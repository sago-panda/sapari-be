-- SPR-159 다시보기 공개 목록.
-- WHERE status = 'ENDED' AND vod_status = 'READY' AND is_vod_public [AND 아카이브 조건]
-- ORDER BY ended_at DESC, id DESC 의 키셋 페이징. 상태 두 개를 등치 접두로 두어 바인드 파라미터 쿼리에서도
-- 인덱스가 정렬을 대신한다(부분 인덱스는 일반 플랜에서 조건을 증명하지 못할 수 있어 쓰지 않았다).
-- 공개 여부·아카이브 조건은 행 필터로 충분하다.
CREATE INDEX ON live_schema.live_rooms (status, vod_status, ended_at DESC, id DESC);
