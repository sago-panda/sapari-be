package com.sapari.chat.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.UUID;

import com.sapari.chat.domain.model.ChatKickLog;
import com.sapari.chat.domain.repository.ChatKickLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatKickLogRepository} 구현 — 도메인 기록을 네이티브 upsert 한 번으로 옮긴다.
 *
 * <p>트랜잭션을 열지 않는다. 쓰기 쿼리라 호출자가 연 읽기·쓰기 트랜잭션 안에서 돌아야 하고,
 * 그 경계가 어디서 닫히는지가 강퇴 흐름의 설계다(커밋 확정 후에야 Redis·발행으로 넘어간다).
 *
 * <p><b>스테레오타입을 붙이지 않는다.</b> chat-core는 리액티브 앱과 블로킹 앱이 함께 의존하는데, 스캔되는
 * 순간 <i>양쪽</i>에 빈이 만들어지려 한다 — 관계형 DB가 없는 streaming-app에서는 그 시도가 곧 부팅 실패다.
 * 그래서 블로킹 어댑터는 그 스택을 실제로 가진 앱이 {@code @Bean}으로 등록한다. 반대로 리액티브 어댑터는
 * 지금처럼 스캔에 맡겨도 되는데, 블로킹 앱은 chat-core를 스캔하지 않고 필요한 것만 가져가기 때문이다.
 */
@RequiredArgsConstructor
public class ChatKickLogRepositoryImpl implements ChatKickLogRepository {

    private final ChatKickLogJpaRepository jpaRepository;

    @Override
    public boolean appendIfAbsent(ChatKickLog log) {
        // ON CONFLICT DO NOTHING은 많아야 한 행에 영향을 준다 — 0이면 이미 있었다는 뜻이다.
        return jpaRepository.insertIfAbsent(
                log.targetUserId(),
                log.roomId(),
                log.kickedById(),
                log.kickedByRole().name(),
                log.triggeringMessage(),
                log.kickedAt()) > 0;
    }

    @Override
    public long countSince(UUID userId, Instant since) {
        return jpaRepository.countSince(userId, since);
    }
}
