package com.sapari.user.infrastructure.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import com.sapari.storage.object.command.ObjectPutCommand;
import com.sapari.storage.object.client.ObjectStorageClient;
import com.sapari.storage.object.exception.ObjectStorageException;
import com.sapari.user.application.dto.ProfileImageStoreCommand;
import com.sapari.user.application.dto.StoredProfileImage;
import com.sapari.user.application.port.ProfileImageStorage;
import com.sapari.user.domain.exception.UserErrorCode;
import com.sapari.user.domain.exception.UserException;

/**
 * 호출자가 검증을 완료한 프로필 이미지 바이트를 공통 object storage에 업로드한다.
 * DB에는 공개 URL이 아니라 저장소 내부 경로인 object key만 남기도록 업로드 결과도 key로 반환한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObjectStorageProfileImageStorage implements ProfileImageStorage {

    private final ObjectStorageClient objectStorageClient;
    private final ProfileImageObjectKeyGenerator keyGenerator;

    /**
     * 호출자가 검증한 이미지 바이트를 저장하고 DB에 저장할 object key를 반환한다.
     * bucket과 저장소 접속 정보는 공통 storage 모듈 설정이 담당한다.
     */
    @Override
    public StoredProfileImage store(ProfileImageStoreCommand command) {
        String key = keyGenerator.generate(command.userId(), command.normalizedExtension());
        try {
            objectStorageClient.put(new ObjectPutCommand(
                    key,
                    command.contentType(),
                    command.content()
            ));
        } catch (ObjectStorageException e) {
            // 공통 저장소의 기술 실패를 user 도메인의 공개 오류 계약으로 변환한다.
            throw new UserException(UserErrorCode.PROFILE_IMAGE_STORAGE_UNAVAILABLE, e);
        }
        return new StoredProfileImage(key, command.contentType(), command.content().length);
    }

    /**
     * 기존 프로필 이미지 object를 best-effort로 삭제한다.
     * 기존 이미지가 없거나 저장소 삭제가 실패해도 사용자 프로필 변경의 주 트랜잭션을 되돌리지 않는다.
     */
    @Override
    public void deleteQuietly(String profileImageKey) {
        deleteQuietly(profileImageKey, "PROFILE_IMAGE_CLEANUP");
    }

    /** 삭제 경로를 고정 식별자로 기록하고 실패가 주 작업이나 다음 사진 정리를 막지 않게 한다. */
    @Override
    public void deleteQuietly(String profileImageKey, String cleanupReason) {
        if (profileImageKey == null || profileImageKey.isBlank()) {
            // 기존 이미지가 없는 사용자는 삭제 보상 작업도 만들지 않는다.
            return;
        }
        try {
            objectStorageClient.delete(profileImageKey);
        } catch (RuntimeException e) {
            // 요청·예외 원문 대신 내부 경로와 대상 키만 남겨 수동 정리를 지원한다.
            log.warn("프로필 이미지 object 삭제 실패: reason={}, key={}, exceptionType={}",
                    cleanupReason,
                    profileImageKey,
                    e.getClass().getSimpleName());
        }
    }
}
