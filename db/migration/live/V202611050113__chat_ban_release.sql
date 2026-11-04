-- 밴 해제의 흔적과 사면 기준선을 남긴다.
--
-- 해제는 chat_ban 행 DELETE 다(ChatBan 도메인 javadoc — "해제됨" 같은 열을 두지 않는 쪽으로 결정).
-- 그래서 해제가 끝나면 그 사람에게 밴이 있었다는 사실도, 누가 풀었다는 사실도 <어디에도 남지 않는다>.
-- 자동 승격은 그나마 로그 한 줄이 남는데(ChatKickRecorder) 해제는 그마저 없었다.
--
-- 그런데 이 테이블이 필요한 진짜 이유는 감사가 아니라 <해제가 실제로 해제가 되게> 하는 것이다.
--
-- 자동 승격은 chat_kick_log 를 730일 창으로 세어(ChatKickRecorder.CONFIRMATION_WINDOW) 서로 다른
-- 강퇴자 수가 3·6·9 에 닿으면 1주·1달·1년을 건다. 해제가 chat_ban 행과 Redis 미러만 지우면
-- <chat_kick_log 행은 그대로 남는다>. 그러면 해제된 사용자가 <새 방에서> 강퇴 한 번을 더 받는 순간
-- 과거 강퇴자가 그대로 다시 세어져 같은 티어가 재적용된다 — 9명에 닿았던 사용자는 강퇴 1회로 1년
-- 밴이고, 창이 730일이라 2년 가까이 그렇다. 즉 해제가 '해제' 가 아니라 '다음 강퇴 1회까지의 유예' 다.
-- (같은 방 재강퇴로는 재현되지 않는다 — ChatKickRecorder 가 firstKickInThisRoom 이 아니면 세는
--  걸음에 가지 않는다. 그래서 이 계급을 재는 벡터는 반드시 <새 방> 이어야 한다.)
--
-- 강퇴 로그를 지우는 쪽은 택하지 않는다. 그건 일어난 사건이고(ChatBan javadoc 이 그 둘의 수명 차이를
-- 테이블 분리의 근거로 적는다) 이력 조회가 그 행으로 강퇴자를 제외한다(GetChatHistoryService).
-- 대신 세는 창의 <하한>을 여기에 둔다 — 해제 시각 이후의 강퇴만 센다.
--
-- append-only 다. 해제를 되돌리는 것은 다시 밴을 거는 것이므로 이 행을 지울 이유가 없고,
-- 지우면 사면 기준선이 과거로 되돌아가 재밴이 되살아난다.
CREATE TABLE live_schema.chat_ban_release (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL,
    released_by_id  uuid        NOT NULL,                  -- 해제한 ADMIN id
    released_at     timestamptz NOT NULL,                  -- 주입된 시계에서 온다(TimeProvider)
    CONSTRAINT pk_chat_ban_release PRIMARY KEY (id)
);

-- 세는 걸음이 이 사용자의 <가장 최근> 해제 시각 하나만 찾는다(상관 서브쿼리의 MAX).
-- 선두 열이 user_id 이고 released_at 이 내림차순이라 그 조회가 인덱스 한 번으로 끝난다 —
-- 이 조회는 강퇴마다, 사용자 단위 어드바이저리 잠금을 쥔 안에서 돌기 때문에 순차 스캔이면 안 된다.
CREATE INDEX chat_ban_release_user_id_released_at_idx
    ON live_schema.chat_ban_release (user_id, released_at DESC);
