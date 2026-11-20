package com.sapari.product.domain.exception;

/**
 * 판매자의 대량 등록 요청이 한도(동시 처리 1건·기간당 건수)를 넘었을 때 발생한다. 응답은 429 PRODUCT-013이며, 다시 시도할 때까지 기다릴
 * 시간(초)을 함께 들고 다녀 웹 레이어가 {@code Retry-After} 헤더로 내려준다.
 */
public class BulkUploadRateLimitedException extends ProductDomainException {

    private final long retryAfterSeconds;

    /**
     * 초과 사유를 디버그 메시지로 담아 생성한다 (응답은 PRODUCT-013 카탈로그 메시지).
     *
     * @param retryAfterSeconds 다시 시도하기까지 기다릴 시간(초, 1 이상)
     * @param debugMessage      로그용 초과 사유
     */
    public BulkUploadRateLimitedException(long retryAfterSeconds, String debugMessage) {
        super(ProductErrorCode.BULK_UPLOAD_RATE_LIMITED, debugMessage);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 다시 시도하기까지 기다릴 시간(초)을 돌려준다.
     *
     * @return 대기 시간(초)
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
