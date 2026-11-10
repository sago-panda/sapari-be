-- SPR-172 최근 본 라이브.
-- (회원, 방) 마다 한 행 — 입장할 때 viewed_at 만 덮어쓴다(ON CONFLICT DO UPDATE — 새 시각이 1분 넘게 늦을 때만, 그래서 단조 증가).
-- FK 는 두지 않는다: 같은 스키마의 다른 이력 테이블과 같은 관례이고, 방은 삭제되지 않는다.
-- 90일 지난 행은 조회에서 거를 뿐 지우지 않는다(정리 배치는 범위 밖).
CREATE TABLE live_schema.live_recent_views (
    user_id      uuid        NOT NULL,
    live_room_id uuid        NOT NULL,
    viewed_at    timestamptz NOT NULL,
    CONSTRAINT pk_live_recent_views PRIMARY KEY (user_id, live_room_id)
);

-- 내 최근 본 목록: WHERE user_id = ? AND viewed_at >= :since [AND (viewed_at, live_room_id) < 커서]
--                 ORDER BY viewed_at DESC, live_room_id DESC 의 키셋 페이징 — 정렬과 같은 모양이다.
CREATE INDEX ON live_schema.live_recent_views (user_id, viewed_at DESC, live_room_id DESC);
