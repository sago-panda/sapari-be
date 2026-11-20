package com.sapari.user.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.sapari.user.application.port.ProfileImageStorage;
import com.sapari.user.port.UserProfileImageCleanupUseCase;

/**
 * 회원 DB 삭제가 확정된 뒤 프로필 이미지를 정리하는 서비스다.
 * 커밋 뒤 삭제가 실패하면 재시도하지 않는다 — 그 손실은 modules/user/AGENTS.md 에 적어 두었다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileImageCleanupService implements UserProfileImageCleanupUseCase {
    private final ProfileImageStorage profileImageStorage;

    /** 호출자 트랜잭션의 커밋 이후에만 스토리지 삭제를 예약한다. */
    @Override
    public void scheduleAfterCommit(UUID userId, String profileImageKey) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("active transaction required for profile image cleanup");
        }
        if (profileImageKey == null || profileImageKey.isBlank()) {
            return;
        }
        if (!profileImageStorage.isOwnedBy(userId, profileImageKey)) {
            // 되돌릴 수 없는 삭제라 남의 경로는 지우지 않는다. 회원 삭제 자체는 막지 않는다(사진만 남는다).
            log.warn("프로필 이미지 정리 건너뜀: 소유자가 아닌 key. userId={}, key={}", userId, profileImageKey);
            return;
        }
        // 청크 롤백·재시도에서는 실행하지 않고 해당 트랜잭션의 실제 커밋 이후에만 삭제한다.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** DB 커밋 이후이므로 삭제 실패는 로그로 남기고 회원 삭제 결과를 뒤집지 않는다. */
            @Override
            public void afterCommit() {
                profileImageStorage.deleteQuietly(profileImageKey, "USER_HARD_DELETE");
            }
        });
    }
}
