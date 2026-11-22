package com.sapari.customer.domain.repository;

/**
 * 소셜 회원가입 SID별 실제 처리 시도와 동시 실행을 제어한다.
 */
public interface SocialSignupAttemptRepository {

    /**
     * 가입 처리권을 시도한다.
     * 동시 요청은 quota를 차감하지 않으며, 실제 처리권을 얻은 요청만 시도 횟수를 증가시킨다.
     */
    AcquireResult tryAcquire(String signupSid);

    /**
     * 현재 lock의 lease token이 호출자의 token과 일치할 때만 처리권을 해제한다.
     */
    void release(String signupSid, String leaseToken);

    enum AcquireStatus {
        ACQUIRED,
        ALREADY_PROCESSING,
        RATE_LIMIT_EXCEEDED
    }

    record AcquireResult(AcquireStatus status, String leaseToken) {

        public AcquireResult {
            if (status == null) {
                throw new IllegalArgumentException("status는 필수입니다.");
            }
            if (status == AcquireStatus.ACQUIRED && (leaseToken == null || leaseToken.isBlank())) {
                throw new IllegalArgumentException("획득 결과에는 lease token이 필요합니다.");
            }
            if (status != AcquireStatus.ACQUIRED && leaseToken != null) {
                throw new IllegalArgumentException("거절 결과에는 lease token을 포함할 수 없습니다.");
            }
        }

        public static AcquireResult acquired(String leaseToken) {
            return new AcquireResult(AcquireStatus.ACQUIRED, leaseToken);
        }

        public static AcquireResult alreadyProcessing() {
            return new AcquireResult(AcquireStatus.ALREADY_PROCESSING, null);
        }

        public static AcquireResult rateLimitExceeded() {
            return new AcquireResult(AcquireStatus.RATE_LIMIT_EXCEEDED, null);
        }
    }
}
