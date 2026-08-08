package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.sapari.chat.application.port.ChatBroadcaster;
import com.sapari.chat.application.port.RateLimitResult;
import com.sapari.chat.application.port.RateLimiter;
import com.sapari.chat.command.SendChatCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.ChatRateLimitException;
import com.sapari.chat.domain.exception.KickStoreCorruptedException;
import com.sapari.chat.domain.exception.LiveNotActiveException;
import com.sapari.chat.domain.exception.UserKickedException;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatKickRepository;
import com.sapari.chat.domain.repository.ChatMessageRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.domain.rule.ProfanityFilter;
import com.sapari.global.time.TimeProvider;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class SendChatServiceTest {

    @Mock
    private ChatKickRepository kickRepository;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatBroadcaster broadcaster;

    // @InjectMocks를 쓰지 않는다 — 정책·필터는 가짜로 바꾸면 검증 대상이 사라지는 순수 로직이라
    // 실제 인스턴스로 조립한다. 포트만 @Mock이다.
    private SendChatService service;

    private final UUID roomId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SendChatService(
                new ChatPermissionPolicy(),
                new ProfanityFilter(Set.of("욕설"), Set.of()),
                kickRepository, rateLimiter, chatMessageRepository, broadcaster,
                new TimeProvider(Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneOffset.UTC)));
    }

    private SendChatCommand command(String role, boolean isRoomOwner, boolean isRoomAlive,
                                    String type, String content, String clientMsgId) {
        return new SendChatCommand(roomId, senderId, role, isRoomOwner, isRoomAlive,
                "닉네임", "buyer@example.com", type, content, clientMsgId);
    }

    /** 강퇴 아님 + 신규 저장(id 부여) + publish 성공. 레이트리밋을 거치지 않는 역할(면제)에 쓴다. */
    private void stubSaved() {
        given(kickRepository.isKicked(any(), any())).willReturn(Mono.just(false));
        willAnswer(inv -> Mono.just(((ChatMessage) inv.getArgument(0)).toBuilder().id("genId").build()))
                .given(chatMessageRepository).save(any());
        given(broadcaster.publish(any(), any())).willReturn(Mono.empty());
    }

    /** stubSaved에 레이트리밋 통과를 더한 것 — 면제 대상이 아닌 역할에 쓴다. */
    private void stubAllowedAndSaved() {
        stubSaved();
        given(rateLimiter.tryAcquire(any())).willReturn(Mono.just(new RateLimitResult(true, 0)));
    }

    @Test
    @DisplayName("happy path — BUYER NORMAL: 저장 후 발행, view 반환")
    void buyer_normal_sends() {
        // given
        stubAllowedAndSaved();

        // when
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕하세요", "c1")))
                .assertNext(view -> {
                    assertThat(view.id()).isEqualTo("genId");
                    assertThat(view.displayMessage()).isEqualTo("안녕하세요");
                })
                .verifyComplete();

        // then
        then(chatMessageRepository).should(times(1)).save(any());
        then(broadcaster).should(times(1)).publish(eq(roomId), any());
    }

    @Test
    @DisplayName("isRoomAlive=false → LiveNotActiveException, kicked/저장 미호출 (체크순서 1번, TC#7)")
    void room_not_alive_rejected_first() {
        // when
        StepVerifier.create(service.send(command("BUYER", false, false, "NORMAL", "안녕", "c1")))
                .expectError(LiveNotActiveException.class)
                .verify();

        // then
        then(kickRepository).should(never()).isKicked(any(), any());
        then(chatMessageRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("빈 내용 → IllegalArgumentException")
    void blank_content_rejected() {
        // when & then
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "   ", "c1")))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("200자 초과 → IllegalArgumentException")
    void too_long_rejected() {
        // when & then
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "a".repeat(201), "c1")))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("GUEST는 권한 거부 — kicked/ratelimit 미도달 (Redis 없이 거부, TC#8)")
    void guest_denied_before_redis() {
        // when
        StepVerifier.create(service.send(command("GUEST", false, true, "NORMAL", "안녕", "c1")))
                .expectError(ChatPermissionDeniedException.class)
                .verify();

        // then
        then(kickRepository).should(never()).isKicked(any(), any());
        then(rateLimiter).should(never()).tryAcquire(any());
    }

    @Test
    @DisplayName("ADMIN은 방 소유와 무관하게 rate limit 면제 — 운영자는 role 기준")
    void admin_is_exempt_regardless_of_room() {
        // given
        stubSaved();

        // when
        StepVerifier.create(service.send(command("ADMIN", false, true, "NORMAL", "운영 안내", "c1")))
                .expectNextCount(1)
                .verifyComplete();

        // then
        then(rateLimiter).should(never()).tryAcquire(any());
    }

    @Test
    @DisplayName("남의 방에 들어온 SELLER는 시청자이므로 rate limit 적용")
    void visiting_seller_is_rate_limited() {
        // given
        // 면제 근거는 "상품설명 연속전송"이라 이 방을 진행하는 사람에게만 해당한다.
        // role만 보면 판매자 계정이 남의 방에서 무제한 도배할 수 있다(권한 정책의 두 축 원칙과 어긋남).
        given(kickRepository.isKicked(any(), any())).willReturn(Mono.just(false));
        given(rateLimiter.tryAcquire(any())).willReturn(Mono.just(new RateLimitResult(false, 3)));

        // when
        StepVerifier.create(service.send(command("SELLER", false, true, "NORMAL", "도배", "c1")))
                .expectError(ChatRateLimitException.class)
                .verify();

        // then
        then(rateLimiter).should(times(1)).tryAcquire(any());
    }

    @Test
    @DisplayName("BUYER가 NOTICE 시도 → 권한 거부")
    void buyer_notice_denied() {
        // when & then
        StepVerifier.create(service.send(command("BUYER", false, true, "NOTICE", "공지", "c1")))
                .expectError(ChatPermissionDeniedException.class)
                .verify();
    }

    @Test
    @DisplayName("강퇴 유저 → UserKickedException, rate limit 미소모 (kicked가 ratelimit보다 먼저)")
    void kicked_user_rejected_before_ratelimit() {
        // given
        given(kickRepository.isKicked(any(), any())).willReturn(Mono.just(true));

        // when
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "c1")))
                .expectError(UserKickedException.class)
                .verify();

        // then
        then(rateLimiter).should(never()).tryAcquire(any());
        then(chatMessageRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("kicked 조회 Redis 에러 → fail-open(전송 허용, L13)")
    void kicked_redis_error_fails_open() {
        // given
        given(kickRepository.isKicked(any(), any())).willReturn(Mono.error(new RuntimeException("redis down")));
        given(rateLimiter.tryAcquire(any())).willReturn(Mono.just(new RateLimitResult(true, 0)));
        willAnswer(inv -> Mono.just(((ChatMessage) inv.getArgument(0)).toBuilder().id("genId").build()))
                .given(chatMessageRepository).save(any());
        given(broadcaster.publish(any(), any())).willReturn(Mono.empty());

        // when
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "c1")))
                .assertNext(view -> assertThat(view.id()).isEqualTo("genId"))
                .verifyComplete();

        // then
        then(chatMessageRepository).should(times(1)).save(any());
    }

    @Test
    @DisplayName("키 타입 충돌도 fail-open이지만 로그는 갈라진다 — 재시도로 낫지 않는다는 사실이 남아야 한다")
    void kickStoreCorrupted_failsOpenButLogsSeparately() {
        // given: 조회가 Redis 복구와 무관하게 계속 실패하는 상태
        String key = "chat:kicked:" + roomId;
        given(kickRepository.isKicked(any(), any()))
                .willReturn(Mono.error(new KickStoreCorruptedException(key, new RuntimeException("WRONGTYPE"))));
        given(rateLimiter.tryAcquire(any())).willReturn(Mono.just(new RateLimitResult(true, 0)));
        willAnswer(inv -> Mono.just(((ChatMessage) inv.getArgument(0)).toBuilder().id("genId").build()))
                .given(chatMessageRepository).save(any());
        given(broadcaster.publish(any(), any())).willReturn(Mono.empty());

        Logger logger = (Logger) LoggerFactory.getLogger(SendChatService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        // when: 가용성 우선 정책은 그대로 — 전송은 통과해야 한다
        try {
            StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "c1")))
                    .assertNext(view -> assertThat(view.id()).isEqualTo("genId"))
                    .verifyComplete();
        } finally {
            logger.detachAppender(appender);
        }

        // then: 일시 장애 문구가 아니라 "낫지 않는다 + 치울 키"가 남는다. 분기를 지우면 이 단언이 깨진다
        assertThat(appender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage())
                            .contains("재시도로 낫지 않음")
                            .contains(key);
                });
    }

    @Test
    @DisplayName("BUYER rate limit 초과 → ChatRateLimitException, 저장 미호출")
    void buyer_rate_limited() {
        // given
        given(kickRepository.isKicked(any(), any())).willReturn(Mono.just(false));
        given(rateLimiter.tryAcquire(any())).willReturn(Mono.just(new RateLimitResult(false, 3)));

        // when
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "c1")))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ChatRateLimitException.class);
                    // retryAfterSeconds가 예외에 실려 transport(RATE_LIMIT 응답)까지 운반되는지 — 유실 방지
                    assertThat(((ChatRateLimitException) e).getRetryAfterSeconds()).isEqualTo(3);
                })
                .verify();

        // then
        then(chatMessageRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("방송을 진행하는 SELLER는 rate limit 면제 — tryAcquire 미호출, 전송 진행")
    void broadcasting_seller_bypasses_rate_limit() {
        // given
        given(kickRepository.isKicked(any(), any())).willReturn(Mono.just(false));
        willAnswer(inv -> Mono.just(((ChatMessage) inv.getArgument(0)).toBuilder().id("genId").build()))
                .given(chatMessageRepository).save(any());
        given(broadcaster.publish(any(), any())).willReturn(Mono.empty());

        // when
        StepVerifier.create(service.send(command("SELLER", true, true, "NORMAL", "상품 설명입니다", "c1")))
                .assertNext(view -> assertThat(view.id()).isEqualTo("genId"))
                .verifyComplete();

        // then
        then(rateLimiter).should(never()).tryAcquire(any());
    }

    @Test
    @DisplayName("욕설 마스킹 — displayMessage는 마스킹본, originalMessage는 원문 (저장 인자 검증)")
    void profanity_masked_in_display_only() {
        // given
        stubAllowedAndSaved();
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);

        // when
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "이건 욕설 이다", "c1")))
                .expectNextCount(1)
                .verifyComplete();

        // then
        then(chatMessageRepository).should(times(1)).save(captor.capture());
        ChatMessage saved = captor.getValue();
        assertThat(saved.originalMessage()).isEqualTo("이건 욕설 이다");
        assertThat(saved.displayMessage()).contains("***");
        assertThat(saved.displayMessage()).doesNotContain("욕설");
    }

    @Test
    @DisplayName("dedup — 저장 시 DuplicateKey면 기존 메시지 재조회, 재발행 안 함")
    void duplicate_key_recovers_existing_without_republish() {
        // given
        given(kickRepository.isKicked(any(), any())).willReturn(Mono.just(false));
        given(rateLimiter.tryAcquire(any())).willReturn(Mono.just(new RateLimitResult(true, 0)));
        given(chatMessageRepository.save(any())).willReturn(Mono.error(new DuplicateKeyException("dup")));
        ChatMessage existing = ChatMessage.builder()
                .id("existingId").roomId(roomId).senderId(senderId)
                .senderNickname("닉네임").senderRole(ChatRole.BUYER)
                .type(new ChatMessageType.Normal())
                .originalMessage("안녕").displayMessage("안녕")
                .clientMsgId("c1").createdAt(Instant.parse("2026-06-18T00:00:00Z"))
                .build();
        given(chatMessageRepository.findByRoomIdAndSenderIdAndClientMsgId(roomId, senderId, "c1"))
                .willReturn(Mono.just(existing));

        // when
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "c1")))
                .assertNext(view -> assertThat(view.id()).isEqualTo("existingId"))
                .verifyComplete();

        // then
        then(broadcaster).should(never()).publish(any(), any());
    }

    @Test
    @DisplayName("publish 실패(비-Duplicate) → 에러 흡수, 저장 메시지 view 반환 (TC#22 — 발신자에겐 성공)")
    void publish_failure_absorbed_returns_saved_view() {
        // given
        given(kickRepository.isKicked(any(), any())).willReturn(Mono.just(false));
        given(rateLimiter.tryAcquire(any())).willReturn(Mono.just(new RateLimitResult(true, 0)));
        willAnswer(inv -> Mono.just(((ChatMessage) inv.getArgument(0)).toBuilder().id("genId").build()))
                .given(chatMessageRepository).save(any());
        given(broadcaster.publish(any(), any())).willReturn(Mono.error(new RuntimeException("redis publish down")));

        // when & then
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "c1")))
                .assertNext(view -> assertThat(view.id()).isEqualTo("genId"))
                .verifyComplete();
    }

    @Test
    @DisplayName("dedup miss — DuplicateKey인데 재조회도 empty면 IllegalStateException (무응답 complete·재시도 루프 방지)")
    void duplicate_key_but_recovery_empty_errors() {
        // given
        given(kickRepository.isKicked(any(), any())).willReturn(Mono.just(false));
        given(rateLimiter.tryAcquire(any())).willReturn(Mono.just(new RateLimitResult(true, 0)));
        given(chatMessageRepository.save(any())).willReturn(Mono.error(new DuplicateKeyException("dup")));
        given(chatMessageRepository.findByRoomIdAndSenderIdAndClientMsgId(roomId, senderId, "c1"))
                .willReturn(Mono.empty());

        // when & then
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "c1")))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    @DisplayName("clientMsgId 없으면 거부 — 없으면 재전송 멱등이 조용히 꺼진다(부분 unique 인덱스)")
    void missing_clientMsgId_rejected() {
        // when & then
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", null)))
                .expectError(IllegalArgumentException.class)
                .verify();

        then(chatMessageRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("clientMsgId 64자 초과 → 거부. 본문 200자 제한을 이 필드로 우회할 수 있으면 안 된다")
    void clientMsgId_tooLong_rejected() {
        // given: 이 값은 Mongo 문서 + unique 인덱스 키로 들어가고 봉투에 실려 전 Pod로 중계된다.
        // 상한이 없으면 프레임 한도까지 채워 보낼 수 있고, 레이트리밋이 면제되는 진행자는 속도 제한도 없다.
        String tooLong = "x".repeat(65);

        // when & then
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", tooLong)))
                .expectError(IllegalArgumentException.class)
                .verify();
        then(kickRepository).should(never()).isKicked(any(), any());
        then(chatMessageRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("clientMsgId 정확히 64자 → 통과(경계는 허용 쪽이다 — UUID 36자 계약에 여유를 둔 값)")
    void clientMsgId_atLimit_allowed() {
        // given
        stubAllowedAndSaved();

        // when & then
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "x".repeat(64))))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("clientMsgId가 공백뿐이면 거부 — blank를 통과시키면 부분 unique 인덱스가 안 걸려 멱등이 꺼진다")
    void clientMsgId_blank_rejected() {
        // when & then
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "   ")))
                .expectError(IllegalArgumentException.class)
                .verify();
        then(chatMessageRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("클라가 messageType=SYSTEM을 보내면 거부 — SYSTEM은 서버만 만드는 신호다")
    void systemType_fromClient_rejected() {
        // when & then: 통과시키면 일반 사용자가 '방송이 종료되었습니다' 같은 신호를 위조해 방에 뿌릴 수 있다
        StepVerifier.create(service.send(command("BUYER", false, true, "SYSTEM", "안녕", "c1")))
                .expectError(IllegalArgumentException.class)
                .verify();
        then(chatMessageRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("모르는 messageType은 거부 — 조용히 NORMAL로 떨어뜨리면 계약에 없는 값이 저장된다")
    void unknownType_rejected() {
        // when & then
        StepVerifier.create(service.send(command("BUYER", false, true, "WHISPER", "안녕", "c1")))
                .expectError(IllegalArgumentException.class)
                .verify();
        then(chatMessageRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("모르는 senderRole은 에러로 나온다 — 스트림을 죽이지 않고 그 전송만 실패시킨다")
    void unknownRole_becomesErrorSignal() {
        // when & then: 신뢰 필드라 정상 경로에선 올 수 없지만, valueOf가 조립 시점에 터지면
        // onErrorResume을 지나쳐 인바운드 스트림이 죽는다. 반드시 onError 신호여야 한다.
        StepVerifier.create(service.send(command("SUPERUSER", false, true, "NORMAL", "안녕", "c1")))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
}
