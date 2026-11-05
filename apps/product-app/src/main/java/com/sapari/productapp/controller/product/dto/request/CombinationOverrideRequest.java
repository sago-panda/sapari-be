package com.sapari.productapp.controller.product.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.sapari.product.command.CombinationOverrideCommand;

/**
 * 조합별 오버라이드 요청 DTO. 서버가 자동 생성한 조합 하나를 옵션값 ref로 골라 개별 값을 지정한다. null 필드는 기본값을 유지한다.
 *
 * <p>완전한 조합(타입마다 1개)·제외 조합 여부·SKU 중복 같은 구조 규칙은 요청 전체를 봐야 하므로 서비스(PRODUCT-004)가 검증한다.
 *
 * @param valueRefs     대상 조합의 옵션값 ref 목록(옵션 타입마다 1개, 옵션 타입 수 상한과 같은 최대 10개)
 * @param price         판매가(원), null이면 서버 계산값
 * @param originalPrice 정가(원), null이면 미설정
 * @param stock         재고, null이면 기본 재고
 * @param sku           SKU 코드(상품 내 유일, DDL varchar(100)), null이면 미설정
 */
public record CombinationOverrideRequest(
        @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 100) String> valueRefs,
        @PositiveOrZero Integer price,
        @PositiveOrZero Integer originalPrice,
        @PositiveOrZero Integer stock,
        @Size(max = 100) String sku
) {

    /**
     * 요청 목록을 커맨드 목록으로 변환한다. null이면 오버라이드 없음이므로 빈 목록.
     *
     * @param requests 조합 오버라이드 요청들
     * @return 조합 오버라이드 커맨드 목록(비어 있을 수 있음)
     */
    static List<CombinationOverrideCommand> toCommands(List<CombinationOverrideRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        return requests.stream().map(CombinationOverrideRequest::toCommand).toList();
    }

    /**
     * 도메인 비의존 커맨드로 변환한다.
     *
     * @return 조합 오버라이드 커맨드
     */
    public CombinationOverrideCommand toCommand() {
        return new CombinationOverrideCommand(valueRefs, price, originalPrice, stock, sku);
    }
}
