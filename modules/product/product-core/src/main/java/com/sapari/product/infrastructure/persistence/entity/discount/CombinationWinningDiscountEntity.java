package com.sapari.product.infrastructure.persistence.entity.discount;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 조합당 현재 승자 할인 1개 저장(파생 캐시). 행 없음=할인 없음. PK = combination_id (조합과 1:1). 베이스 미상속 — id가 생성값이 아니라 combination_id이고
 * created_at 없음.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "combination_winning_discounts", schema = "product_schema")
public class CombinationWinningDiscountEntity {

    // PK이자 ref: product_option_combinations.id (1:1). 앱이 주입.
    @Id
    private UUID combinationId;

    // ref: discount_policies.id. 현재 승자 정책. 물리 FK 미사용.
    private UUID policyId;

    private Integer discountAmount;

    // 할인 후 최종 가격 = price - discount_amount
    private Integer finalPrice;

    private Instant resolvedAt;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여 private으로 두고, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 강제한다.
     */
    @Builder
    private CombinationWinningDiscountEntity(UUID combinationId, UUID policyId, Integer discountAmount,
                                             Integer finalPrice, Instant resolvedAt) {
        this.combinationId = combinationId;
        this.policyId = policyId;
        this.discountAmount = discountAmount;
        this.finalPrice = finalPrice;
        this.resolvedAt = resolvedAt;
    }

    /**
     * 현재 승자 할인을 재산정 결과로 교체한다.
     *
     * <p>조합당 승자 1개만 캐시하는 1:1 파생 행이므로, 새 정책·할인액·최종가·산정 시각을
     * 한 번에 묶어 갱신해 캐시가 부분적으로 어긋나지 않도록 한다.
     */
    public void updateWinner(UUID policyId, Integer discountAmount, Integer finalPrice, Instant resolvedAt) {
        this.policyId = policyId;
        this.discountAmount = discountAmount;
        this.finalPrice = finalPrice;
        this.resolvedAt = resolvedAt;
    }
}
