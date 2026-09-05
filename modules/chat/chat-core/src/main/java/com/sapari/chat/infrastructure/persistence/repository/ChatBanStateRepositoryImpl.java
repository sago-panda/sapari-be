package com.sapari.chat.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;

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
        // 하나만 가져온다 — 쓰임은 "있는가"와 "얼마나 오래 가는가"뿐이라, 정렬 첫 행이면 충분하다.
        List<ChatBanEntity> found = jpaRepository.findActive(userId, now, Limit.of(1));
        return found.stream().findFirst().map(ChatBanStateRepositoryImpl::toDomain);
    }

    @Override
    public void append(ChatBan ban) {
        jpaRepository.insert(ban.userId(), ban.bannedById(), ban.expiresAt(), ban.createdAt());
    }

    private static ChatBan toDomain(ChatBanEntity entity) {
        return new ChatBan(entity.getUserId(), entity.getBannedById(),
                entity.getExpiresAt(), entity.getCreatedAt());
    }
}
