package com.sapari.user.port;

import java.util.UUID;

/** 내부 회원 영구 삭제 작업에서 사용하는 프로필 이미지 정리 예약 포트다. */
public interface UserProfileImageCleanupUseCase {

    /**
     * 활성 트랜잭션이 커밋된 뒤 사진을 정리한다. 롤백 시에는 실행하지 않는다.
     * key 가 그 회원의 것이 아니면 삭제하지 않는다 — 포트가 user-api 라 다른 도메인도 부를 수 있어서다.
     */
    void scheduleAfterCommit(UUID userId, String profileImageKey);
}
