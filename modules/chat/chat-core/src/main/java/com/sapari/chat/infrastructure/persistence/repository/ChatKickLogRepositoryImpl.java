package com.sapari.chat.infrastructure.persistence.repository;

import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.model.ChatKickLog;
import com.sapari.chat.domain.repository.ChatKickLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatKickLogRepository} 구현 — 도메인 기록을 네이티브 upsert 한 번으로 옮긴다.
 *
 * <p>트랜잭션을 열지 않는다. 쓰기 쿼리라 호출자가 연 읽기·쓰기 트랜잭션 안에서 돌아야 하고,
 * 그 경계가 어디서 닫히는지가 강퇴 흐름의 설계다(커밋 확정 후에야 Redis·발행으로 넘어간다).
 */
@Repository
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
}
