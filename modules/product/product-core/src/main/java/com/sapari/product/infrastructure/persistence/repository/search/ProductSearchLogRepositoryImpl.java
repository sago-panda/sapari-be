package com.sapari.product.infrastructure.persistence.repository.search;

import com.sapari.product.domain.model.search.ProductSearchLog;
import com.sapari.product.domain.repository.search.ProductSearchLogRepository;
import com.sapari.product.infrastructure.persistence.mapper.search.ProductSearchLogMapper;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 검색 로그 영속 어댑터. append-only 단건 적재이며, 운영의 대용량 경로는 Redis/Kafka 버퍼링 후 bulk COPY가 정석이다(본 save는 테스트·소량용). id는 앱이 주입한 TSID.
 */
@Repository
@RequiredArgsConstructor
public class ProductSearchLogRepositoryImpl implements ProductSearchLogRepository {

    private final ProductSearchLogJpaRepository jpaRepository;
    private final ProductSearchLogMapper mapper;

    @Override
    public ProductSearchLog save(ProductSearchLog log) {
        // 검색 로그는 append-only. id는 앱이 생성한 TSID.
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(log)));
    }

    @Override
    public Optional<ProductSearchLog> findById(Long id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public List<ProductSearchLog> findByKeyword(String keyword) {
        return jpaRepository.findByKeyword(keyword)
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
