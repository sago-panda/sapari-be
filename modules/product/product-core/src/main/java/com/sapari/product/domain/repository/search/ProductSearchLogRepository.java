package com.sapari.product.domain.repository.search;

import com.sapari.product.domain.model.search.ProductSearchLog;
import java.util.List;
import java.util.Optional;

/**
 * 검색 로그 영속 포트. 대용량 append-only — 운영에선 직접 INSERT 대신 Redis/Kafka 버퍼링 후 bulk COPY가 정석이며, 본 {@code save}는 단건 적재(테스트·소량)용이다.
 * id는 앱이 생성한 TSID(Long).
 */
public interface ProductSearchLogRepository {
    /**
     * 검색 로그 1건 적재(append-only). id는 앱이 주입한 TSID.
     */
    ProductSearchLog save(ProductSearchLog log);

    Optional<ProductSearchLog> findById(Long id);

    /**
     * 특정 검색어의 로그 목록.
     */
    List<ProductSearchLog> findByKeyword(String keyword);
}
