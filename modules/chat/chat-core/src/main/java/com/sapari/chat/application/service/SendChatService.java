package com.sapari.chat.application.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.sapari.chat.application.port.ChatBroadcaster;
import com.sapari.chat.application.port.RateLimiter;
import com.sapari.chat.command.SendChatCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.ChatRateLimitException;
import com.sapari.chat.domain.exception.KickStoreCorruptedException;
import com.sapari.chat.domain.exception.LiveNotActiveException;
import com.sapari.chat.domain.exception.UserBannedException;
import com.sapari.chat.domain.exception.UserKickedException;
import com.sapari.chat.domain.model.ChatConstants;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatBanRepository;
import com.sapari.chat.domain.repository.ChatKickRepository;
import com.sapari.chat.domain.repository.ChatMessageRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.domain.rule.ProfanityFilter;
import com.sapari.chat.port.SendChatUseCase;
import com.sapari.chat.view.ChatMessageView;
import com.sapari.global.time.TimeProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 메시지 전송 파이프라인 (reactive — streaming-app 이벤트루프에서 호출).
 *
 * <p><b>전송 체크 순서는 계약이다</b>: isRoomAlive → 입력검증 → 권한 → kicked → rate limit → 욕설필터 → 저장 → 발행.
 * 비용 0(메모리·입력)을 앞에 두고 Redis는 뒤로 미뤄 불필요한 I/O를 차단한다.
 *
 * <p>{@code @Transactional}을 두지 않는다 — 단일 Mongo 문서 저장 + Redis 발행이라 다중 엔티티 트랜잭션이 없고,
 * 재전송 멱등은 (roomId,senderId,clientMsgId) unique 인덱스가 보장한다(DuplicateKey → 기존 메시지 재조회).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SendChatService implements SendChatUseCase {

    private static final int MAX_CONTENT_LENGTH = 200;

    /**
     * 열화 로그 간격. <b>건수가 아니라 경과시간</b>으로 솎아낸다 — 건수 기준은 카운터가 프로세스 생애
     * 누적이라 두 번째 이후의 짧은 장애가 통째로 침묵한다(규모가 작을수록 안 남는 역방향).
     */
    private static final Duration DEGRADED_LOG_INTERVAL = Duration.ofSeconds(10);

    private final ChatPermissionPolicy permissionPolicy;
    private final ProfanityFilter profanityFilter;
    private final ChatKickRepository kickRepository;
    private final ChatBanRepository banRepository;
    private final RateLimiter rateLimiter;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatBroadcaster broadcaster;
    private final TimeProvider timeProvider;

    // 열화 경로 관측 — 통과시키되 규모와 시작 시점은 남긴다.
    private final AtomicLong kickedFailOpenCount = new AtomicLong();
    private final AtomicLong kickStoreCorruptedCount = new AtomicLong();
    private final AtomicLong bannedFailOpenCount = new AtomicLong();
    private final AtomicLong publishFailureCount = new AtomicLong();
    private final AtomicReference<Instant> lastKickedFailOpenLog = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> lastKickStoreCorruptedLog = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> lastBannedFailOpenLog = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> lastPublishFailureLog = new AtomicReference<>(Instant.EPOCH);

    /**
     * 강퇴 조회 실패를 기록한다.
     *
     * <p><b>여기서는 던질 수 있는 호출을 하지 마라.</b> 이 메서드는 fail-open 분기(onErrorResume 람다) 안에서
     * 불리는데, 예외가 나가면 Reactor가 하류로 전파해 전송이 <i>거부</i>된다 — 가용성 우선 정책이 정반대로
     * 뒤집힌다. 지금 호출하는 것들(원자적 증가·주입된 Clock·로거·instanceof)은 모두 던지지 않는다.
     * 메트릭·감사 같은 걸 추가할 때 이 불변식을 깨지 않도록 확인할 것.
     *
     * <p>낫지 않는 실패는 <b>따로 센다</b>. 카운터와 스로틀을 공유하면 먼저 찍은 쪽이 다른 쪽의 첫 발생을
     * 덮어, 영영 안 낫는 상태가 곧 복구될 장애의 로그에 묻힌다.
     */
    private void recordKickedFailOpen(UUID roomId, Throwable err) {
        if (err instanceof KickStoreCorruptedException corrupted) {
            long count = kickStoreCorruptedCount.incrementAndGet();
            if (shouldLog(lastKickStoreCorruptedLog)) {
                // 예외 객체는 싣지 않는다 — 진단은 이 문장과 key로 끝나고, 스택은 Reactor 내부를 가리켜
                // 손댈 곳을 알려주지 못한다. 대신 재시도로 낫지 않는다는 사실을 문장에 박는다.
                log.error("강퇴 명단 키의 타입이 어긋났다 — 이 방의 강퇴는 사람이 그 키를 치울 때까지 계속 "
                                + "무력화된다(재시도로 낫지 않음). fail-open으로 전송 허용(누적 {}건) key={} roomId={}",
                        count, corrupted.getKey(), roomId);
            }
            return;
        }
        long count = kickedFailOpenCount.incrementAndGet();
        if (shouldLog(lastKickedFailOpenLog)) {
            log.error("강퇴 조회 실패 — fail-open으로 전송 허용(누적 {}건) 표본 roomId={}",
                    count, roomId, err);
        }
    }

    /**
     * 밴 조회 실패를 기록한다. 강퇴와 카운터·스로틀을 나누는 이유는 위와 같다 — 함께 세면 먼저 찍은 쪽이
     * 다른 쪽의 첫 발생을 덮는다.
     *
     * <p>roomId를 남기지 않는 것은 의도다. 밴은 계정 단위라 어느 방에서 조회했는지가 진단에 쓰이지 않고,
     * userId는 로그에 싣지 않는다. 여기서 필요한 것은 "밴 집행이 언제부터 몇 건이나 열렸는가" 하나다.
     *
     * <p>{@link #recordKickedFailOpen}과 같은 제약을 받는다 — <b>던질 수 있는 호출을 넣지 마라.</b>
     */
    private void recordBannedFailOpen(Throwable err) {
        long count = bannedFailOpenCount.incrementAndGet();
        if (shouldLog(lastBannedFailOpenLog)) {
            log.error("밴 조회 실패 — fail-open으로 전송 허용(누적 {}건)", count, err);
        }
    }

    /**
     * 마지막 로그로부터 간격이 지났으면 true — 첫 발생은 반드시 남고(EPOCH 시작) 이후는 간격당 1건.
     * 시각은 {@code TimeProvider}에서 받는다 — application 레이어는 시스템 시계를 직접 읽지 못한다
     * (ArchUnit 규칙). 그래서 <b>벽시계</b>다: NTP가 뒤로 뛰면 그 폭만큼 이 로그가 침묵한다.
     * 단조시계를 쓰는 {@code RedisRateLimiter} 쪽과 안전성 등급이 다르며, 영향은 관측 신호에 한정된다.
     */
    private boolean shouldLog(AtomicReference<Instant> lastLog) {
        Instant now = timeProvider.now();
        Instant last = lastLog.get();
        return !Duration.between(last, now).minus(DEGRADED_LOG_INTERVAL).isNegative()
                && lastLog.compareAndSet(last, now);
    }

    @Override
    public Mono<ChatMessageView> send(SendChatCommand command) {
        // defer로 감싸 동기 검증(타입 파싱 등)의 throw도 onError 신호로 전환 — 호출처 onErrorResume이 일관 동작
        return Mono.defer(() -> runPipeline(command));
    }

    private Mono<ChatMessageView> runPipeline(SendChatCommand command) {
        // 1) isRoomAlive — 세션 메모리 스냅샷(command), 비용 0, 가장 먼저
        if (!command.isRoomAlive()) {
            return Mono.error(new LiveNotActiveException("진행 중인 라이브가 아닙니다."));
        }
        // 2) 입력검증 — Redis 닿기 전 즉시 거부
        String content = command.content();
        if (content == null || content.isBlank()) {
            return Mono.error(new IllegalArgumentException("메시지 내용이 비어 있습니다."));
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            return Mono.error(new IllegalArgumentException("메시지는 " + MAX_CONTENT_LENGTH + "자를 초과할 수 없습니다."));
        }
        // clientMsgId를 필수로 받는다 — 재전송 멱등이 이 값에만 걸려 있다(unique 인덱스가 부분 인덱스라
        // 값이 없으면 적용되지 않는다). 없어도 받아주면 그 메시지만 조용히 멱등이 꺼져, 재전송이 곧 중복 저장이 된다.
        String clientMsgId = command.clientMsgId();
        if (clientMsgId == null || clientMsgId.isBlank()) {
            return Mono.error(new IllegalArgumentException("clientMsgId는 필수입니다."));
        }
        // 필수로 만든 이상 상한도 같이 정한다. 이 값은 본문과 달리 Mongo 문서 + unique 인덱스 키로 들어가고
        // 봉투에 실려 전 Pod로 중계되는데, 상한이 없으면 프레임 한도(수십 KB)까지 채워 보낼 수 있다.
        // 그러면 본문 200자 제한이 우회된다 — 레이트리밋이 면제되는 진행자·운영자는 그 속도 제한도 없다.
        if (clientMsgId.length() > ChatConstants.MAX_CLIENT_MSG_ID_LENGTH) {
            return Mono.error(new IllegalArgumentException(
                    "clientMsgId는 " + ChatConstants.MAX_CLIENT_MSG_ID_LENGTH + "자를 초과할 수 없습니다."));
        }
        ChatRole role = ChatRole.valueOf(command.senderRole());   // 잘못된 role 문자열이면 throw → defer가 onError로
        ChatMessageType type = toType(command.messageType());     // NORMAL/NOTICE만 허용(SYSTEM·미지 타입 거부)

        // 3) 권한 — GUEST·권한없는 NOTICE는 Redis 없이 거부
        if (!permissionPolicy.canSend(role, command.isRoomOwner(), type)) {
            return Mono.error(new ChatPermissionDeniedException("해당 메시지를 보낼 권한이 없습니다."));
        }

        // 4) kicked·banned — 에러는 둘 다 fail-open(전송 허용) — 어댑터는 error 전파, 매핑은 여기.
        // 통과시키되 흔적은 남긴다 — 이 경로가 열린 구간은 집행이 무력화된 구간이라, 시작 시점과 규모를
        // 남겨야 사후에 "언제 몇 건이 우회했는가"를 짚을 수 있다.
        //
        // 밴을 여기서도 보는 이유: 입장 게이트는 <b>새 접속만</b> 막는다. 한 사람이 여러 방에 동시 접속한
        // 상태에서 한 방의 강퇴로 밴이 걸리면, 그 방 세션만 닫히고 나머지 방 세션은 그대로 살아 계속
        // 발화한다 — "어느 방에도 참여할 수 없다"는 밴의 정의와 어긋난다. 이 검사가 그 세션을 첫 발화에서
        // 끊는다(전송 계층이 사유를 보낸 뒤 세션을 닫는다).
        //
        // zip 으로 <b>동시에</b> 띄운다. 이어 붙이면 메시지마다 왕복이 하나 더 늘고, 그 비용은 방 크기에
        // 비례해 커진다. 두 조회는 서로의 결과를 쓰지 않으므로 순서를 지킬 이유가 없다.
        Mono<Boolean> kickedCheck = kickRepository.isKicked(command.roomId(), command.senderId())
                .onErrorResume(err -> {
                    recordKickedFailOpen(command.roomId(), err);
                    return Mono.just(false);
                });
        Mono<Boolean> bannedCheck = banRepository.isBanned(command.senderId())
                .onErrorResume(err -> {
                    recordBannedFailOpen(err);
                    return Mono.just(false);
                });
        return Mono.zip(kickedCheck, bannedCheck)
                // 강퇴를 먼저 본다. 둘 다 걸린 사람에게는 이 방에서 끊긴 사유가 더 구체적이고,
                // 어느 쪽이든 전송 계층의 처리(사유 프레임 + 세션 종료)는 같다.
                .flatMap(checks -> {
                    if (checks.getT1()) {
                        return Mono.<Void>error(new UserKickedException("강퇴되어 메시지를 보낼 수 없습니다."));
                    }
                    if (checks.getT2()) {
                        return Mono.<Void>error(new UserBannedException("이용이 제한되어 메시지를 보낼 수 없습니다."));
                    }
                    return enforceRateLimit(role, command.isRoomOwner(), command.senderId());   // 5) rate limit
                })
                .then(Mono.defer(() -> persistAndPublish(command, role, type, content)));  // 6) 욕설필터·저장·발행
    }

    /**
     * 5) Rate limit — 면제는 <b>이 방송을 진행하는 판매자</b>와 운영자만. 어댑터가 Redis 장애 시 fail-open.
     *
     * <p>면제 근거가 "상품설명 연속전송"이라 방주인에게만 해당한다. role만 보면 판매자 계정이 남의 방에
     * 시청자로 들어가 무제한 도배할 수 있는데, 권한 정책이 세운 <b>role + 방 소유 두 축</b> 원칙과 어긋난다.
     */
    private Mono<Void> enforceRateLimit(ChatRole role, boolean isRoomOwner, UUID senderId) {
        if (role == ChatRole.ADMIN || (role == ChatRole.SELLER && isRoomOwner)) {
            return Mono.empty();
        }
        return rateLimiter.tryAcquire(senderId)
                .flatMap(result -> result.allowed()
                        ? Mono.<Void>empty()
                        : Mono.error(new ChatRateLimitException(
                                "메시지를 너무 빠르게 보내고 있습니다.", result.retryAfterSeconds())));
    }

    /** 6) 욕설필터 → 도메인 생성 → 저장(먼저) → 발행. DuplicateKey(재전송)는 기존 메시지 재조회로 멱등 처리(재발행 생략). */
    private Mono<ChatMessageView> persistAndPublish(SendChatCommand command, ChatRole role,
                                                    ChatMessageType type, String content) {
        String displayMessage = profanityFilter.filter(content);
        ChatMessage message = ChatMessage.builder()
                .id(null)                          // ObjectId는 save 시점에 reactive 드라이버가 부여
                .roomId(command.roomId())
                .senderId(command.senderId())
                .senderNickname(command.senderNickname())
                .senderEmail(command.senderEmail())
                .senderRole(role)
                .type(type)
                .originalMessage(content)          // 마스킹 전 원문(방주인 토글·증거)
                .displayMessage(displayMessage)    // 마스킹 적용본
                .clientMsgId(command.clientMsgId())
                .createdAt(timeProvider.now())     // Instant.now() 직접 금지 — TimeProvider
                .build();

        // persist-then-publish: DuplicateKey를 발행 전에 잡아 중복 broadcast를 억제한다
        return chatMessageRepository.save(message)
                .flatMap(saved -> broadcaster.publish(command.roomId(), saved)
                        // publish 실패는 흡수한다 — 저장은 됐으므로 반환 view(=ack)를 그대로 돌려준다.
                        // 낙관적 렌더 모델: 발신자는 send 시점에 자기 메시지를 이미 로컬 렌더했고 이 ack로 확정한다
                        // → 서버 에코 불필요. 크로스 Pod·동일 Pod 타 세션은 실시간 미전달(허용, 메시지는 영속됨).
                        .onErrorResume(err -> {
                            // 장애 중엔 전 전송이 이 경로다 — 건당 스택트레이스는 원인 로그를 묻는다(레이트리밋과 동일)
                            long count = publishFailureCount.incrementAndGet();
                            if (shouldLog(lastPublishFailureLog)) {
                                log.error("Redis publish 실패 — 저장 보장, 발신자는 낙관적 렌더+ack로 표시"
                                        + "(누적 {}건) 표본 roomId={}", count, command.roomId(), err);
                            }
                            return Mono.empty();
                        })
                        .thenReturn(saved.toView()))
                .onErrorResume(DuplicateKeyException.class, e -> recoverDuplicate(command));
    }

    private Mono<ChatMessageView> recoverDuplicate(SendChatCommand command) {
        return chatMessageRepository
                .findByRoomIdAndSenderIdAndClientMsgId(command.roomId(), command.senderId(), command.clientMsgId())
                .map(ChatMessage::toView)
                // 재조회 miss 시 empty 전파를 막는다 — 그대로 두면 send()가 무응답 complete → 동일 clientMsgId 재시도 루프
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "DuplicateKey 후 재조회 miss — clientMsgId=" + command.clientMsgId())));
    }

    private ChatMessageType toType(String messageType) {
        return switch (messageType) {
            case "NORMAL" -> new ChatMessageType.Normal();
            case "NOTICE" -> new ChatMessageType.Notice();
            default -> throw new IllegalArgumentException("전송할 수 없는 메시지 타입입니다: " + messageType);
        };
    }
}
