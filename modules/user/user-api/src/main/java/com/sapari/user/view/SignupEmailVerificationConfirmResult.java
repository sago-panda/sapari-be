package com.sapari.user.view;

/**
 * 회원가입 이메일 인증번호 확인 결과다.
 * emailVerified는 화면 상태 표시용이고 최종 가입 허용은 verified 상태 소비로 결정된다.
 */
public record SignupEmailVerificationConfirmResult(boolean emailVerified, long verifiedExpiresInSeconds) {
}
