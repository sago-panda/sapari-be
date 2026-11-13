-- SPR-159 egress 웹훅의 조건부 UPDATE(WHERE egress_id = ?)가 웹훅마다 전체 스캔하지 않게 한다.
CREATE INDEX ON live_schema.live_rooms (egress_id) WHERE egress_id IS NOT NULL;
