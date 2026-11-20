package com.sapari.product.infrastructure.persistence.entity.optionattribute;

import com.sapari.storage.db.entity.UuidTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 옵션 속성 그룹 템플릿의 영속 엔티티 (table {@code option_attribute_groups}).
 *
 * <p>{@code is_system=true}는 관리자 공용, {@code false}는 판매자 커스텀({@code seller_id} 소유)이다.
 * 사전 정의 값(presets)은 {@code OptionAttributeGroupPresetEntity}로 분리된 자식이다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "option_attribute_groups", schema = "product_schema")
public class OptionAttributeGroupEntity extends UuidTimeEntity {

    private String name;

    private Boolean isSystem;

    // 커스텀 생성 판매자 (시스템은 NULL). 물리 FK 미사용.
    private UUID sellerId;

    private String description;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 한다.
     */
    @Builder
    public OptionAttributeGroupEntity(String name, Boolean isSystem, UUID sellerId, String description) {
        this.name = name;
        this.isSystem = isSystem;
        this.sellerId = sellerId;
        this.description = description;
    }

    /**
     * 그룹 이름을 갱신한다.
     */
    public void updateName(String name) {
        this.name = name;
    }

    /**
     * 시스템 공용 그룹 여부를 갱신한다(true=관리자 공용, false=판매자 커스텀).
     */
    public void updateIsSystem(Boolean isSystem) {
        this.isSystem = isSystem;
    }

    /**
     * 커스텀 그룹의 소유 판매자를 갱신한다(시스템 그룹은 NULL).
     */
    public void updateSellerId(UUID sellerId) {
        this.sellerId = sellerId;
    }

    /**
     * 그룹 설명을 갱신한다.
     */
    public void updateDescription(String description) {
        this.description = description;
    }
}
