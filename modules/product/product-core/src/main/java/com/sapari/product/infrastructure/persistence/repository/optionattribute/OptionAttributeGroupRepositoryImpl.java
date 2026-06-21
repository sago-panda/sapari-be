package com.sapari.product.infrastructure.persistence.repository.optionattribute;

import com.sapari.product.infrastructure.persistence.entity.optionattribute.OptionAttributeGroupEntity;
import com.sapari.product.infrastructure.persistence.entity.optionattribute.OptionAttributeGroupPresetEntity;

import com.sapari.product.domain.model.optionattribute.OptionAttributeGroup;
import com.sapari.product.domain.model.optionattribute.OptionPreset;
import com.sapari.product.domain.repository.optionattribute.OptionAttributeGroupRepository;
import com.sapari.product.infrastructure.persistence.mapper.optionattribute.OptionAttributeGroupMapper;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * {@link OptionAttributeGroupRepository} 영속 어댑터.
 *
 * <p>{@code save}는 그룹을 upsert한 뒤 presets를 <b>교체 저장</b>한다(갱신 시 기존 preset 전체 삭제 후
 * 재삽입). 조회는 {@code assemble}로 그룹 + 정렬된 presets를 합쳐 애그리거트를 복원한다.
 */
@Repository
@RequiredArgsConstructor
public class OptionAttributeGroupRepositoryImpl implements OptionAttributeGroupRepository {

    private final OptionAttributeGroupJpaRepository groupJpaRepository;
    private final OptionAttributeGroupPresetJpaRepository presetJpaRepository;
    private final OptionAttributeGroupMapper mapper;

    @Override
    public OptionAttributeGroup save(OptionAttributeGroup domain) {
        UUID groupId;
        if (domain.id() == null) {
            var saved = groupJpaRepository.save(mapper.toEntity(domain));
            groupId = saved.getId();
        } else {
            var existing = groupJpaRepository.findById(domain.id())
                    .orElseThrow(() -> new EntityNotFoundException("옵션 속성 그룹을 찾을 수 없습니다: " + domain.id()));
            mapper.updateEntityFromDomain(existing, domain);
            groupJpaRepository.save(existing);
            presetJpaRepository.deleteByAttributeGroupId(domain.id());
            groupId = domain.id();
        }
        persistPresets(groupId, domain.presets());
        return findById(groupId).orElseThrow();
    }

    @Override
    public Optional<OptionAttributeGroup> findById(UUID id) {
        return groupJpaRepository.findById(id)
                .map(this::assemble);
    }

    @Override
    public List<OptionAttributeGroup> findBySellerId(UUID sellerId) {
        return groupJpaRepository.findBySellerId(sellerId)
                .stream()
                .map(this::assemble)
                .toList();
    }

    @Override
    public List<OptionAttributeGroup> findSystemGroups() {
        return groupJpaRepository.findByIsSystemTrue()
                .stream()
                .map(this::assemble)
                .toList();
    }

    private void persistPresets(UUID groupId, List<OptionPreset> presets) {
        for (OptionPreset preset : presets) {
            presetJpaRepository.save(OptionAttributeGroupPresetEntity.builder()
                    .attributeGroupId(groupId)
                    .value(preset.value())
                    .sortOrder(preset.sortOrder())
                    .build());
        }
    }

    private OptionAttributeGroup assemble(
            OptionAttributeGroupEntity entity) {
        List<OptionPreset> presets = presetJpaRepository
                .findByAttributeGroupIdOrderBySortOrderAsc(entity.getId())
                .stream()
                .map(mapper::toPreset)
                .toList();
        return mapper.toDomain(entity)
                .toBuilder()
                .presets(presets)
                .build();
    }
}
