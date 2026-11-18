-- SPR-158 방 관리.
-- status 에 'CANCELLED' 값이 추가되지만 CHECK 제약이 없어 DDL 은 필요 없다(취소 시각은 ended_at 에 둔다).
-- 단 구버전 앱은 이 값을 읽지 못한다 — 취소 기능은 모든 레플리카가 새 코드로 바뀐 뒤 노출할 것(modules/live/AGENTS.md).

-- RTMP 송출 주소. 발급 시 ingress_id 와 같은 조건부 UPDATE 로 저장한다(판매자 방 상세용).
-- streamKey(자격증명)는 여전히 저장하지 않는다. 이 컬럼 이전에 발급된 방은 NULL 이다.
ALTER TABLE live_schema.live_rooms ADD COLUMN rtmp_url varchar(500);

-- 판매자 내 방 목록: WHERE seller_id = ? [AND status IN (...)] ORDER BY scheduled_at DESC, id DESC 의 키셋 페이징.
-- 기존 (seller_id, status, created_at) 은 정렬 키가 달라 쓰지 못한다. 탭별 개수(GROUP BY status)는 그쪽이 맡는다.
CREATE INDEX ON live_schema.live_rooms (seller_id, scheduled_at DESC, id DESC);
