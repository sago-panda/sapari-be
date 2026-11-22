package com.sapari.user.application.service;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.user.application.dto.ProfileImageChangeResult;
import com.sapari.user.application.dto.ProfileImageRemoveResult;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.domain.exception.UserException;
import com.sapari.user.domain.exception.UserErrorCode;

/**
 * 프로필 이미지 object key의 DB 변경만 짧은 트랜잭션으로 처리한다.
 * S3-compatible 저장소 업로드·삭제는 rollback 대상이 아니므로 호출자가 트랜잭션 밖에서 보상 흐름을 조율한다.
 */
@Service
@RequiredArgsConstructor
public class ProfileImageMutationProcessor {

    private final UserRepository userRepository;

    /**
     * 새 프로필 이미지 key를 저장하고, 커밋 성공 후 삭제할 기존 key를 함께 반환한다.
     */
    @Transactional
    public ProfileImageChangeResult replaceProfileImageKey(UUID userId, String newProfileImageKey) {
        User user = findActiveUserForUpdate(userId);

        String oldProfileImageKey = user.profileImageKey();
        User savedUser = userRepository.save(user.updateProfileImageKey(newProfileImageKey));

        return new ProfileImageChangeResult(savedUser, oldProfileImageKey);
    }

    /**
     * DB의 프로필 이미지 key를 비우고, 커밋 성공 후 삭제할 기존 key를 함께 반환한다.
     */
    @Transactional
    public ProfileImageRemoveResult removeProfileImageKey(UUID userId) {
        User user = findActiveUserForUpdate(userId);

        String oldProfileImageKey = user.profileImageKey();
        User savedUser = userRepository.save(user.removeProfileImage());

        return new ProfileImageRemoveResult(savedUser, oldProfileImageKey);
    }

    /** 인증 후 탈퇴·정지와 경합할 수 있으므로 잠금 획득 후 활성 상태를 다시 검사한다. */
    private User findActiveUserForUpdate(UUID userId) {
        User user;
        try {
            user = userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new IllegalStateException("user not found: " + userId));
        } catch (PessimisticLockingFailureException e) {
            // 저장소 보상은 호출자가 수행하고 클라이언트에는 재시도 가능한 충돌임을 알린다.
            throw new UserException(UserErrorCode.USER_MUTATION_BUSY, e);
        }
        if (!user.isActive()) {
            throw new UserException(UserErrorCode.USER_NOT_ACTIVE);
        }
        return user;
    }
}
