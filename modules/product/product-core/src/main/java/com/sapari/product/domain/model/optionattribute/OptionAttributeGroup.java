package com.sapari.product.domain.model.optionattribute;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/**
 * 옵션 속성 그룹 템플릿의 도메인 모델 (애그리거트 루트).
 *
 * <p>색상·사이즈처럼 재사용되는 옵션 속성의 템플릿. {@code isSystem=true}는 관리자 공용,
 * {@code false}는 판매자 커스텀({@code sellerId} 소유)이다. 사전 정의 값 {@link OptionPreset} 목록을 자식으로 포함하며 불변 복사본으로 보관한다.
 */
@Builder(toBuilder = true)
public record OptionAttributeGroup(
        UUID id,
        String name,
        Boolean isSystem,
        UUID sellerId,
        String description,
        List<OptionPreset> presets,
        Instant createdAt,
        Instant updatedAt
) {

    public OptionAttributeGroup {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name은 필수입니다.");
        }
        presets = presets == null ? List.of() : List.copyOf(presets);
    }

    /**
     * 신규 템플릿을 생성한다. name 필수. isSystem 누락 시 시스템(공용, true)으로 간주하고, 커스텀({@code isSystem=false})이면 {@code sellerId}가 필수다.
     */
    public static OptionAttributeGroup create(String name, Boolean isSystem, UUID sellerId,
                                              String description, List<OptionPreset> presets) {
        boolean system = isSystem == null ? Boolean.TRUE : isSystem;
        if (!system && sellerId == null) {
            throw new IllegalArgumentException("커스텀 그룹은 sellerId가 필수입니다.");
        }
        return OptionAttributeGroup.builder()
                .name(name)
                .isSystem(system)
                .sellerId(sellerId)
                .description(description)
                .presets(presets)
                .build();
    }
}
