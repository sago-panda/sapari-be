package com.sapari.product.infrastructure.persistence.entity.product.option;

import com.sapari.storage.db.entity.UuidTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 옵션 타입별 선택 값 (빨강, M 등). 프리셋 또는 직접 입력. Product 애그리거트 내부 (저작 클러스터).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_option_values", schema = "product_schema")
public class ProductOptionValueEntity extends UuidTimeEntity {

    // ref: product_option_types.id. 물리 FK 미사용.
    private UUID optionTypeId;

    // ref: option_attribute_group_presets.id (NULL=직접 입력). 물리 FK 미사용.
    private UUID attributePresetId;

    private String value;

    // 확장 메타데이터 jsonb. 예: {"hex":"#FF0000"}
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    private Integer priceDelta;

    private Short sortOrder;

    private Instant deletedAt;

    /**
     * 빌더 전용 생성자. JPA용 protected 기본 생성자와 구분해 private으로 두고 빌더로만 생성/재구성한다.
     */
    @Builder
    private ProductOptionValueEntity(UUID optionTypeId, UUID attributePresetId, String value, String metadata,
                                     Integer priceDelta, Short sortOrder, Instant deletedAt) {
        this.optionTypeId = optionTypeId;
        this.attributePresetId = attributePresetId;
        this.value = value;
        this.metadata = metadata;
        this.priceDelta = priceDelta;
        this.sortOrder = sortOrder;
        this.deletedAt = deletedAt;
    }

    /**
     * 옵션 값 편집 정보(표시값·메타데이터·가격가감·노출순서)를 일괄 갱신한다.
     *
     * <p>판매자 옵션 편집 화면에서 한 값을 수정할 때 함께 바뀌는 필드들이라 묶어 갱신한다(삭제 마킹은 별도).
     */
    public void updateValue(String value, String metadata, Integer priceDelta, Short sortOrder) {
        this.value = value;
        this.metadata = metadata;
        this.priceDelta = priceDelta;
        this.sortOrder = sortOrder;
    }

    /**
     * 소프트딜리트 시각을 설정/해제한다(물리 제거가 아닌 deletedAt 마킹).
     */
    public void updateDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
