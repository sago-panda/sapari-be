package com.sapari.chat.application.service;

import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.sapari.chat.application.port.ChatBroadcaster;
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
import com.sapari.chat.port.SendChatUseCase;
import com.sapari.chat.view.ChatMessageView;
import com.sapari.global.time.TimeProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 메시지 전송 파이프라인 (reactive — streaming-app 이벤트루프에서 호출).
 *
 * <p>전송 체크 순서(§10.12): isRoomAlive → 입력검증 → 권한 → kicked → rate limit → 욕설필터 → 저장 → 발행.
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

    private final ChatPermissionPolicy permissionPolicy;
    private final ProfanityFilter profanityFilter;
    private final ChatKickRepository kickRepository;
    private final RateLimiter rateLimiter;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatBroadcaster broadcaster;
    private final TimeProvider timeProvider;

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
        ChatRole role = ChatRole.valueOf(command.senderRole());   // 잘못된 role 문자열이면 throw → defer가 onError로
        ChatMessageType type = toType(command.messageType());     // NORMAL/NOTICE만 허용(SYSTEM·미지 타입 거부)

        // 3) 권한 — GUEST·권한없는 NOTICE는 Redis 없이 거부
        if (!permissionPolicy.canSend(role, command.isRoomOwner(), type)) {
            return Mono.error(new ChatPermissionDeniedException("해당 메시지를 보낼 권한이 없습니다."));
        }

        // 4) kicked — Redis 1회 SISMEMBER. 에러는 fail-open(전송 허용, L13) — 어댑터는 error 전파, 매핑은 여기
        return kickRepository.isKicked(command.roomId(), command.senderId())
                .onErrorReturn(false)
                .flatMap(kicked -> kicked
                        ? Mono.<Void>error(new UserKickedException("강퇴되어 메시지를 보낼 수 없습니다."))
                        : enforceRateLimit(role, command.senderId()))                 // 5) rate limit
                .then(Mono.defer(() -> persistAndPublish(command, role, type, content)));  // 6) 욕설필터·저장·발행
    }

    /** 5) Rate limit — BUYER만 적용(SELLER·ADMIN은 상품설명 연속전송 허용). 어댑터가 Redis 장애 시 fail-open. */
    private Mono<Void> enforceRateLimit(ChatRole role, UUID senderId) {
        if (role != ChatRole.BUYER) {
            return Mono.empty();
        }
        return rateLimiter.tryAcquire(senderId)
                .flatMap(result -> result.allowed()
                        ? Mono.<Void>empty()
                        : Mono.error(new ChatRateLimitException("메시지를 너무 빠르게 보내고 있습니다.")));
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

        // persist-then-publish: DuplicateKey를 발행 전에 잡아 중복 broadcast를 억제(§7.2)
        return chatMessageRepository.save(message)
                .flatMap(saved -> broadcaster.publish(command.roomId(), saved)
                        // publish 실패는 흡수한다 — 저장은 됐으므로 발신자에겐 view를 반환(가용성 우선).
                        // 크로스 Pod·동일 Pod 타 세션은 미전달(허용). 발신자 본인 세션 직접 에코는
                        // senderSessionId(WS 세션)를 쥔 transport(T9) 책임이라 여기선 흡수까지만 한다.
                        .onErrorResume(err -> {
                            log.error("Redis publish 실패 — 저장은 보장, 발신자 에코는 transport(T9) 책임 roomId={}",
                                    command.roomId(), err);
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
