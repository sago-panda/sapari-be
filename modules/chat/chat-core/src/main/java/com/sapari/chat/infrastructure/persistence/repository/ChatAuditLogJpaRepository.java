package com.sapari.chat.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sapari.chat.infrastructure.persistence.entity.ChatAuditLogEntity;

public interface ChatAuditLogJpaRepository extends JpaRepository<ChatAuditLogEntity, UUID> {

    /**
     * 감사 한 줄을 덧붙인다.
     *
     * <p><b>왜 네이티브인가</b>: 덧붙이기 전용이고 충돌 처리도 반환값도 필요 없다 — JPA
     * {@code save} 로도 같은 INSERT 하나가 나가지만 엔티티가 영속 컨텍스트에 올라가 플러시에서
     * 더티 체킹을 받는다. 그 비용에 대가가 없고, 형제 셋(강퇴·밴·사전)이 모두 네이티브라 쓰기
     * 경로의 모양이 하나로 유지된다.
     *
     * <p>{@code id} 는 넘기지 않는다 — 테이블 기본값({@code gen_random_uuid()})이 만든다. 반면
     * {@code created_at} 은 <b>기본값이 아예 없고</b>, 있더라도 넘겨야 한다: 그 시각은 조치의
     * 시각이어야 하므로 주입된 시계에서 와야 하고 DB 시계는 그것이 아니다(형제
     * {@code insertIfAbsent} 가 {@code kicked_at} 에 같은 판단을 적는다).
     *
     * <p><b>{@code detail} 은 문자열로 받아 쿼리에서 캐스트한다.</b> 저장소에 jsonb 매핑 선례가
     * 0건이라 Hibernate 타입({@code @JdbcTypeCode(SqlTypes.JSON)})을 새로 들이지 않는다 —
     * 들이면 이 모듈의 <b>모든</b> 엔티티가 그 타입 등록을 공유하게 되고, 그 선택이 처음 쓰는
     * 자리에서 조용히 전역이 된다. {@code CAST} 는 이 한 문장 안에 머문다. {@code null} 이면
     * {@code NULL::jsonb} 라 컬럼 기본값과 같다.
     *
     * <p>{@code ip_address} 는 열거하지 않는다 — v1 이 쓰지 않고 nullable 이다({@code ChatAuditEntry}
     * javadoc 에 이유).
     */
    @Modifying
    @Query(value = """
            INSERT INTO live_schema.audit_logs
                   (actor_id, actor_type, action, target_id, target_type, detail, created_at)
            VALUES (:actorId, :actorType, :action, :targetId, :targetType,
                    CAST(:detail AS jsonb), :createdAt)
            """, nativeQuery = true)
    void insert(@Param("actorId") UUID actorId,
                @Param("actorType") String actorType,
                @Param("action") String action,
                @Param("targetId") UUID targetId,
                @Param("targetType") String targetType,
                @Param("detail") String detail,
                @Param("createdAt") Instant createdAt);
}
