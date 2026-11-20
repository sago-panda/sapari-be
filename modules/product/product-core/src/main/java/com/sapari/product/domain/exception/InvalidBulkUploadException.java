package com.sapari.product.domain.exception;

/**
 * 대량 등록 요청 자체를 처리할 수 없을 때 발생한다 (예: 엑셀이 아니거나 필수 시트·헤더가 없음, 상품 수 한도 초과, 상품 코드 중복).
 *
 * <p>상품별 내용 오류는 이 예외가 아니라 대량 등록 결과의 오류 목록으로 돌려준다 — 이 예외는 파일 전체를 읽을 수 없는 경우에만 쓴다.
 */
public class InvalidBulkUploadException extends ProductDomainException {

    /**
     * 위반 내용을 디버그 메시지로 담아 생성한다 (응답은 PRODUCT-012 카탈로그 메시지).
     */
    public InvalidBulkUploadException(String debugMessage) {
        super(ProductErrorCode.INVALID_BULK_UPLOAD, debugMessage);
    }

    /**
     * 위반 내용과 원인 예외로 생성한다 (파일 파싱 실패 등).
     */
    public InvalidBulkUploadException(String debugMessage, Throwable cause) {
        super(ProductErrorCode.INVALID_BULK_UPLOAD, debugMessage, cause);
    }
}
