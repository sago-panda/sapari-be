package com.sapari.product.domain.repository.optionattribute;

import com.sapari.product.domain.model.optionattribute.OptionAttributeGroup;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 옵션 속성 그룹 템플릿의 영속 포트.
 *
 * <p>{@link #save}는 그룹 + presets를 함께 upsert한다(자식 presets는 교체 저장).
 * {@link #findBySellerId}는 판매자 커스텀 그룹, {@link #findSystemGroups}는 관리자 공용(system) 그룹을 조회한다.
 */
public interface OptionAttributeGroupRepository {

    OptionAttributeGroup save(OptionAttributeGroup optionAttributeGroup);

    Optional<OptionAttributeGroup> findById(UUID id);

    List<OptionAttributeGroup> findBySellerId(UUID sellerId);

    List<OptionAttributeGroup> findSystemGroups();
}
