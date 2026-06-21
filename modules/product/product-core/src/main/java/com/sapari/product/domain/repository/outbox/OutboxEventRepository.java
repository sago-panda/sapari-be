package com.sapari.product.domain.repository.outbox;

import com.sapari.product.domain.model.outbox.OutboxEvent;
import java.util.List;
import java.util.Optional;

/**
 * Transactional Outbox 영속 포트. 비즈니스 변경과 같은 트랜잭션에서 이벤트를 적재하고, 워커가 미처리분을 폴링해 ES에 반영한 뒤 처리 완료로 갱신한다. id는 앱이 생성한
 * TSID(Long).
 */
public interface OutboxEventRepository {
    /**
     * 이벤트 적재(비즈니스 INSERT와 동일 트랜잭션) 또는 처리 상태 갱신 후 반환한다.
     */
    OutboxEvent save(OutboxEvent event);

    Optional<OutboxEvent> findById(Long id);

    /**
     * 미처리(processed_at IS NULL) 이벤트 목록 — 워커 폴링 큐(생성순).
     */
    List<OutboxEvent> findUnprocessed();
}
