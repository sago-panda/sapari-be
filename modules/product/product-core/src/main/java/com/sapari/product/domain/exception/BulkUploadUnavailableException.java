package com.sapari.product.domain.exception;

/**
 * 대량 등록 한도를 판정할 수 없어(Redis 장애 등) 요청을 받지 않을 때 발생한다. 응답은 503 PRODUCT-014다 — 한도를 확인하지 못한 채 받으면
 * 보호가 사라지므로, 대량 등록은 일시적으로 거절한다(단건 등록은 영향 없음).
 */
public class BulkUploadUnavailableException extends ProductDomainException {

    /**
     * 판정 실패 내용과 원인 예외로 생성한다 (응답은 PRODUCT-014 카탈로그 메시지).
     *
     * @param debugMessage 로그용 실패 내용
     * @param cause        원인 예외(인프라 오류)
     */
    public BulkUploadUnavailableException(String debugMessage, Throwable cause) {
        super(ProductErrorCode.BULK_UPLOAD_UNAVAILABLE, debugMessage, cause);
    }
}
