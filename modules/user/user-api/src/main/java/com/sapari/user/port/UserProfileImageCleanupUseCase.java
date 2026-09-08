package com.sapari.user.port;

/** 내부 회원 영구 삭제 작업에서 사용하는 프로필 이미지 정리 예약 포트다. */
public interface UserProfileImageCleanupUseCase {

    /** 활성 트랜잭션이 커밋된 뒤 사진을 정리한다. 롤백 시에는 실행하지 않는다. */
    void scheduleAfterCommit(String profileImageKey);
}
