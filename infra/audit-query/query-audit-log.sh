#!/bin/sh
# =============================================================================
# 감사 기록 조회 — live_schema.audit_logs 에 사람이 물어보는 길.
#
# 왜 필요한가: SPR-170 이 관리자 조치 여섯(ChatAuditAction 상수 수, 실측)을 그 표에 쓰기
# 시작했는데 **읽는 코드가 0건**이었다. modules/chat/AGENTS.md 가 그 원칙을 적어 두었다 —
# "a real audit needs a store **and** a way for a person to query it". 거기서 든 예는
# chat_kick_log 로 **다른 표**다. 원칙만 가져오고 이 표에 적용한 것은 이 스크립트다.
#
# 형제 replay-backfill/backfill-archive-url.sh 의 규칙을 그대로 받는다:
#   - 자격증명을 argv 로 받지 않는다(argv 는 같은 호스트의 다른 사용자가 ps 로 읽는다).
#     libpq 표준 환경변수만 쓴다. 비밀번호는 ~/.pgpass(0600) 또는 PGSERVICE 로 둘 것.
#   - 쓰기 문장을 만들지 않는다. 이 스크립트는 SELECT 만 낸다.
# 다른 점 하나: 형제는 UPDATE 를 생성하므로 SQL 만 stdout 으로 내지만, 이쪽은 읽기뿐이라 직접
# 실행한다 — psql 에 다시 붙여넣게 만들면 "사람이 물어볼 수 있게" 라는 목적이 반쯤 사라진다.
#
# 종료 코드:
#   0  조회 성공(0건도 성공이다 — "그 조치가 없었다" 가 답이다)
#   64 사용법·인자 오류(EX_USAGE). **2 를 쓰지 않는다** — psql 이 접속 실패에 2 를 내므로
#      같은 값을 쓰면 "오타" 와 "DB 가 안 뜬다" 가 구분되지 않는다(실측: 둘 다 2 였다).
#      그 충돌은 테스트에도 번졌다 — argv 칸들이 가드 때문이 아니라 접속 실패로 통과했다.
#   12 self-test 실패
#   그 밖 psql 자신의 종료 코드가 그대로 빠져나간다(접속 실패 2 · ON_ERROR_STOP 3).
#      형제에게는 preflight() → exit 1 이 있는데 여기에는 없다. 이유: 형제는 UPDATE 를
#      만드는 여러 라운드 작업이라 나쁜 입력으로 시작하지 않는 것이 중요하고, 이쪽은 읽기
#      한 방이라 psql 자신의 오류 문구가 같은 정보를 바로 준다.
# =============================================================================
set -eu

usage() {
    cat >&2 <<'USAGE'
Usage: query-audit-log.sh [--explain] <mode> [args...]
       query-audit-log.sh --self-test

모드 (limit 기본 50, 1 이상의 정수):
  actor  <actor_id> <actor_type> [limit]   이 사람이 무엇을 했나
  action <action>                [limit]   이 조치가 누구에게 갔나
  target <target_id>             [limit]   이 대상에게 무엇이 있었나

비용(실측, 40만 행 postgres:16):
  actor  0.088ms  (actor_id, actor_type, created_at) 인덱스를 정렬까지 쓴다.
                  그래서 actor_type 은 선택이 아니라 필수다 — 빼면 13.0ms(148배).
  action 0.072ms  (action, created_at) 인덱스. 값은 정확히 일치여야 한다(LIKE 없음).
  target 9.9ms    인덱스가 없다. 되지만 표 크기에 비례해 느려진다.
detail(jsonb) 로 묻는 모드는 없다 — 실측 15.8ms 이고 고정할 계획 모양이 없어 넣지 않았다.

--explain 은 같은 질의의 실행 계획을 낸다(EXPLAIN ANALYZE).

접속은 libpq 환경변수로 한다(PGHOST/PGPORT/PGDATABASE/PGUSER, 또는 PGSERVICE).
비밀번호는 argv 로 넘기지 않는다 — ~/.pgpass(0600) 또는 PGSERVICE 를 쓸 것.
USAGE
}

# 인자 검증을 순수 함수로 뗀다 — 형제가 같은 자리에서 배운 것이다("종료 코드 결정. 순수 함수로
# 떼어 self-test 로 덮는다 — 직전 라운드의 High 가 여기서 났다"). DB 없이 덮인다.
#
# 반환 0 = 통과. 그 밖은 종료 코드. 거부 사유는 stderr 로 낸다.
validate_args() {
    case "${1:-}" in
        actor)
            # actor_type 은 선택이 아니다. 인덱스가 (actor_id, actor_type, created_at) 라서
            # actor_type 을 고정하지 않으면 created_at 이 정렬에 쓰이지 못하고 Sort 가 붙는다.
            # 실측(40만 행, postgres:16): 둘 다 고정 0.088ms(Index Scan Backward) vs
            # actor_type 누락 13.0ms = 148배. 그래서 조용히 느려지게 두지 않고 거부한다.
            if [ -z "${2:-}" ] || [ -z "${3:-}" ]; then
                cat >&2 <<'ERR'
actor 질의는 actor_id 와 actor_type 을 둘 다 받는다.

  query-audit-log.sh actor <actor_id> <actor_type> [limit]

actor_type 을 비우면 인덱스(actor_id, actor_type, created_at)가 정렬에 쓰이지 못해
Sort 가 붙는다 — 실측 40만 행에서 0.088ms → 13.0ms(148배). 느려지는 것을 모르게 두는 대신
여기서 거부한다. 값은 ChatRole 이름이다(ADMIN·SELLER 등).
ERR
                return 64
            fi
            ;;
        action|target)
            # 이 둘은 값 하나다. action 은 (action, created_at) 인덱스를 타고, target 은
            # 인덱스가 없어 전체 스캔이다 — 실측 40만 행에서 9.9ms 이고 표 크기에 비례한다.
            # 되는 것과 싼 것은 다르므로, 막지 않고 사용법에 비용을 적는다.
            if [ -z "${2:-}" ]; then
                printf '%s 질의는 값 하나를 받는다: query-audit-log.sh %s <값> [limit]\n' "$1" "$1" >&2
                return 64
            fi
            ;;
        '') usage; return 64 ;;
        *) usage; return 64 ;;
    esac
    return 0
}

# 순수 로직 점검(DB 불필요). 건수를 출력하는 이유: "exit 0" 한 줄로도 통과하는 가드를 만들지
# 않기 위해서다 — 분모를 출력에 적어 두면 비어 있는 것이 보인다.
self_test() {
    checked=0
    fail=0

    # validate_args 의 반환값만 본다. stderr 는 버린다(문구가 아니라 판정을 재는 칸이다).
    expect_rc() {
        want="$1"; label="$2"; shift 2
        validate_args "$@" >/dev/null 2>&1 && got=0 || got=$?
        checked=$((checked + 1))
        if [ "$got" != "$want" ]; then
            printf 'self-test FAIL: %s — 기대 %s, 실제 %s\n' "$label" "$want" "$got" >&2
            fail=$((fail + 1))
        fi
    }

    expect_rc 64 '모드 없음'                       ''
    expect_rc 64 '알 수 없는 모드'                  nonsense
    expect_rc 64 'actor — actor_type 누락'          actor 11111111-1111-1111-1111-111111111111
    expect_rc 0 'actor — 둘 다 있으면 통과'        actor 11111111-1111-1111-1111-111111111111 ADMIN

    # validate_args 가 통과시키는 모드와 where_clause 가 아는 모드가 갈리면 런타임에 SQL
    # 문법 오류로만 드러난다. 둘의 일치를 여기서 센다 — 분모는 아래 목록의 3이다.
    #
    # ⚠️ 이 목록은 손으로 적은 것이라 **모드를 새로 늘릴 때 같이 늘려야** 전수가 된다. 그걸
    # 잊어도 where_clause 의 `*)` arm 이 조용한 빈 WHERE 대신 소리를 낸다 — 이 칸이 덮는 것은
    # "기존 모드의 arm 이 없어지는 쪽" 이고, "새 모드가 한쪽에만 생기는 쪽" 은 그 arm 이 받는다.
    for m in actor action target; do
        checked=$((checked + 1))
        if [ -z "$(where_clause "$m" 2>/dev/null)" ]; then
            printf 'self-test FAIL: where_clause 가 %s 를 모른다\n' "$m" >&2
            fail=$((fail + 1))
        fi
    done

    if [ "$fail" -ne 0 ]; then
        printf 'self-test 실패 %s건 / 검사 %s건\n' "$fail" "$checked" >&2
        return 12
    fi
    printf 'self-test 통과 %s건\n' "$checked"
    return 0
}

# 모드별 WHERE 절. 조건절이 인덱스와 맞는지가 성능의 전부라 한 자리에 모아 둔다 —
# 통합 테스트의 `--explain` 칸이 이 문자열의 결과를 계획 모양으로 고정한다.
#
# 값은 전부 psql 변수로 넘긴다(:'v1' 형태). 문자열 연결로 SQL 을 만들지 않는 이유는 인젝션이다 —
# 운영자가 붙여넣는 값에 따옴표가 섞일 수 있고, 이 스크립트는 그 값을 신뢰하지 않는다.
where_clause() {
    case "$1" in
        actor)  echo "actor_id = :'v1'::uuid AND actor_type = :'v2'" ;;
        action) echo "action = :'v1'" ;;
        target) echo "target_id = :'v1'::uuid" ;;
        # validate_args 가 먼저 막으므로 오늘은 도달하지 않는다. 그래도 조용히 빈 WHERE 를
        # 만들지 않는다 — 그러면 `WHERE  ORDER BY` 문법 오류가 되고, 모드를 하나 더 늘릴 때
        # 두 열거가 갈라진 것이 여기서 드러난다. self-test 가 그 둘의 일치를 센다.
        *) printf 'where_clause: 알 수 없는 모드 %s\n' "$1" >&2; return 64 ;;
    esac
}

run_query() {
    mode="$1"; explain="$2"; v1="$3"; v2="$4"; limit="$5"
    where="$(where_clause "$mode")"
    select="SELECT created_at, actor_type, actor_id, action, target_type, target_id, detail
              FROM live_schema.audit_logs
             WHERE $where
          ORDER BY created_at DESC
             LIMIT $limit"

    # ⚠️ SQL 을 stdin 으로 넘긴다. `-c` 는 psql 변수 치환(:'v1')을 하지 않는다 — 실측으로
    # 확인했다(ERROR: syntax error at or near ":"). 값을 셸에서 문자열 연결로 끼우는 쪽은
    # 인젝션 면이 열리므로, 치환이 되는 입력 경로를 쓴다.
    if [ "$explain" = "yes" ]; then
        psql -X -q -v ON_ERROR_STOP=1 -v v1="$v1" -v v2="$v2" -P pager=off <<SQL || return $?
EXPLAIN (ANALYZE, BUFFERS) $select;
SQL
        return 0
    fi

    psql -X -q -v ON_ERROR_STOP=1 -v v1="$v1" -v v2="$v2" -P pager=off -P footer=off <<SQL || return $?
$select;
SQL

    # 건수를 따로 센다. psql 의 "(N rows)" 꼬리를 파싱하는 쪽이 왕복 하나를 아끼지만 그 문구는
    # 우리 계약이 아니다(로케일·버전·pset 에 따라 바뀐다).
    #
    # ⚠️ 위 $select 를 그대로 감싸면 안 된다 — 안에 LIMIT 이 있어 상한에서 "50건" 이 **정확히
    # 50** 과 **잘렸다** 를 구분하지 못한다(감사 조회에서 그 둘은 다른 답이다). 상한+1 까지만
    # 세고 멈춘다: 넘겼는지만 알면 되고, 전체를 세면 LIMIT 가 묶어 둔 스캔 비용이 풀린다.
    # 이 탐침에는 ORDER BY 가 없다(몇 건인지에 순서는 필요 없다). 그래서 `--explain` 가드가
    # 고정하는 계획은 위 $select 것이고 이 탐침 것은 아니다 — 대신 LIMIT 이 비용을 묶는다.
    #
    # 행과 건수는 **다른 스냅샷**이다(왕복 둘). append-only 표라 사이에 들어온 행은 건수에만
    # 보일 수 있고, 그때 나오는 말은 "잘렸다" 로 안전한 쪽이다.
    probe="SELECT 1 FROM live_schema.audit_logs WHERE $where LIMIT $((limit + 1))"
    n="$(psql -X -tAq -v ON_ERROR_STOP=1 -v v1="$v1" -v v2="$v2" <<SQL
SELECT count(*) FROM ($probe) s;
SQL
    )" || return $?
    if [ "$n" -gt "$limit" ]; then
        printf '%s건 이상 — 상한 %s 에서 잘렸다. limit 인자를 올려라.\n' "$limit" "$limit"
    else
        printf '%s건\n' "$n"
    fi
}

explain=no
case "${1:-}" in
    --self-test) self_test; exit $? ;;
    --explain) explain=yes; shift ;;
esac

validate_args "$@" || exit $?

mode="$1"
case "$mode" in
    actor)  v1="$2"; v2="$3"; limit="${4:-50}" ;;
    action) v1="$2"; v2=""   ; limit="${3:-50}" ;;
    target) v1="$2"; v2=""   ; limit="${3:-50}" ;;
esac

# limit 은 SQL 에 **셸 문자열로 끼워지는 유일한 값**이다(나머지는 psql 변수 :'v1').
# 두 겹인 이유는 역할이 다르다 — 실측: 모양 가드만 지우면 범위 가드의 `[: integer expression
# expected` 가 받아 여전히 거부한다(즉 모양 가드는 게이트가 아니라 **문구** 담당), 범위 가드만
# 지우면 limit=0 이 SQL 까지 간다. 게이트는 범위 가드 쪽이다.
case "$limit" in
    ''|*[!0-9]*) printf 'limit 은 양의 정수다: %s\n' "$limit" >&2; exit 64 ;;
esac
[ "$limit" -gt 0 ] || { printf 'limit 은 1 이상이다: %s\n' "$limit" >&2; exit 64; }

run_query "$mode" "$explain" "$v1" "$v2" "$limit"
