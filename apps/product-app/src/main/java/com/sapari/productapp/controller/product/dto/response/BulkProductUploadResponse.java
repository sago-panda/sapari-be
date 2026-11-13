package com.sapari.productapp.controller.product.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * 상품 대량 등록(엑셀 업로드) 결과.
 *
 * <p>{@code status}는 {@code REJECTED}(검증 오류, 저장 없음) · {@code COMPLETED}(전부 저장) · {@code STOPPED}(저장 중 한 상품 실패로 중단 —
 * {@code created}는 이미 저장된 상품)다. 검증 오류도 처리 결과이므로 200 본문으로 돌려준다(행 단위 목록을 담을 수 있는 유일한 자리).
 *
 * @param status          처리 결과
 * @param created         저장된 상품들(파일 순서)
 * @param errors          오류들(시트 → 행 → 열 순). 최대 {@code MAX_ERRORS}건
 * @param totalErrorCount 전체 오류 수 — {@code errors}가 잘렸는지 판단용
 */
public record BulkProductUploadResponse(
        String status,
        List<Created> created,
        List<UploadError> errors,
        int totalErrorCount
) {

    /** 응답에 담는 오류 상한 (행 수만큼 오류가 나는 파일의 응답 크기 제한). */
    public static final int MAX_ERRORS = 500;

    /**
     * 저장된 상품 한 건.
     *
     * @param productCode 파일의 상품코드
     * @param productId   저장된 상품 id
     */
    public record Created(String productCode, UUID productId) {
    }

    /**
     * 오류 한 건. 셀을 특정할 수 있으면 열까지, 상품 단위 오류면 상품 행만 가리킨다.
     *
     * @param sheet       시트 이름(예: "2.옵션")
     * @param row         행 번호(1-based)
     * @param column      열 문자(예: "D"), 상품 단위 오류면 null
     * @param productCode 상품코드, 특정할 수 없으면 null
     * @param code        에러 코드(COMMON-001 입력 제약 · PRODUCT-0xx 도메인 규칙·파일 형식)
     * @param message     위반 내용
     */
    public record UploadError(String sheet, int row, String column, String productCode, String code, String message) {
    }
}
