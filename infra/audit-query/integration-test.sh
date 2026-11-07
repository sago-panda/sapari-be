#!/bin/sh
# =============================================================================
# query-audit-log.sh 테스트 — argv/종료코드 층(DB 불필요)과 실제 PostgreSQL 층을 둘 다 덮는다.
#
# 형제 `replay-backfill/integration-test.sh` 가 적어 둔 교훈을 그대로 받는다:
# "--self-test(단위)가 못 잡는 층을 덮는다: 테이블·컬럼명, 쿼리 문법, 종료 코드 실제값.
#  지금까지 나온 결함이 전부 이 층에서 나왔다."
#
# 필요: docker(postgres:16), psql.  실행: ./infra/audit-query/integration-test.sh
# =============================================================================
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
TARGET="$HERE/query-audit-log.sh"

PASS=0
FAIL=0

# 기대 종료 코드와 stderr/stdout 패턴을 함께 본다 — 코드만 보면 "우연히 같은 코드" 를 못 가른다.
expect() {
    label="$1"; want_code="$2"; want_pattern="$3"; shift 3
    out="$("$@" 2>&1)" && code=0 || code=$?
    if [ "$code" != "$want_code" ]; then
        printf 'FAIL %s — 종료 코드 기대 %s, 실제 %s\n' "$label" "$want_code" "$code"
        printf '     출력: %s\n' "$(printf '%s' "$out" | head -3)"
        FAIL=$((FAIL + 1)); return
    fi
    if ! printf '%s' "$out" | grep -q -- "$want_pattern"; then
        printf 'FAIL %s — 출력에 %s 가 없다\n' "$label" "$want_pattern"
        printf '     출력: %s\n' "$(printf '%s' "$out" | head -3)"
        FAIL=$((FAIL + 1)); return
    fi
    printf 'ok   %s\n' "$label"
    PASS=$((PASS + 1))
}

# --- argv 층 (DB 불필요) ---------------------------------------------------

# self-test 가 "exit 0" 한 줄이어도 통과하는 칸을 만들지 않는다 — 몇 건을 실제로 검사했는지를
# 출력에서 본다. 그 수가 0 이면 분모 0 인 가드다.
expect '--self-test 가 검사 건수를 내고 통과한다' 0 'self-test 통과 7건' sh "$TARGET" --self-test

# 검증 실패는 **64**(EX_USAGE)다. 2 를 쓰지 않는 이유가 이 칸들의 분모 자체다 — psql 은 접속
# 실패에 2 를 내므로, 2 로 단언하면 이 칸들은 DB 가 없는 상태에서 "가드가 걸려서" 가 아니라
# "접속이 안 돼서" 통과한다(실측으로 그 상태였다). 64 는 psql 이 내지 않는 값이다.
expect '모드 없이 부르면 사용법을 내고 비정상 종료한다' 64 'Usage' sh "$TARGET"

# 사용법이 무엇을 물을 수 있는지 자기가 말해야 한다 — 운영자 창구의 기능이다. 모드 한 줄이
# 조용히 빠지는 것을 잡는다.
# ⚠️ 모드 **이름만** grep 하면 안 된다 — 실측: 비용 절의 `target 9.9ms` 가 그 자리를 메워
# 사용법에서 target 줄을 지워도 칸이 통과했다(분모 0). 그래서 인자 자리까지 묶어 단언한다.
usage_out="$(sh "$TARGET" 2>&1 || true)"   # 64 로 끝나므로 set -e 에 걸리지 않게 받는다
for pat in 'actor  <actor_id> <actor_type>' 'action <action>' 'target <target_id>' '--explain'; do
    if printf '%s' "$usage_out" | grep -qF -- "$pat"; then
        printf 'ok   사용법이 "%s" 를 적는다\n' "$pat"; PASS=$((PASS + 1))
    else
        printf 'FAIL 사용법에 "%s" 가 없다\n' "$pat"; FAIL=$((FAIL + 1))
    fi
done

# 접속 실패와 인자 오류가 **구분되는가**. 운영자가 "오타" 와 "DB 가 안 뜬다" 를 가르는 축이고,
# 위 칸들의 분모를 지키는 축이기도 하다.
out="$(PGHOST=127.0.0.1 PGPORT=1 PGDATABASE=x PGUSER=x sh "$TARGET" action CHAT_KICK_USER 2>&1)" && code=0 || code=$?
if [ "$code" != 64 ] && [ "$code" != 0 ]; then
    printf 'ok   접속 실패는 64 가 아니다(실제 %s) — 인자 오류와 구분된다\n' "$code"; PASS=$((PASS + 1))
else
    printf 'FAIL 접속 실패 종료 코드 %s — 인자 오류(64)와 구분되지 않는다\n' "$code"; FAIL=$((FAIL + 1))
fi

# actor 질의는 actor_type 을 함께 고정해야 인덱스 (actor_id, actor_type, created_at) 를 정렬까지
# 쓴다. 실측(40만 행, postgres:16): 둘 다 고정 0.088ms(Index Scan Backward) vs actor_type 누락
# 13.0ms(Sort) = 148배. 그래서 "선택 인자" 가 아니라 거부 사유다.
expect 'actor 질의에 actor_type 이 없으면 거부하고 이유를 적는다' 64 '148' \
    sh "$TARGET" actor 11111111-1111-1111-1111-111111111111

# limit 은 **셸 문자열로 SQL 에 끼워지는 유일한 값**이다(나머지는 psql 변수 :'v1'). 그래서 이
# 검증은 사용성이 아니라 인젝션 가드다 — 숫자가 아닌 것이 통과하면 LIMIT 자리에 임의 SQL 이 간다.
expect '숫자가 아닌 limit 을 거부한다 (SQL 에 직접 끼워지는 유일한 값)' 64 'limit' \
    sh "$TARGET" action CHAT_KICK_USER '1; DROP TABLE live_schema.audit_logs'
expect '0 이하 limit 을 거부한다' 64 'limit' sh "$TARGET" action CHAT_KICK_USER 0

# --- 실제 PostgreSQL 층 -----------------------------------------------------
#
# 선례가 테스트에서 배운 안전장치: libpq 는 **서비스 파일이 환경변수보다 우선**한다. 운영 절차가
# PGSERVICE 를 권하므로 그 셸에서 이걸 돌리면 컨테이너가 아니라 운영 DB 에 붙는다. 먼저 지운다.
unset PGSERVICE PGSERVICEFILE PGOPTIONS PGHOSTADDR PGSSLMODE PGPASSWORD

command -v docker >/dev/null 2>&1 || { printf '\nSKIP DB 층 — docker 없음\n'; printf '통과 %s · 실패 %s\n' "$PASS" "$FAIL"; [ "$FAIL" -eq 0 ]; exit $?; }

PG_NAME="spr175-pg-it-$$"
PG_PORT="${PG_PORT:-55475}"
WORK="$(mktemp -d)"
cleanup() { docker rm -f "$PG_NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

export PGHOST=127.0.0.1 PGPORT="$PG_PORT" PGUSER=postgres PGDATABASE=audit_test
PGPASSFILE="$WORK/pgpass"; export PGPASSFILE
printf '127.0.0.1:%s:audit_test:postgres:test\n' "$PG_PORT" > "$PGPASSFILE"
chmod 600 "$PGPASSFILE"

docker run -d --rm --name "$PG_NAME" -e POSTGRES_PASSWORD=test -e POSTGRES_DB=audit_test \
    -p "$PG_PORT":5432 postgres:16 >/dev/null
i=0
until docker exec "$PG_NAME" pg_isready -q 2>/dev/null; do
    i=$((i + 1)); [ "$i" -gt 60 ] && { printf 'FAIL postgres 기동 실패\n'; exit 1; }
    sleep 1
done

# ⭐ 정본 마이그레이션을 **파일 전체**로 적용한다 — 형제 `replay-backfill/integration-test.sh` 와
# 같은 방식이다. 스키마를 베껴 두거나 한 테이블만 뽑아 두면 실제 컬럼이 바뀌어도 이 테스트는
# 초록이고, 그게 선례가 "결함이 전부 이 층" 이라고 적은 그 층이다. live 의 audit_logs 는
# 테이블과 인덱스 둘 다 이 한 파일에 있다(V1__init_live.sql:289-302).
q() { psql -X -tAq -v ON_ERROR_STOP=1 -c "$1"; }

# 마이그레이션 전에 "내 컨테이너인지" 확인한다 — 형제가 적어 둔 이유와 같다: 남의 DB 에
# CREATE TABLE 을 쏘지 않기 위해서다. 이 스크립트는 PGHOST/PGPORT 를 export 하고 DDL 을 돌린다.
[ "$(q "SELECT current_database()")" = audit_test ] \
    || { printf 'FAIL 대상 DB 가 audit_test 가 아니다\n'; exit 1; }
[ "$(q "SELECT count(*) FROM information_schema.tables WHERE table_schema='live_schema'")" = 0 ] \
    || { printf 'FAIL live_schema 가 이미 존재한다 — 테스트 전용 DB 가 아니다. 중단한다\n'; exit 1; }

psql -X -q -v ON_ERROR_STOP=1 -f "$HERE/../../db/migration/live/V1__init_live.sql"

A1=11111111-1111-1111-1111-111111111111
A2=22222222-2222-2222-2222-222222222222
T1=44444444-4444-4444-4444-444444444444
psql -X -q -v ON_ERROR_STOP=1 <<SQL
INSERT INTO live_schema.audit_logs (actor_id, actor_type, action, target_id, target_type, detail, created_at) VALUES
  ('$A1','ADMIN','CHAT_KICK_USER','$T1','USER','{"roomId":"r1"}','2026-11-01T00:00:00Z'),
  ('$A1','ADMIN','CHAT_RELEASE_BAN','$T1','USER','{"banExisted":"true"}','2026-11-03T00:00:00Z'),
  ('$A1','SELLER','CHAT_KICK_USER','$T1','USER','{"roomId":"r9"}','2026-11-02T00:00:00Z'),
  ('$A2','ADMIN','CHAT_ADD_FILTER_WORD',NULL,'FILTER_WORD','{"word":"ㅂㅅ"}','2026-11-04T00:00:00Z');
SQL

# 4) actor 질의 — 테이블·컬럼명·SQL 문법, 그리고 최신순.
#    A1/ADMIN 은 2건이고 최신이 RELEASE_BAN(11-03)이다. SELLER 행은 섞이지 않아야 한다.
expect 'actor 질의가 그 actor_type 의 행만 최신순으로 낸다' 0 'CHAT_RELEASE_BAN' sh "$TARGET" actor "$A1" ADMIN
out="$(sh "$TARGET" actor "$A1" ADMIN 2>&1)"
if [ "$(printf '%s' "$out" | grep -c 'CHAT_')" = "2" ] \
   && [ "$(printf '%s' "$out" | grep -n 'CHAT_RELEASE_BAN' | cut -d: -f1)" \
        -lt "$(printf '%s' "$out" | grep -n 'CHAT_KICK_USER' | cut -d: -f1)" ]; then
    printf 'ok   actor 질의가 2건이고 최신이 먼저다(SELLER 행 미포함)\n'; PASS=$((PASS + 1))
else
    printf 'FAIL actor 질의 건수·순서\n     출력: %s\n' "$(printf '%s' "$out" | tr '\n' '|')"; FAIL=$((FAIL + 1))
fi

# 5) action 질의 — 같은 action 을 actor 와 무관하게 모은다(A1 의 ADMIN·SELLER 둘).
out="$(sh "$TARGET" action CHAT_KICK_USER 2>&1)"
if [ "$(printf '%s' "$out" | grep -c 'CHAT_KICK_USER')" = "2" ]; then
    printf 'ok   action 질의가 actor_type 과 무관하게 2건을 낸다\n'; PASS=$((PASS + 1))
else
    printf 'FAIL action 질의 건수\n     출력: %s\n' "$(printf '%s' "$out" | tr '\n' '|')"; FAIL=$((FAIL + 1))
fi

# 6) target 질의 — 인덱스가 없는 축이다. 되긴 해야 한다(계획은 단언하지 않는다).
out="$(sh "$TARGET" target "$T1" 2>&1)"
if [ "$(printf '%s' "$out" | grep -c 'CHAT_')" = "3" ]; then
    printf 'ok   target 질의가 그 대상의 3건을 낸다\n'; PASS=$((PASS + 1))
else
    printf 'FAIL target 질의 건수\n     출력: %s\n' "$(printf '%s' "$out" | tr '\n' '|')"; FAIL=$((FAIL + 1))
fi

# 7) 0건도 성공이다 — "그 조치가 없었다" 가 답이다. 종료 코드로 "없음" 과 "오류" 를 가른다.
expect '0건 조회도 종료 0 이고 0건임을 적는다' 0 '0건' sh "$TARGET" action CHAT_NO_SUCH_ACTION

# 8) 계획 가드(Seam 3) — 실측을 회귀 가드로 바꾼다. 인덱스가 사라지거나 조건절이 인덱스와
#    안 맞게 바뀌면 여기서 깨진다. 절대 시간은 보지 않는다(기계마다 다르고 모양은 아니다).
expect '--explain actor 가 Index Scan Backward 를 탄다' 0 'Index Scan Backward' \
    sh "$TARGET" --explain actor "$A1" ADMIN
expect '--explain action 이 Index Scan Backward 를 탄다' 0 'Index Scan Backward' \
    sh "$TARGET" --explain action CHAT_KICK_USER

# 9) 상한에서 잘렸는지를 숨기지 않는다. A1/ADMIN 은 2건이므로 limit 1 이면 잘린 것이고,
#    limit 50 이면 안 잘린 것이다. 둘을 같이 봐야 "항상 잘렸다고 말하는" 구현이 통과하지 못한다.
out="$(sh "$TARGET" actor "$A1" ADMIN 1 2>&1)"
if printf '%s' "$out" | grep -q '잘렸다'; then
    printf 'ok   상한에서 잘렸음을 적는다\n'; PASS=$((PASS + 1))
else
    printf 'FAIL 상한 1 인데 잘림을 안 적는다\n     출력: %s\n' "$(printf '%s' "$out" | tr '\n' '|')"; FAIL=$((FAIL + 1))
fi
out="$(sh "$TARGET" actor "$A1" ADMIN 50 2>&1)"
if printf '%s' "$out" | grep -q '^2건$' && ! printf '%s' "$out" | grep -q '잘렸다'; then
    printf 'ok   상한 아래에서는 정확한 건수만 적는다\n'; PASS=$((PASS + 1))
else
    printf 'FAIL 상한 50 에서 2건이 아니거나 잘림을 적었다\n     출력: %s\n' "$(printf '%s' "$out" | tr '\n' '|')"; FAIL=$((FAIL + 1))
fi

printf '\n통과 %s · 실패 %s\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
