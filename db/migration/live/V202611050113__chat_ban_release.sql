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
-- 지워지는 행의 스냅샷을 같이 적는다. 이것이 없으면 감사가 "무엇을 지웠는지" 를 못 답한다 —
-- 정본 행이 DELETE 로 사라지므로 영구였는지 기한부였는지(expires_at)도, 누가 걸었는지(banned_by_id)도
-- 어디에도 남지 않는다. 그리고 <되돌릴 수도 없다>: 수동 밴을 넣는 앱 경로가 0건이고(밴 생성 호출처는
-- ChatKickRecorder 하나이며 그 티어에 영구가 없다) 그래서 사람이 넣은 영구 밴을 한 번 지우면 어떤
-- 앱 경로로도 다시 걸 수 없다. 최소한 무엇이었는지는 남아야 한다.
--
-- ban_existed 가 따로 있는 이유: 활성 밴을 푼 것과 밴 없는 사람에게 버튼을 누른 것이 저장된 기록에서
-- 같은 행이 되면 안 된다. 오타 한 글자로 실존하는 다른 사용자의 730일 누적을 조용히 접을 수 있고
-- (응답이 존재 여부를 누설하지 않는 쪽이 맞지만 운영자에게도 신호가 0이면 안 된다) 이 테이블은
-- append-only 라 앱 경로로 기준선을 낮출 수도 없다. 그 사실을 행과 응답 양쪽에 남긴다.
CREATE TABLE live_schema.chat_ban_release (
    id                     uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id                uuid        NOT NULL,           -- soft-ref → user_schema.users.id (스키마 간 FK 금지)
    released_by_id         uuid        NOT NULL,           -- soft-ref → user_schema.users.id (해제한 ADMIN)
    released_at            timestamptz NOT NULL,           -- 주입된 시계에서 온다(TimeProvider)
    ban_existed            boolean     NOT NULL,           -- 지운 밴이 실제로 있었는가
    released_expires_at    timestamptz,                    -- 지운 밴의 만료. NULL = 영구였거나 밴이 없었다
    released_banned_by_id  uuid,                           -- 지운 밴의 발급자(SYSTEM=자동 승격). 밴이 없었으면 NULL
    CONSTRAINT pk_chat_ban_release PRIMARY KEY (id)
);

-- 세는 걸음이 이 사용자의 <가장 최근> 해제 시각 하나만 찾는다(상관 서브쿼리의 MAX).
-- 이 조회는 강퇴마다, 사용자 단위 어드바이저리 잠금을 쥔 안에서 돌기 때문에 순차 스캔이면 안 된다.
--
-- 사는 것은 released_at 이 <인덱스에 들어 있다>는 사실이다 — 그래서 Index Only Scan 이 된다.
-- DESC 가 사는 것은 아니다: btree 는 양방향 스캔이라 ASC 와 계획이 같다(실측, postgres:16 에 5만 행 —
-- (user_id, released_at DESC) 와 (user_id, released_at) 가 동일한 Index Only Scan 이고
-- (user_id) 단독만 힙 접근이 붙는다). 방향을 맞춰야 한다고 읽지 말 것 — 그 확장은 비용만 된다.
CREATE INDEX chat_ban_release_user_id_released_at_idx
    ON live_schema.chat_ban_release (user_id, released_at DESC);

-- ⚠️ 롤아웃 순서: 이 마이그레이션이 앱보다 <먼저> 가야 한다. 그러지 않으면 새 엔드포인트만
-- 깨지는 것이 아니라 <기존 강퇴>가 깨진다 — 사면 기준선을 읽는 상관 서브쿼리가
-- countConfirmingKickersSince 안에 있고, 그 질의는 방마다 첫 강퇴일 때 매번 돈다. 실측(사본에서
-- 이 파일만 치우고 ChatBanStateRepositoryImplTest): 17건 중 6건 실패, 그중 하나는 이 티켓 전부터
-- 있던 '누적 강퇴는 방을 가리지 않고, 창 밖의 것은 세지 않는다' 다. 에러는
-- relation "chat_ban_release" does not exist 다.
-- infra/AGENTS.md 가 shedlock 에 대해 "the migration leads, live-app follows" 를 적은 것과 같은
-- 의존이다. 가드를 따로 만들지는 않았다 — 조용히 틀리는 쪽이 아니라 시끄러운 500 이라서다.
