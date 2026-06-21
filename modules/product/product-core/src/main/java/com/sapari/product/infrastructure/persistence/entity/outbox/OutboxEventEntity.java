package com.sapari.product.infrastructure.persistence.entity.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
 * Transactional Outbox — PG ↔ ES 비동기 동기화 원자성 보장. id는 앱 생성 TSID(serial 미사용) — 베이스 미상속. aggregate_type(소문자 'product'
 * 등)·event_type은 값이 enum과 케이스가 달라 String으로 둠.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "outbox_events", schema = "product_schema")
public class OutboxEventEntity {

    @Id
    private Long id;

    // ENUM: product, combination, order, review
    private String aggregateType;

    // 도메인 객체 ID. FK 미설정 — 삭제 후에도 발행 가능.
    private UUID aggregateId;

    // ENUM: UPSERTED, DELETED, STOCK_CHANGED, PRICE_CHANGED, DISCOUNT_WINNER_CHANGED, REVIEW_ADDED, OPTION_VALUE_RENAMED
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    // DB DEFAULT now() — 미설정 시 DB가 채움.
    private Instant createdAt;

    private Instant processedAt;

    private Integer retryCount;

    private String lastError;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여 private으로 두고, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 강제한다.
     *
     * <p>id는 {@code @GeneratedValue}가 없는 앱 공급 TSID이므로 생성 시점에 빌더로 직접 주입한다.
     */
    @Builder
    private OutboxEventEntity(Long id, String aggregateType, UUID aggregateId, String eventType, String payload,
                              Instant createdAt, Instant processedAt, Integer retryCount, String lastError) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
        this.processedAt = processedAt;
        this.retryCount = retryCount;
        this.lastError = lastError;
    }

    /**
     * 디스패처의 발행 시도 결과를 반영한다.
     *
     * <p>성공이면 processedAt을 채워 처리 완료로 표시하고, 실패면 증가한 retryCount와 lastError를
     * 함께 기록한다. 처리 시각·재시도 횟수·마지막 오류는 한 시도의 결과로 함께 갱신되어야 일관되므로 묶어서 반영한다.
     */
    public void applyProcessing(Instant processedAt, Integer retryCount, String lastError) {
        this.processedAt = processedAt;
        this.retryCount = retryCount;
        this.lastError = lastError;
    }
}
