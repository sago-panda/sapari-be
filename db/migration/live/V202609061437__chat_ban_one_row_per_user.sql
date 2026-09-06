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

-- 기존 중복 정리 — 남길 것은 가장 오래 가는 밴이다(만료 없음이 가장 긴 만료).
-- 짧은 쪽을 남기면 밴이 정본보다 일찍 풀린다. 정렬은 조회 쿼리와 같은 순서를 쓴다.
DELETE FROM live_schema.chat_ban
 WHERE id IN (
     SELECT id
       FROM (
           SELECT id,
                  row_number() OVER (
                      PARTITION BY user_id
                      ORDER BY expires_at DESC NULLS FIRST, created_at DESC, id
                  ) AS rn
             FROM live_schema.chat_ban
       ) ranked
      WHERE rn > 1
 );

CREATE UNIQUE INDEX uk_chat_ban_user_id ON live_schema.chat_ban (user_id);

-- (user_id, expires_at) 인덱스는 이 제약이 서면 쓸모가 없다 — 사용자당 행이 하나뿐이라 두 번째 열이
-- 좁힐 것이 없고, 위 유니크 인덱스가 같은 조회를 그대로 받는다. 이 마이그레이션이 만들어 낸 잉여라
-- 여기서 함께 치운다. 이름은 V1 에서 무명으로 만들어 Postgres 기본 규칙이 붙인 것이다.
DROP INDEX IF EXISTS live_schema.chat_ban_user_id_expires_at_idx;
