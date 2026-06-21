package com.sapari.product.domain.model.audit;

/**
 * 감사 로그 행위 주체 유형. SYSTEM이면 {@code actorId}가 null이다.
 */
public enum ActorType {
    USER,   // 일반 사용자/판매자
    ADMIN,  // 관리자
    SYSTEM  // 시스템
}
