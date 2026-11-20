package com.sapari.product.infrastructure.persistence.entity.optionattribute;

import com.sapari.storage.db.entity.BaseUuidEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 옵션 속성 그룹의 사전 정의 값 영속 엔티티 (table {@code option_attribute_group_presets}). {@code attributeGroupId}로 소속 그룹을 참조(물리 FK
 * 미사용)하며, 그룹 저장 시 교체 저장된다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "option_attribute_group_presets", schema = "product_schema")
public class OptionAttributeGroupPresetEntity extends BaseUuidEntity {

    // ref: option_attribute_groups.id. 물리 FK 미사용.
    private UUID attributeGroupId;

    private String value;

    private Integer sortOrder;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 한다.
     */
    @Builder
    public OptionAttributeGroupPresetEntity(UUID attributeGroupId, String value, Integer sortOrder) {
        this.attributeGroupId = attributeGroupId;
        this.value = value;
        this.sortOrder = sortOrder;
    }
}
