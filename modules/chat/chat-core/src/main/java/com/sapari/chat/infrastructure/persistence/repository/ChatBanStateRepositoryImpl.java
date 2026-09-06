package com.sapari.chat.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.sapari.chat.domain.model.ChatBan;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.infrastructure.persistence.entity.ChatBanEntity;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatBanStateRepository} 구현.
 *
 * <p>스테레오타입을 달지 않는다 — 블로킹 JPA라 리액티브 앱에는 그 의존이 없다. 이 스택을 소유한 앱이
 * {@code @Bean}으로 등록한다.
 */
@RequiredArgsConstructor
public class ChatBanStateRepositoryImpl implements ChatBanStateRepository {

    private final ChatBanJpaRepository jpaRepository;

    @Override
    public Optional<ChatBan> findActive(UUID userId, Instant now) {
        return jpaRepository.findActive(userId, now).map(ChatBanStateRepositoryImpl::toDomain);
    }

    @Override
    public ChatBan extendOrCreate(ChatBan ban) {
        int changed = jpaRepository.upsertExtending(
                ban.userId(), ban.bannedById(), ban.expiresAt(), ban.createdAt());
        if (changed > 0) {
            return ban;
        }
        // 0행 = 이미 더 긴 밴이 있어 아무것도 바뀌지 않았다. 그 밴을 읽어 돌려준다 — 이 호출이 만든
        // 값을 그대로 돌려주면 호출자가 정본에 없는 만료를 기록하고 미러에 싣는다. 왕복이 하나 늘지만
        // 이 경로는 동시 강퇴에서만 밟히고, 그 순간의 정확한 만료를 아는 방법이 다시 읽는 것뿐이다.
        return jpaRepository.findActive(ban.userId(), ban.createdAt())
                .map(ChatBanStateRepositoryImpl::toDomain)
                .orElseThrow(() -> new IllegalStateException(
                        "밴을 늘리지도 만들지도 못했는데 남아 있는 밴도 없다 — userId=" + ban.userId()));
    }

    private static ChatBan toDomain(ChatBanEntity entity) {
        return new ChatBan(entity.getUserId(), entity.getBannedById(),
                entity.getExpiresAt(), entity.getCreatedAt());
    }
}
