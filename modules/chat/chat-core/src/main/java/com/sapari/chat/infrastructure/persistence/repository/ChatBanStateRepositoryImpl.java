package com.sapari.chat.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.sapari.chat.domain.model.ChatBan;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.domain.repository.ChatBanStateRepository.BanWrite;
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
    public BanWrite extendOrCreate(ChatBan ban) {
        int changed = jpaRepository.upsertExtending(
                ban.userId(), ban.bannedById(), ban.expiresAt(), ban.createdAt());
        if (changed > 0) {
            return new BanWrite(ban, true);
        }
        // 0행 = 이미 더 긴 밴이 있어 아무것도 바뀌지 않았다. 그 밴을 읽어 돌려준다 — 이 호출이 만든
        // 값을 그대로 돌려주면 호출자가 정본에 없는 만료를 기록하고 미러에 싣는다. 왕복이 하나 늘지만
        // 이 경로는 동시 강퇴에서만 밟히고, 그 순간의 정확한 만료를 아는 방법이 다시 읽는 것뿐이다.
        // 아래 orElseThrow는 에러 처리가 아니라 단언이다. 0행이려면 기존 만료가 이 밴보다 길거나 영구라야
        // 하고, 티어 길이가 전부 양수라 기존 만료 > 새 만료 > createdAt = 여기 넘기는 now다. 즉 그 행은
        // 반드시 활성으로 잡힌다. (ChatBan 컴팩트 생성자가 만료 <= 생성시각을 거부해 이중으로 막힌다.)
        // 도달하면 그 전제 중 하나가 깨진 것이므로 방어 코드를 얹지 말고 여기를 다시 읽을 것.
        return jpaRepository.findActive(ban.userId(), ban.createdAt())
                .map(ChatBanStateRepositoryImpl::toDomain)
                .map(existing -> new BanWrite(existing, false))
                .orElseThrow(() -> new IllegalStateException(
                        "도달 불가 — 0행인데 활성 밴이 없다. 티어 길이가 양수라는 전제가 깨졌다 userId="
                                + ban.userId()));
    }

    private static ChatBan toDomain(ChatBanEntity entity) {
        return new ChatBan(entity.getUserId(), entity.getBannedById(),
                entity.getExpiresAt(), entity.getCreatedAt());
    }
}
