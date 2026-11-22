package com.sapari.user.exception;

/**
 * 다른 도메인이 user 의 실패를 골라 자기 오류로 바꿀 때 대조하는 공개 오류 코드다.
 * 값의 정본은 이 클래스이고 user-core 의 UserErrorCode 가 같은 상수를 쓴다 — 문자열을 두 곳에 적지 않는다.
 */
public final class UserPublicErrorCodes {

    /** 프로필 이미지 저장소 장애(503). */
    public static final String PROFILE_IMAGE_STORAGE_UNAVAILABLE = "USER-118";

    private UserPublicErrorCodes() {
    }
}
