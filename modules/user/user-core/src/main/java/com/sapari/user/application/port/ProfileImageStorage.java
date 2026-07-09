package com.sapari.user.application.port;

import com.sapari.user.application.dto.ProfileImageStoreCommand;
import com.sapari.user.application.dto.StoredProfileImage;

/**
 * user-core가 S3/MinIO/S3Mock 구현에 직접 의존하지 않기 위한 프로필 이미지 저장 역할이다.
 * 구현체는 검증된 이미지 바이트를 저장하고, user 도메인에는 외부 URL이 아닌 내부 object key만 돌려준다.
 */
public interface ProfileImageStorage {

    /**
     * 검증이 완료된 프로필 이미지를 외부 저장소에 저장한다.
     * 저장 실패는 호출자에게 전파해 DB에 저장할 object key와 실제 object 존재 여부가 어긋나지 않게 한다.
     */
    StoredProfileImage store(ProfileImageStoreCommand command);

    /**
     * 프로필 교체/삭제의 주 트랜잭션이 저장소 정리 실패 때문에 실패하지 않도록 best-effort로 삭제한다.
     * 고아 object는 운영 정리 대상으로 남길 수 있으므로 호출자는 삭제 실패를 사용자 요청 실패로 바꾸지 않는다.
     */
    void deleteQuietly(String profileImageKey);
}
