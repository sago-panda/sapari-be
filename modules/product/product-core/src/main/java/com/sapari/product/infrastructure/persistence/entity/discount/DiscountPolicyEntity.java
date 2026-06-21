package com.sapari.product.infrastructure.persistence.entity.discount;

import com.sapari.product.domain.model.discount.DiscountType;

import com.sapari.storage.db.entity.UuidTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기간·상시 할인 정책. 조합별 승자 1개 선정(write-time resolution).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "discount_policies", schema = "product_schema")
public class DiscountPolicyEntity extends UuidTimeEntity {

    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    private DiscountType discountType;

    // RATE=할인율(%), FIXED_AMOUNT=할인 금액(원)
    private Integer discountValue;

    // 높을수록 우선. 할인액 무관 override. 프로모션=높은 값.
    private Integer priority;

    private Instant startedAt;

    private Instant endedAt;

    private Boolean isActive;

    // ref: users.id (role=ADMIN). 물리 FK 미사용.
    private UUID createdBy;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여 private으로 두고, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 강제한다.
     */
    @Builder
    private DiscountPolicyEntity(String name, String description, DiscountType discountType, Integer discountValue,
                                 Integer priority, Instant startedAt, Instant endedAt, Boolean isActive,
                                 UUID createdBy) {
        this.name = name;
        this.description = description;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.priority = priority;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.isActive = isActive;
        this.createdBy = createdBy;
    }

    /**
     * 정책의 표시용 기본 정보(이름·설명)를 갱신한다.
     */
    public void updateInfo(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /**
     * 할인 방식과 값을 함께 갱신한다.
     *
     * <p>discountValue의 의미가 discountType(RATE=할인율%, FIXED_AMOUNT=할인 금액)에 종속되므로,
     * 둘을 묶어 변경해 방식과 값이 어긋나지 않도록 한다.
     */
    public void updateDiscount(DiscountType discountType, Integer discountValue) {
        this.discountType = discountType;
        this.discountValue = discountValue;
    }

    /**
     * 조합별 승자 선정 시 적용되는 우선순위를 갱신한다(높을수록 우선).
     */
    public void updatePriority(Integer priority) {
        this.priority = priority;
    }

    /**
     * 정책 적용 기간(시작·종료 시각)을 함께 갱신한다.
     */
    public void updatePeriod(Instant startedAt, Instant endedAt) {
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    /**
     * 정책 활성화 여부를 갱신한다.
     */
    public void updateActive(Boolean isActive) {
        this.isActive = isActive;
    }
}
