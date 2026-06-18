package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import com.sapari.chat.application.port.ChatBroadcaster;
import com.sapari.chat.application.port.RateLimitResult;
import com.sapari.chat.application.port.RateLimiter;
import com.sapari.chat.command.SendChatCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.ChatRateLimitException;
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

class SendChatServiceTest {

    private ChatKickRepository kickRepository;
    private RateLimiter rateLimiter;
    private ChatMessageRepository chatMessageRepository;
    private ChatBroadcaster broadcaster;
    private SendChatService service;

    private final UUID roomId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        kickRepository = mock(ChatKickRepository.class);
        rateLimiter = mock(RateLimiter.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        broadcaster = mock(ChatBroadcaster.class);
        // 순수 로직(정책·필터)·TimeProvider는 실제 인스턴스 — 가짜로 대체할 이유 없음
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

    /** 신규 저장: 저장 시 id(ObjectId) 부여, publish 성공을 흉내낸다. */
    private void stubAllowedAndSaved() {
        when(kickRepository.isKicked(any(), any())).thenReturn(Mono.just(false));
        when(rateLimiter.tryAcquire(any())).thenReturn(Mono.just(new RateLimitResult(true, 0)));
        when(chatMessageRepository.save(any())).thenAnswer(inv ->
                Mono.just(((ChatMessage) inv.getArgument(0)).toBuilder().id("genId").build()));
        when(broadcaster.publish(any(), any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("happy path — BUYER NORMAL: 저장 후 발행, view 반환")
    void buyer_normal_sends() {
        stubAllowedAndSaved();
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕하세요", "c1")))
                .assertNext(view -> {
                    assertThat(view.id()).isEqualTo("genId");
                    assertThat(view.displayMessage()).isEqualTo("안녕하세요");
                })
                .verifyComplete();
        verify(chatMessageRepository).save(any());
        verify(broadcaster).publish(eq(roomId), any());
    }

    @Test
    @DisplayName("isRoomAlive=false → LiveNotActiveException, kicked/저장 미호출 (체크순서 1번, TC#7)")
    void room_not_alive_rejected_first() {
        StepVerifier.create(service.send(command("BUYER", false, false, "NORMAL", "안녕", "c1")))
                .expectError(LiveNotActiveException.class)
                .verify();
        verify(kickRepository, never()).isKicked(any(), any());
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("빈 내용 → IllegalArgumentException")
    void blank_content_rejected() {
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "   ", "c1")))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("200자 초과 → IllegalArgumentException")
    void too_long_rejected() {
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "a".repeat(201), "c1")))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("GUEST는 권한 거부 — kicked/ratelimit 미도달 (Redis 없이 거부, TC#8)")
    void guest_denied_before_redis() {
        StepVerifier.create(service.send(command("GUEST", false, true, "NORMAL", "안녕", "c1")))
                .expectError(ChatPermissionDeniedException.class)
                .verify();
        verify(kickRepository, never()).isKicked(any(), any());
        verify(rateLimiter, never()).tryAcquire(any());
    }

    @Test
    @DisplayName("BUYER가 NOTICE 시도 → 권한 거부")
    void buyer_notice_denied() {
        StepVerifier.create(service.send(command("BUYER", false, true, "NOTICE", "공지", "c1")))
                .expectError(ChatPermissionDeniedException.class)
                .verify();
    }

    @Test
    @DisplayName("강퇴 유저 → UserKickedException, rate limit 미소모 (kicked가 ratelimit보다 먼저)")
    void kicked_user_rejected_before_ratelimit() {
        when(kickRepository.isKicked(any(), any())).thenReturn(Mono.just(true));
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "c1")))
                .expectError(UserKickedException.class)
                .verify();
        verify(rateLimiter, never()).tryAcquire(any());
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("kicked 조회 Redis 에러 → fail-open(전송 허용, L13)")
    void kicked_redis_error_fails_open() {
        when(kickRepository.isKicked(any(), any())).thenReturn(Mono.error(new RuntimeException("redis down")));
        when(rateLimiter.tryAcquire(any())).thenReturn(Mono.just(new RateLimitResult(true, 0)));
        when(chatMessageRepository.save(any())).thenAnswer(inv ->
                Mono.just(((ChatMessage) inv.getArgument(0)).toBuilder().id("genId").build()));
        when(broadcaster.publish(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "c1")))
                .assertNext(view -> assertThat(view.id()).isEqualTo("genId"))
                .verifyComplete();
        verify(chatMessageRepository).save(any());
    }

    @Test
    @DisplayName("BUYER rate limit 초과 → ChatRateLimitException, 저장 미호출")
    void buyer_rate_limited() {
        when(kickRepository.isKicked(any(), any())).thenReturn(Mono.just(false));
        when(rateLimiter.tryAcquire(any())).thenReturn(Mono.just(new RateLimitResult(false, 3)));
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "c1")))
                .expectError(ChatRateLimitException.class)
                .verify();
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("SELLER는 rate limit 면제 — tryAcquire 미호출, 전송 진행")
    void seller_bypasses_rate_limit() {
        when(kickRepository.isKicked(any(), any())).thenReturn(Mono.just(false));
        when(chatMessageRepository.save(any())).thenAnswer(inv ->
                Mono.just(((ChatMessage) inv.getArgument(0)).toBuilder().id("genId").build()));
        when(broadcaster.publish(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.send(command("SELLER", true, true, "NORMAL", "상품 설명입니다", "c1")))
                .assertNext(view -> assertThat(view.id()).isEqualTo("genId"))
                .verifyComplete();
        verify(rateLimiter, never()).tryAcquire(any());
    }

    @Test
    @DisplayName("욕설 마스킹 — displayMessage는 마스킹본, originalMessage는 원문 (저장 인자 검증)")
    void profanity_masked_in_display_only() {
        stubAllowedAndSaved();
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "이건 욕설 이다", "c1")))
                .expectNextCount(1)
                .verifyComplete();
        verify(chatMessageRepository).save(captor.capture());
        ChatMessage saved = captor.getValue();
        assertThat(saved.originalMessage()).isEqualTo("이건 욕설 이다");
        assertThat(saved.displayMessage()).contains("***");
        assertThat(saved.displayMessage()).doesNotContain("욕설");
    }

    @Test
    @DisplayName("dedup — 저장 시 DuplicateKey면 기존 메시지 재조회, 재발행 안 함")
    void duplicate_key_recovers_existing_without_republish() {
        when(kickRepository.isKicked(any(), any())).thenReturn(Mono.just(false));
        when(rateLimiter.tryAcquire(any())).thenReturn(Mono.just(new RateLimitResult(true, 0)));
        when(chatMessageRepository.save(any())).thenReturn(Mono.error(new DuplicateKeyException("dup")));
        ChatMessage existing = ChatMessage.builder()
                .id("existingId").roomId(roomId).senderId(senderId)
                .senderNickname("닉네임").senderRole(ChatRole.BUYER)
                .type(new ChatMessageType.Normal())
                .originalMessage("안녕").displayMessage("안녕")
                .clientMsgId("c1").createdAt(Instant.parse("2026-06-18T00:00:00Z"))
                .build();
        when(chatMessageRepository.findByRoomIdAndSenderIdAndClientMsgId(roomId, senderId, "c1"))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "c1")))
                .assertNext(view -> assertThat(view.id()).isEqualTo("existingId"))
                .verifyComplete();
        verify(broadcaster, never()).publish(any(), any());
    }

    @Test
    @DisplayName("dedup miss — DuplicateKey인데 재조회도 empty면 IllegalStateException (무응답 complete·재시도 루프 방지)")
    void duplicate_key_but_recovery_empty_errors() {
        when(kickRepository.isKicked(any(), any())).thenReturn(Mono.just(false));
        when(rateLimiter.tryAcquire(any())).thenReturn(Mono.just(new RateLimitResult(true, 0)));
        when(chatMessageRepository.save(any())).thenReturn(Mono.error(new DuplicateKeyException("dup")));
        when(chatMessageRepository.findByRoomIdAndSenderIdAndClientMsgId(roomId, senderId, "c1"))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.send(command("BUYER", false, true, "NORMAL", "안녕", "c1")))
                .expectError(IllegalStateException.class)
                .verify();
    }
}
