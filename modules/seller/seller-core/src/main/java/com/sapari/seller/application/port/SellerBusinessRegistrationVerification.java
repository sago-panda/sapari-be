package com.sapari.seller.application.port;

/**
 * 사업자 진위확인 결과를 판매자 가입 가능 여부로 표현한다.
 */
public record SellerBusinessRegistrationVerification(
        boolean registrationAvailable,
        FailureReason failureReason
) {

    public SellerBusinessRegistrationVerification {
        if (registrationAvailable && failureReason != null) {
            throw new IllegalArgumentException("가입 가능한 검증 결과에는 실패 원인이 없어야 합니다.");
        }
        if (!registrationAvailable && failureReason == null) {
            throw new IllegalArgumentException("가입 불가 검증 결과에는 실패 원인이 필요합니다.");
        }
    }

    /**
     * 사업자등록정보가 일치하고 계속사업자라 가입 가능한 결과를 생성한다.
     */
    public static SellerBusinessRegistrationVerification available() {
        return new SellerBusinessRegistrationVerification(true, null);
    }

    /**
     * 정보 불일치, 휴업, 폐업, 미등록처럼 가입할 수 없는 사업자 결과를 생성한다.
     */
    public static SellerBusinessRegistrationVerification invalid() {
        return new SellerBusinessRegistrationVerification(false, FailureReason.INVALID_OR_INACTIVE);
    }

    /**
     * 외부 API 장애나 설정 누락처럼 사업자 진위확인을 수행할 수 없는 결과를 생성한다.
     */
    public static SellerBusinessRegistrationVerification unavailable() {
        return new SellerBusinessRegistrationVerification(false, FailureReason.UNAVAILABLE);
    }

    /**
     * 사업자 진위확인 실패 원인을 서비스 에러로 변환하기 위한 내부 분류다.
     */
    public enum FailureReason {
        INVALID_OR_INACTIVE,
        UNAVAILABLE
    }
}
