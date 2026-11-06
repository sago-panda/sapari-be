package com.sapari.chat.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 감사 기록 한 줄. <b>조치가 수행됐다는 사실</b>을 적는다 — 요청됐다는 사실이 아니다.
 *
 * <p>그 구분이 기록의 뜻을 정한다. DB 트랜잭션을 가진 경로(강퇴·밴 해제·사전 쓰기)는 이 줄이
 * 조치와 <b>같은 트랜잭션에서 커밋</b>되므로 둘이 갈리지 않는다. Redis 쓰기뿐인 경로(명단 재적재·
 * 종료 마커 해제)는 조인할 트랜잭션이 없어 조치가 <b>성공한 뒤</b>에 적는다 — 그 사이에 이 쓰기가
 * 실패하면 흔적이 비는데, 두 조치가 멱등이고 자기 로그를 남기므로 그쪽을 택했다. 반대로 먼저
 * 적으면 일어나지 않은 조치가 기록에 남고, 그건 감사 기록으로서 더 나쁜 거짓이다.
 *
 * <p><b>{@code detail} 은 선택이 아니라 행위가 정한다.</b> ⚠️ 한때 이 자리에 "v1 에서 비운다 —
 * 담을 만한 것이 {@code targetId}·{@code action} 으로 이미 표현된다" 고 적었는데 <b>사전 쓰기에
 * 대해 거짓이었다</b>. 사전의 한 줄은 식별자가 단어 문자열이고, <b>제거</b>는 정본 행을 지우므로
 * 행 UUID 를 남겨도 가리킬 대상이 없다 — 감사가 답해야 하는 "무엇을 지웠나" 에 답을 못 한다.
 * 그래서 그 두 행위만 {@code detail} 을 싣고, 나머지는 {@code targetId} 로 충분해 비운다.
 * 어느 쪽인지는 {@link ChatAuditAction#requiresTargetId()} 가 들고 있고 아래 생성자가 강제한다.
 *
 * <p>{@code detail} 을 문자열 맵으로 받는 이유: 저장소에 jsonb 매핑 선례가 <b>0건</b>이고
 * (전수: {@code columnDefinition} 은 모두 {@code text}) Hibernate 타입을 새로 들이는 대신
 * 어댑터가 직렬화하고 네이티브 쿼리가 {@code CAST(... AS jsonb)} 한다. 값 타입을 문자열로 묶어
 * 둬서 중첩 구조가 기록에 스며드는 것도 막는다 — 감사 행은 질의 대상이지 문서가 아니다.
 *
 * <p>{@code ip_address} 는 쓰지 않는다. {@code X-Forwarded-For} 는 호출자가 바꿀 수 있어
 * ({@code WebhookRateLimitFilter} 가 같은 축을 적는다 — "공격자가 마음대로 채우는 헤더")
 * 증거로 쓸 수 없고, 채우면 얻는 것 없이
 * PII 보존 의무가 생긴다. 컬럼은 정본에 남겨 두고 비운다.
 *
 * <p><b>역할을 문자열로 받지 않는다.</b> 들어가는 칸이 {@code actor_type varchar(10)} 인데
 * 플랫폼 역할 이름은 그 상한을 약속하지 않는다 — 11자짜리 역할이 생기는 날 조치는 성공하고
 * 감사 쓰기만 터진다(같은 트랜잭션인 경로에서는 조치까지 롤백된다). {@link ChatRole} 은 가장 긴
 * 이름이 6자라({@code SELLER}·{@code SYSTEM}) 상한이 타입으로 묶이고, 서비스가 권한 검사를 위해
 * 이미 파싱해 둔 값이라 변환이 새로 생기지도 않는다.
 *
 * @param actorId   누른 사람. 익명 경로가 없으므로 {@code null} 이 아니다
 * @param actorRole 그 사람의 역할. 커맨드가 싣고 온 이름을 서비스가 파싱한 값이다
 * @param targetId  무엇에 대한 조치인가. 종류는 {@code action} 이 들고 있고, UUID 로 가리켜지지
 *                  않는 행위에서는 {@code null} 이다
 * @param detail    UUID 로 못 가리키는 것. 비어 있으면 {@code null} 로 적힌다
 * @param occurredAt 주입된 시계에서 온 시각 — 조치의 시각과 같아야 하므로 호출자가 넘긴다
 */
public record ChatAuditEntry(
        UUID actorId,
        ChatRole actorRole,
        ChatAuditAction action,
        UUID targetId,
        Map<String, String> detail,
        Instant occurredAt
) {
    public ChatAuditEntry {
        if (actorId == null) {
            throw new IllegalArgumentException("actorId는 필수입니다.");
        }
        if (actorRole == null) {
            throw new IllegalArgumentException("actorRole은 필수입니다.");
        }
        if (action == null) {
            throw new IllegalArgumentException("action은 필수입니다.");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt은 필수입니다.");
        }
        // 행위가 요구하는 대상이 비어 있으면 그 행은 "누가 무언가를 했다" 까지만 말한다.
        // 그 상태는 조회에서 티가 나지 않으므로(두 인덱스가 actor·action 축이다) 여기서 막는다.
        if (action.requiresTargetId() && targetId == null) {
            throw new IllegalArgumentException(action + "은 targetId가 필수입니다.");
        }
        // 반대 방향도 막는다 — 요구하지 않는 행위에 UUID 가 실리면 그게 무엇을 가리키는지
        // 아무도 모르고(사전 행은 지워질 수 있다) 조회가 그걸 대상으로 읽는다.
        if (!action.requiresTargetId() && targetId != null) {
            throw new IllegalArgumentException(action + "은 targetId를 갖지 않습니다.");
        }
        detail = detail == null || detail.isEmpty() ? null : Map.copyOf(detail);
    }

    public ChatAuditTargetType targetType() {
        return action.targetType();
    }
}
