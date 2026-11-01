-- chat_kick_log 방 단위 조회 인덱스 [SPR-151]
--
-- 채팅 이력 조회(SPR-146)가 요청마다 `SELECT user_id ... WHERE live_room_id = ?` 로 그 방의 강퇴자를
-- 읽는다. 기존 인덱스(pk(id) · UNIQUE(user_id, live_room_id) · (user_id, kicked_at)) 중 live_room_id 를
-- 선두로 갖는 것이 없어 이 조회는 순차 스캔이 되고, 비용이 그 방이 아니라 플랫폼 전체 누적 강퇴 수에 비례한다.
--
-- (live_room_id) 단일이 아니라 user_id 까지 넣는 이유: 조회가 user_id 만 읽으므로 인덱스만으로
-- 답할 수 있다(Index Only Scan). 추가 비용은 uuid 컬럼 하나다.
CREATE INDEX ON live_schema.chat_kick_log (live_room_id, user_id);
