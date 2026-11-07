#!/bin/sh
# =============================================================================
# 감사 기록 조회 — live_schema.audit_logs 에 사람이 물어보는 길.
#
# 왜 필요한가: SPR-170 이 관리자 조치 다섯을 그 표에 쓰기 시작했는데 **읽는 코드가 0건**이었다.
# modules/chat/AGENTS.md 가 그 상태를 결함으로 규정한다 — "a real audit needs a store **and** a way
# for a person to query it. chat_kick_log shows the difference — it is read by escalation, but
# nothing lets a human ask it anything, which is what 'a store is not an audit' looks like."
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
#   2  사용법 오류(모드 누락·인자 누락·알 수 없는 모드)
#   12 self-test 실패
# =============================================================================
set -eu

usage() {
    cat >&2 <<'USAGE'
Usage: query-audit-log.sh <mode> [args...]

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
                return 2
            fi
            ;;
        action|target)
            # 이 둘은 값 하나다. action 은 (action, created_at) 인덱스를 타고, target 은
            # 인덱스가 없어 전체 스캔이다 — 실측 40만 행에서 9.9ms 이고 표 크기에 비례한다.
            # 되는 것과 싼 것은 다르므로, 막지 않고 사용법에 비용을 적는다.
            if [ -z "${2:-}" ]; then
                printf '%s 질의는 값 하나를 받는다: query-audit-log.sh %s <값> [limit]\n' "$1" "$1" >&2
                return 2
            fi
            ;;
        '') usage; return 2 ;;
        *) usage; return 2 ;;
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

    expect_rc 2 '모드 없음'                       ''
    expect_rc 2 '알 수 없는 모드'                  nonsense
    expect_rc 2 'actor — actor_type 누락'          actor 11111111-1111-1111-1111-111111111111
    expect_rc 0 'actor — 둘 다 있으면 통과'        actor 11111111-1111-1111-1111-111111111111 ADMIN

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
        psql -q -v ON_ERROR_STOP=1 -v v1="$v1" -v v2="$v2" -P pager=off <<SQL
EXPLAIN (ANALYZE, BUFFERS) $select;
SQL
        return $?
    fi

    psql -q -v ON_ERROR_STOP=1 -v v1="$v1" -v v2="$v2" -P pager=off -P footer=off <<SQL || return $?
$select;
SQL

    # 건수를 따로 센다. psql 의 "(N rows)" 꼬리를 파싱하는 쪽이 왕복 하나를 아끼지만 그 문구는
    # 우리 계약이 아니다(로케일·버전·pset 에 따라 바뀐다). 둘 다 같은 WHERE 를 쓰므로 위
    # `--explain` 가드가 고정하는 계획 모양이 이쪽에도 그대로 적용된다.
    n="$(psql -tAq -v ON_ERROR_STOP=1 -v v1="$v1" -v v2="$v2" <<SQL
SELECT count(*) FROM ($select) s;
SQL
    )" || return $?
    printf '%s건\n' "$n"
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

case "$limit" in
    ''|*[!0-9]*) printf 'limit 은 양의 정수다: %s\n' "$limit" >&2; exit 2 ;;
esac
[ "$limit" -gt 0 ] || { printf 'limit 은 1 이상이다: %s\n' "$limit" >&2; exit 2; }

run_query "$mode" "$explain" "$v1" "$v2" "$limit"
