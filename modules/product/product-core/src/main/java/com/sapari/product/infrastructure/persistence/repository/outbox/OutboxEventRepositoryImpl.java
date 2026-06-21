package com.sapari.product.infrastructure.persistence.repository.outbox;

import com.sapari.product.domain.model.outbox.OutboxEvent;
import com.sapari.product.domain.repository.outbox.OutboxEventRepository;
import com.sapari.product.infrastructure.persistence.mapper.outbox.OutboxEventMapper;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * Transactional Outbox 영속 어댑터. id가 앱 생성 TSID라 항상 존재 → 기존 행이면 처리 상태만 갱신(createdAt 보존), 없으면 신규 INSERT. 미처리 이벤트 폴링 조회를
 * 제공한다.
 */
@Repository
@RequiredArgsConstructor
public class OutboxEventRepositoryImpl implements OutboxEventRepository {

    private final OutboxEventJpaRepository jpaRepository;
    private final OutboxEventMapper mapper;

    @Override
    public OutboxEvent save(OutboxEvent event) {
        // id는 앱 생성 TSID라 항상 존재. 기존 행이면 처리 상태만 갱신(createdAt 보존), 없으면 신규 INSERT.
        return jpaRepository.findById(event.id())
                .map(existing -> {
                    mapper.updateEntityFromDomain(existing, event);
                    return mapper.toDomain(jpaRepository.save(existing));
                })
                .orElseGet(() -> mapper.toDomain(jpaRepository.save(mapper.toEntity(event))));
    }

    @Override
    public Optional<OutboxEvent> findById(Long id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public List<OutboxEvent> findUnprocessed() {
        return jpaRepository.findByProcessedAtIsNullOrderByCreatedAt()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
