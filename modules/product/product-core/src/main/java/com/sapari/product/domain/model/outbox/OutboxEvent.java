package com.sapari.product.domain.model.outbox;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * Transactional Outbox 이벤트 애그리거트 루트. id는 앱 생성 TSID(필수). aggregateType(소문자 'product' 등)·eventType은 값 케이스가 enum과 달라
 * String으로 둔다.
 */
@Builder(toBuilder = true)
public record OutboxEvent(
        Long id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        Instant createdAt,
        Instant processedAt,
        Integer retryCount,
        String lastError
) {

    public OutboxEvent {
        if (id == null) {
            throw new IllegalArgumentException("id(앱 생성 TSID)는 필수입니다.");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType은 필수입니다.");
        }
        if (aggregateId == null) {
            throw new IllegalArgumentException("aggregateId는 필수입니다.");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType은 필수입니다.");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload는 필수입니다.");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt은 필수입니다.");
        }
    }

    /**
     * 신규 아웃박스 이벤트를 생성한다. 모든 필드가 필수다.
     *
     * <p>도메인 변경과 같은 트랜잭션에서 이벤트를 INSERT해 두고 별도 릴레이가 발행하는 Transactional
     * Outbox 패턴이라, id는 DB 시퀀스가 아니라 앱이 미리 만든 TSID를 받는다. retryCount는 0으로 시작한다.
     */
    public static OutboxEvent create(Long id, String aggregateType, UUID aggregateId, String eventType,
                                     String payload, Instant createdAt) {
        return OutboxEvent.builder()
                .id(id)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .createdAt(createdAt)
                .retryCount(0)
                .build();
    }

    /**
     * 발행 성공으로 마킹한다. processedAt을 채우고 직전 실패 흔적(lastError)을 지운 새 인스턴스를 반환한다. 이후 릴레이가 미처리 이벤트만 다시 집어가지 않도록 하는 표식이다.
     */
    public OutboxEvent markProcessed(Instant now) {
        return toBuilder()
                .processedAt(now)
                .lastError(null)
                .build();
    }

    /**
     * 발행 실패로 마킹한다. retryCount를 1 증가시키고 마지막 에러를 기록한 새 인스턴스를 반환한다. 누적 재시도 횟수로 백오프/최대 재시도 제한이나 DLQ 이동 판단의 근거를 남긴다.
     */
    public OutboxEvent markFailed(String error) {
        return toBuilder()
                .retryCount((retryCount == null ? 0 : retryCount) + 1)
                .lastError(error)
                .build();
    }

    /**
     * 이미 발행 완료(processedAt이 채워짐)인지 여부.
     */
    public boolean isProcessed() {
        return processedAt != null;
    }
}
