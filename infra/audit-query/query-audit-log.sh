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
    # 행과 건수는 **다른 스냅샷**이다(왕복 둘). append-only 표라(실측: audit_logs 에 DELETE·
    # TRUNCATE·보존 잡 0건) 사이에 들어온 행은 건수에만 보일 수 있고, 그때 나오는 말은
    # "잘렸다" 로 안전한 쪽이다. ⚠️ 안전한 쪽인 것은 **스냅샷 축에서만** 그렇다 — 같은 건수
    # 줄이 다른 축에서 안 전한 쪽으로 틀린 적이 있다(위 limit 밑수).
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

# limit 은 SQL 에 **셸 문자열로 끼워지는 유일한 값**이다 — 나머지(v1·v2)는 psql 변수
# :'v1' 로 가고, 모드는 아래 3개 화이트리스트다. 그 전수가 기대는 것: $where 도 셸로
# 끼워지므로, **모드를 하나 늘릴 때 그 WHERE 에 운영자 값의 조각을 넣으면 이 문장이 조용히
# 거짓이 된다.** 새 모드는 값을 반드시 :'vN' 으로 넘길 것.
#
# ⚠️ 그리고 이 값의 실제 결함은 인젝션이 아니라 **밑수**였다. 한 문자열을 세 독자가 다르게
# 읽는다(실측): Postgres `LIMIT 050` → 10진 50 · `[ 050 -gt 49 ]` → 10진 50 ·
# `$((050 + 1))` → **8진 41**. 그래서 선행 0 이 들어오면 행은 50개 나가는데 건수 탐침은
# 41 을 세고, `[ 41 -gt 050 ]` 이 거짓이라 **잘림 경고까지 사라진다**. `08`·`09` 는 한술 더
# 떠서 `$((08 + 1))` 이 "value too great for base" 로 죽어 종료 코드 표에 없는 1 이 난다.
#
# 고치는 방법으로 `$((10#$limit + 1))` 이 먼저 떠오르지만 **쓸 수 없다** — `10#` 는 bash/ksh
# 확장이고 POSIX 산술에 없다(실측: dash 는 `arithmetic expression: expecting EOF`). 셰뱅이
# /bin/sh 이므로 여기서 막는다. 선행 0 을 거부하면 세 독자가 같은 값을 읽는다.
#
# 가드는 **한 겹이다**. 선행 0 을 거부하면 남는 값은 [1-9][0-9]* 뿐이라 예전의 `-gt 0` 는
# 분모가 0 이 된다(그래서 뺐다).
#
# 상한을 둔다. 전에는 "너무 큰 값은 행 질의의 LIMIT 이 psql 오류로 먼저 죽는다" 고 적었는데
# **두 자리에서 거짓이었다**(실측, 분모 5행):
#   limit=9000000000000000000   exit 0 · 행 5 · "5건"  ← 아무것도 안 죽는다. 그 띠에서 저
#                                                        문장은 한 번도 발동하지 않는다.
#   limit=9223372036854775807   exit 3 · 행 5 · ERROR: LIMIT must not be negative
#                                                      ← 죽는 쪽이 행 질의가 아니라 **탐침**이다.
#                                                        $((max + 1)) 이 음수로 랩어라운드한다.
#   limit=9223372036854775808   exit 3 · 행 0 · ERROR: bigint out of range
# 첫 띠가 문제다 — 조용히 틀리지는 않지만(건수는 맞다) 행 질의가 **더 이상 LIMIT 으로 묶이지
# 않는다**. 위 비용표 전체가 "LIMIT 이 인덱스 스캔을 묶는다" 에 걸려 있으니, 그 불변식을
# 설명으로 두는 대신 입구에서 참으로 만든다. 상한을 두면 세 띠가 다 도달 불가다.
#
# 10000 은 **측정으로 나온 경계가 아니라 정책 상한**이다: 이 창구는 사람이 터미널에서 읽는
# 것이고, 답이 그보다 크면 질문이 틀린 것이다(actor/action 을 좁히거나 덤프를 따로 떠라).
# 위 실측이 정하는 것은 "상한이 있어야 한다" 뿐이고 값은 운영 판단이다.
LIMIT_MAX=10000
case "$limit" in
    ''|*[!0-9]*|0*)
        printf 'limit 은 선행 0 없는 1 이상의 10진 정수다: %s\n' "$limit" >&2
        exit 64 ;;
esac
if [ "$limit" -gt "$LIMIT_MAX" ]; then
    printf 'limit 상한은 %s 다(받은 값 %s). 더 큰 답이 필요하면 질문을 좁혀라 — actor/action 을\n' \
        "$LIMIT_MAX" "$limit" >&2
    printf '고정하거나 덤프를 따로 뜨는 쪽이다. 상한이 있는 이유는 LIMIT 이 인덱스 스캔을 묶는 것이\n' >&2
    printf '이 창구의 비용 전체를 받치고 있어서다.\n' >&2
    exit 64
fi

run_query "$mode" "$explain" "$v1" "$v2" "$limit"
