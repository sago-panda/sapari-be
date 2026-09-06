-- 사용자당 밴 행을 하나로 못 박는다.
--
-- 왜: 서로 다른 방에서 같은 사람을 동시에 강퇴하면 두 호출이 각각 "활성 밴 없음"을 읽고 각각 INSERT 한다
-- (READ COMMITTED 라 서로의 미커밋 행이 보이지 않는다). 트랜잭션을 열어도 막히지 않는다 — 막는 것은
-- 격리 수준이 아니라 제약이다.
--
-- 그 결과가 아픈 자리는 해제다. 해제는 행 DELETE 인데, 행이 몇 개인지 모르면 하나를 지워도 다른 행이
-- 남아 그 사람은 계속 막힌다. 화면에는 "해제됨" 이라고 뜨는데 실제로는 안 풀린다 — 사람이 손으로
-- 테이블을 뒤지기 전에는 원인을 알 수 없는 종류의 고장이다.
--
-- 이 제약이 서면 정본이 미러(chat:banned:{userId}, 늘리기 전용)와 같은 규칙을 따른다. 지금까지는
-- 미러만 단조였고 정본은 무제한 append 라 둘의 모양이 달랐다.

-- 중복이 있으면 이 인덱스 생성이 실패한다. 그게 의도다.
--
-- 처음에는 중복을 조용히 지우는 DELETE 를 함께 뒀는데 빼기로 했다. 그 문장은 전제(운영에 chat 데이터
-- 없음 — origin/main·origin/dev 에 chat 파일 0개)가 맞으면 한 줄도 지나지 않는 죽은 코드이고, 틀리면
-- 제재 기록을 사람 모르게 파괴한다. 둘 다 나쁜 쪽이다. 빼면 중복이 있을 때
-- "Key (user_id)=(...) is duplicated" 로 배포가 멈추고 사람이 본다 — 이 도메인이 오염된 강퇴 키를
-- 자가치유하지 않고, 되돌릴 코드 없는 제재를 서버가 걸지 않기로 한 것과 같은 판단이다.
--
-- CONCURRENTLY 를 쓰지 않는 것도 같은 전제 위에 있다. 빈 테이블이면 즉시 끝나고, 비어 있지 않다면
-- 그건 위 전제가 깨진 상황이라 잠기는 편이 낫다.
--
-- 게다가 여기서는 쓸 수도 없다. CREATE INDEX CONCURRENTLY 는 트랜잭션 안에서 돌지 못하는데, 이 파일은
-- 인덱스 생성과 삭제를 함께 하므로 둘이 한 트랜잭션으로 묶여야 한다 — 중간에 실패해 새 인덱스는 없고
-- 옛 인덱스만 사라진 상태로 남으면, 그때부터는 조용히 느려지기만 한다.

-- 이 문장이 실패했을 때(Key (user_id)=(...) is duplicated) 사람이 할 일:
--   1) 무엇이 겹쳤는지 본다
--        SELECT user_id, count(*), array_agg(expires_at ORDER BY expires_at DESC NULLS FIRST)
--          FROM live_schema.chat_ban GROUP BY user_id HAVING count(*) > 1;
--   2) 남길 것은 가장 오래 가는 밴이다 — 만료 없음(NULL)이 가장 긴 만료다.
--      짧은 쪽을 남기면 그 사람의 밴이 정본보다 일찍 풀린다.
--        DELETE FROM live_schema.chat_ban WHERE id IN (
--            SELECT id FROM (SELECT id, row_number() OVER (PARTITION BY user_id
--                     ORDER BY expires_at DESC NULLS FIRST, created_at DESC, id) rn
--                FROM live_schema.chat_ban) t WHERE rn > 1);
--   3) 다시 적용한다. 실패한 마이그레이션은 통째로 롤백되고 이력에 남지 않으므로 repair 가 필요 없다
--      (Postgres 는 DDL 도 트랜잭셔널 — 실 Flyway 컨테이너로 확인).
-- 위 DELETE 를 마이그레이션에 넣지 않는 이유는 파일 첫머리에 있다. 판단이 필요한 삭제는 사람이 한다.
CREATE UNIQUE INDEX uk_chat_ban_user_id ON live_schema.chat_ban (user_id);

-- (user_id, expires_at) 인덱스는 이 제약이 서면 쓸모가 없다 — 사용자당 행이 하나뿐이라 두 번째 열이
-- 좁힐 것이 없고, 위 유니크 인덱스가 같은 조회를 그대로 받는다. 이 마이그레이션이 만들어 낸 잉여라
-- 여기서 함께 치운다. 이름은 V1 에서 무명으로 만들어 Postgres 기본 규칙이 붙인 것이고, 실제
-- Postgres 16 에 V1 을 올려 확인했다. IF EXISTS 를 붙이지 않는 것은 그 가정이 깨지는 날 조용히
-- 넘어가면 잉여 인덱스가 남은 것을 아무도 모르기 때문이다.
DROP INDEX live_schema.chat_ban_user_id_expires_at_idx;
