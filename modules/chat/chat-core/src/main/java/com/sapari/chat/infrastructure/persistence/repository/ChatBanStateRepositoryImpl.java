package com.sapari.chat.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.sapari.chat.domain.model.ChatBan;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.domain.repository.ChatBanStateRepository.BanWrite;
import com.sapari.chat.domain.repository.ChatBanStateRepository.Released;
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

    /**
     * 대기 상한을 먼저 걸고 잠근다.
     *
     * <p>두 문장인 것이 포트로 새지 않는다 — 포트는 "이 사용자의 밴 판단을 직렬화한다" 하나이고,
     * 그걸 어떻게 유계로 만드는지는 이 스택의 몫이다. 상한을 거는 것이 잠금보다 먼저여야 한다는
     * 순서도 여기서만 지켜지면 된다.
     *
     * <p>⚠️ <b>한 문장으로 합치지 말 것.</b> 포트가 약속한 "경계 없이 부르면 시끄럽게 죽는다"가
     * 이 순서에 걸려 있다 — 잠금 자체는 SELECT라 경계가 없어도 조용히 지나가는데, 앞 문장이
     * {@code @Modifying}이라 그쪽이 먼저
     * {@code "No active transaction for update or delete query"}로 죽는다(실측). 합치면 그 안전망이
     * 사라지고 포트의 약속이 거짓이 된다.
     */
    @Override
    public void lockForBanDecision(UUID userId) {
        jpaRepository.boundLockWait();
        jpaRepository.lockForBanDecision(userId);
    }

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

    /**
     * 읽기 → 삭제 → 기준선이 <b>한 트랜잭션</b>이어야 한다 — 호출자가 경계를 연다.
     *
     * <p><b>순서가 하중을 받는 이유는 잔여 상태가 아니다.</b> 한때 여기에 "기준선을 먼저 넣고
     * 삭제가 실패하면 추적이 더 어렵다" 고 적혀 있었는데 <b>공허한 근거</b>였다 — 한 트랜잭션이라
     * READ COMMITTED 독자에게 중간 상태가 보이지 않으므로 그 차이는 <b>관측 불가능</b>하다.
     *
     * <p>순서가 load-bearing 인 것은 <b>자원 순서 선언</b>의 일부이기 때문이다. 이 경로는
     * {@code 어드바이저리 → chat_ban → chat_ban_release} 를 잡고 강퇴는
     * {@code 어드바이저리 → chat_kick_log → chat_ban} 을 잡는다 — 어드바이저리가 양쪽 맨 앞이라
     * 전순서가 유지되고 사이클이 없다({@code ChatBanReleaseRecorder.release} javadoc).
     * 여기서 순서를 뒤집으면 그 선언을 다시 세워야 한다.
     *
     * <p>읽기가 맨 앞인 것은 별 이유다 — 지우고 나면 무엇을 지웠는지 알 수 없고, 그 내용은
     * 감사 행 말고는 어디에도 남지 않는다. 수동 밴을 넣는 앱 경로가 0건이라 되돌릴 수도 없다.
     */
    @Override
    public Released release(UUID userId, UUID releasedById, Instant now) {
        // 지우기 전에 읽는다 — 정본 행이 사라지면 그 내용은 어디에도 남지 않고, 수동 밴을 넣는
        // 경로가 0건이라 되돌릴 수도 없다. 만료 여부를 묻지 않는 쪽으로 읽는 이유는 해제가
        // 만료된 행도 지우기 때문이다(다음 승격의 ON CONFLICT 대상이 되어 발급자가 덮인다).
        Released snapshot = jpaRepository.findAnyByUserId(userId)
                .map(e -> new Released(true, e.getExpiresAt(), e.getBannedById()))
                .orElseGet(() -> new Released(false, null, null));

        jpaRepository.deleteByUserId(userId);
        jpaRepository.appendRelease(userId, releasedById, now,
                snapshot.banExisted(), snapshot.expiresAt(), snapshot.bannedById());
        return snapshot;
    }

    private static ChatBan toDomain(ChatBanEntity entity) {
        return new ChatBan(entity.getUserId(), entity.getBannedById(),
                entity.getExpiresAt(), entity.getCreatedAt());
    }
}
