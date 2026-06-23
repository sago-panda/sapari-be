package com.sapari.product.domain.exception;

import com.sapari.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 상품 도메인 에러 코드 카탈로그. 코드 형식은 {@code PRODUCT-0xx}이며 HTTP status·클라이언트 메시지를 함께 보관한다.
 *
 * <p>{@code GlobalExceptionHandler}는 도메인을 몰라도 {@link ErrorCode} 한 인터페이스로 일관된 응답을 만든다.
 * 메시지는 클라이언트 노출용이므로 내부 식별자(id 등)를 담지 않는다(디버그 메시지는 예외 생성자로 따로 전달).
 */
@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {

    PRODUCT_NOT_FOUND(404, "PRODUCT-001", "상품을 찾을 수 없습니다."),
    INVALID_PRODUCT_STATE(400, "PRODUCT-002", "요청한 상태로 변경할 수 없습니다."),
    PRODUCT_ACCESS_DENIED(403, "PRODUCT-003", "해당 상품에 대한 권한이 없습니다."),
    INVALID_PRODUCT_OPTION(400, "PRODUCT-004", "옵션 구성이 올바르지 않습니다."),
    CATEGORY_NOT_FOUND(404, "PRODUCT-005", "카테고리를 찾을 수 없습니다."),
    INVALID_PRODUCT_IMAGE(400, "PRODUCT-006", "이미지 구성이 올바르지 않습니다."),
    INVALID_PRODUCT_TAG(400, "PRODUCT-007", "태그 형식이 올바르지 않습니다."),
    COMBINATION_NOT_FOUND(404, "PRODUCT-008", "옵션 조합을 찾을 수 없습니다."),
    TOO_MANY_COMBINATIONS(400, "PRODUCT-009", "옵션 조합 수가 허용 한도를 초과했습니다."),
    INTERNAL_PRODUCT_ERROR(500, "PRODUCT-010", "상품 처리 중 오류가 발생했습니다.");

    private final int status;
    private final String code;
    private final String message;
}
