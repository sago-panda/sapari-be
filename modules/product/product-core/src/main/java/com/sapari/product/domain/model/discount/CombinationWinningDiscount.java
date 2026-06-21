package com.sapari.product.domain.model.discount;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * 조합당 현재 승자 할인 1개(파생 캐시) 애그리거트. PK = combinationId (조합과 1:1).
 */
@Builder(toBuilder = true)
public record CombinationWinningDiscount(
        UUID combinationId,
        UUID policyId,
        Integer discountAmount,
        Integer finalPrice,
        Instant resolvedAt
) {

    public CombinationWinningDiscount {
        if (combinationId == null) {
            throw new IllegalArgumentException("combinationId는 필수입니다.");
        }
        if (policyId == null) {
            throw new IllegalArgumentException("policyId는 필수입니다.");
        }
        if (discountAmount == null || discountAmount < 0) {
            throw new IllegalArgumentException("discountAmount는 0 이상이어야 합니다.");
        }
        if (finalPrice == null || finalPrice < 0) {
            throw new IllegalArgumentException("finalPrice는 0 이상이어야 합니다.");
        }
    }

    /**
     * 조합별로 해석된 승자 할인을 캐시 row로 생성한다.
     *
     * <p>여러 정책 경합 끝에 한 조합에 적용될 최종 할인(이긴 정책)을 미리 계산해 1:1로 보관하는 파생 캐시다.
     * {@code discountAmount}/{@code finalPrice}는 모두 0 이상이어야 하며, 음수 값이 들어가 가격이 깨지는 것을 막는다. row가 없으면 그 조합엔 할인이 없다는 의미이므로,
     * 0원 할인이라도 의미가 있어 별도로 검증한다.
     */
    public static CombinationWinningDiscount create(
            UUID combinationId, UUID policyId, Integer discountAmount, Integer finalPrice, Instant now) {
        return builder()
                .combinationId(combinationId)
                .policyId(policyId)
                .discountAmount(discountAmount)
                .finalPrice(finalPrice)
                .resolvedAt(now)
                .build();
    }
}
