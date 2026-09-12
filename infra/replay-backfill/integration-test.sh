#!/bin/sh
# =============================================================================
# backfill-archive-url.sh 통합 테스트 — 실제 PostgreSQL + 실제 HTTP 서버로 돌린다.
#
# --self-test(단위)가 못 잡는 층을 덮는다: 테이블·컬럼명, 쿼리 문법, 종료 코드 실제값,
# 커서 왕복, 루프가 끝까지 도는지. 지금까지 나온 결함이 전부 이 층에서 나왔다.
#
# 필요: docker(postgres:16), psql, python3.  실행: ./infra/replay-backfill/integration-test.sh
# =============================================================================
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../.."
TARGET="$HERE/backfill-archive-url.sh"
PG_NAME="spr148-pg-it-$$"
PG_PORT="${PG_PORT:-55433}"
CDN_PORT="${CDN_PORT:-58080}"
WORK="$(mktemp -d)"
CDN_ROOT="$WORK/cdn"
CDN_PID=""

# libpq 는 서비스 파일이 환경변수보다 우선한다. 백필 운영 절차가 PGSERVICE 를 권장하므로,
# 그 셸에서 이 테스트를 돌리면 컨테이너가 아니라 운영 DB 에 붙는다. 반드시 먼저 지운다.
unset PGSERVICE PGSERVICEFILE PGOPTIONS PGHOSTADDR PGSSLMODE PGPASSWORD
export PGHOST=127.0.0.1 PGPORT="$PG_PORT" PGUSER=postgres PGDATABASE=sapari_test
# 비밀번호도 환경이 아니라 0600 파일로 넘긴다(형제 스크립트의 규칙과 맞춘다).
PGPASSFILE="$WORK/pgpass"; export PGPASSFILE
printf '127.0.0.1:%s:sapari_test:postgres:test\n' "$PG_PORT" > "$PGPASSFILE"
chmod 600 "$PGPASSFILE"
export CDN_BASE_URL="http://127.0.0.1:$CDN_PORT"

pass=0; fail=0
ok()   { echo "  ok   $1"; pass=$((pass + 1)); }
bad()  { echo "  FAIL $1"; [ -z "${2:-}" ] || echo "       $2"; fail=$((fail + 1)); }
q()    { psql -X -At -v ON_ERROR_STOP=1 -c "$1"; }

cleanup() {
    [ -z "$CDN_PID" ] || kill "$CDN_PID" 2>/dev/null || true
    [ -z "$CDN_PID" ] || wait "$CDN_PID" 2>/dev/null || true
    docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

# --- 기동 -----------------------------------------------------------------
echo "== 환경 기동"
# 루프백에만 바인딩한다(0.0.0.0 이면 같은 LAN 에서 postgres/test 로 붙을 수 있다). --rm 으로 잔존도 줄인다.
docker run -d --rm --name "$PG_NAME" -e POSTGRES_PASSWORD=test -e POSTGRES_DB=sapari_test \
    -p "127.0.0.1:$PG_PORT:5432" postgres:16 >/dev/null
# pg_isready 는 initdb 중에도 통과한다(실제로 겪었다). 진짜 연결이 될 때까지 기다린다.
ready=0
for _ in $(seq 1 60); do
    psql -X -q -c 'select 1' >/dev/null 2>&1 && { ready=1; break; }
    sleep 1
done
[ "$ready" -eq 1 ] || { echo "  PostgreSQL 기동 실패"; exit 1; }
# 마이그레이션 전에 "내 컨테이너인지" 확인한다. 남의 DB 에 CREATE TABLE 을 쏘지 않기 위해서다.
[ "$(q "SELECT current_database()")" = sapari_test ] || { echo "  대상 DB 가 sapari_test 가 아니다"; exit 1; }
[ "$(q "SELECT count(*) FROM information_schema.tables WHERE table_schema='live_schema'")" = 0 ] \
    || { echo "  live_schema 가 이미 존재한다 — 테스트 전용 DB 가 아니다. 중단한다."; exit 1; }
psql -X -q -v ON_ERROR_STOP=1 -f "$ROOT/db/migration/live/V1__init_live.sql"
echo "  PostgreSQL + 실제 live 스키마 적용"

mkdir -p "$CDN_ROOT"
# 서브셸로 감싸면 $! 가 서브셸 PID 라 cleanup 이 python 을 못 죽이고, 다음 실행이 그 포트를
# 낡은 루트로 물고 있는 서버에 붙어 전부 404 가 된다(실제로 한 번 겪었다). --directory 로 직접 띄운다.
python3 - "$CDN_PORT" "$CDN_ROOT" >/dev/null 2>&1 <<'SERVER' &
import functools, http.server, pathlib, sys
root = pathlib.Path(sys.argv[2])
class H(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        mode = (root / '.mode').read_text() if (root / '.mode').exists() else ''
        redirect = ((mode == 'root-redirect' and self.path == '/') or
                    (mode == 'object-redirect' and self.path.endswith('.m3u8')) or
                    (mode == 'segment-redirect' and self.path.endswith('.ts')))
        if redirect:
            self.send_response(302); self.send_header('Location', '/elsewhere'); self.end_headers(); return
        if mode == 'large-segment' and self.path.endswith('.ts'):
            self.send_response(200); self.send_header('Content-Length', '1048577'); self.end_headers(); return
        if mode == 'soft404' and self.path.endswith('.ts') and not pathlib.Path(self.translate_path(self.path)).exists():
            self.send_response(200); self.end_headers(); self.wfile.write(b'<html>not found</html>'); return
        super().do_GET()
    def log_message(*args): pass
http.server.HTTPServer(('127.0.0.1', int(sys.argv[1])), functools.partial(H, directory=str(root))).serve_forever()
SERVER
CDN_PID=$!
# 루트 목록만 보면 "낡은 루트를 문 남의 서버" 를 구분하지 못한다. 우리가 방금 놓은 파일로 확인한다.
printf 'marker\n' > "$CDN_ROOT/.probe"
cdn_ready=0
for _ in $(seq 1 20); do
    [ "$(curl -fsS "$CDN_BASE_URL/.probe" 2>/dev/null)" = marker ] && { cdn_ready=1; break; }
    sleep 0.3
done
[ "$cdn_ready" -eq 1 ] || { echo "  가짜 CDN 기동 실패(포트 $CDN_PORT 을 다른 서버가 쓰고 있을 수 있다)"; exit 1; }
echo "  가짜 CDN: $CDN_BASE_URL"

# --- 픽스처 ---------------------------------------------------------------
# 정상 아카이브(EVENT + ENDLIST + 세그먼트 전수)
make_archive() { # <roomId> <세그먼트 수> [missing]
    dir="$CDN_ROOT/live/$1/720p"; mkdir -p "$dir"
    {
        echo "#EXTM3U"; echo "#EXT-X-PLAYLIST-TYPE:EVENT"; echo "#EXT-X-TARGETDURATION:2"
        echo "#EXT-X-MEDIA-SEQUENCE:0"
        # #EXTINF 는 검증기가 지나는 분기를 늘리지 않지만(#으로 시작하는 줄은 전부 건너뛴다),
        # 이 픽스처가 리포지토리에서 "실제 산출물이 어떻게 생겼는가"를 기록한 유일한 자리라 형태를 맞춘다.
        i=0; while [ "$i" -lt "$2" ]; do printf '#EXTINF:2.0,\nsegment_%s.ts\n' "$i"; i=$((i + 1)); done
        echo "#EXT-X-ENDLIST"
    } > "$dir/playlist.m3u8"
    i=0; while [ "$i" -lt "$2" ]; do
        if [ "${3:-}" != missing ] || [ "$i" -ne 0 ]; then
            { for packet in 1 2 3; do printf '\107'; dd if=/dev/zero bs=187 count=1 2>/dev/null; done; } > "$dir/segment_$i.ts"
        fi
        i=$((i + 1))
    done
}

R_LEGACY=11111111-1111-1111-1111-111111111111   # hls_archive_url = hls_url (레거시 복사)
R_NULL=22222222-2222-2222-2222-222222222222     # hls_archive_url IS NULL (배포 경계)
R_MASTER=33333333-3333-3333-3333-333333333333   # hls_url 이 archive-master.m3u8 (-master 분기)
R_BROKEN=44444444-4444-4444-4444-444444444444   # 세그먼트 결손 → 영구 검증 실패
R_LIVE=55555555-5555-5555-5555-555555555555     # 배포 이전 시작·미종료

make_archive "$R_LEGACY" 3
make_archive "$R_NULL" 2
make_archive "$R_MASTER" 2
make_archive "$R_BROKEN" 2 missing

seed() { # <id> <hls_url> <archive> <status> <ended_at> <started_at>
    q "INSERT INTO live_schema.live_rooms
         (id, seller_id, title, seller_nickname, status, hls_url, hls_archive_url, ended_at, started_at,
          created_at, updated_at)
       VALUES ('$1', gen_random_uuid(), 't', 'n', '$4', $2, $3, $5, $6, now(), now())" >/dev/null
}
B="$CDN_BASE_URL/live"
seed "$R_LEGACY" "'$B/$R_LEGACY/720p/index.m3u8'" "'$B/$R_LEGACY/720p/index.m3u8'" ENDED "'2026-09-10T10:00:00.250Z'" "'2026-09-10T09:00:00Z'"
seed "$R_NULL"   "'$B/$R_NULL/master.m3u8'"       "NULL"                            ENDED "'2026-09-10T11:00:00.500Z'" "'2026-09-10T10:00:00Z'"
seed "$R_BROKEN" "'$B/$R_BROKEN/720p/index.m3u8'" "NULL"                            ENDED "'2026-09-10T09:00:00.750Z'" "'2026-09-10T08:00:00Z'"
seed "$R_MASTER" "'$B/$R_MASTER/archive-master.m3u8'" "NULL"                         ENDED "'2026-09-10T12:00:00.125Z'" "'2026-09-10T11:00:00Z'"
seed "$R_LIVE"   "'$B/$R_LIVE/720p/index.m3u8'"   "NULL"                            LIVE  "NULL"                        "'2026-09-10T23:00:00Z'"
# 보고 전용 모집단 — 도구가 "복구하지 않고 알리기만" 하는 대상이다. 경고가 실제로 나오는지 확인한다.
R_NOURL=66666666-6666-6666-6666-666666666666    # hls_url 조차 없음
R_NOEND=77777777-7777-7777-7777-777777777777    # ended_at 이 비어 커서 기준을 못 만듦
R_SUSP=88888888-8888-8888-8888-888888888888     # 배포 이전 시작·정지 상태
seed "$R_NOURL" "NULL" "NULL" ENDED "'2026-09-10T08:00:00Z'" "'2026-09-10T07:00:00Z'"
seed "$R_NOEND" "'$B/$R_NOEND/720p/index.m3u8'" "NULL" ENDED "NULL" "'2026-09-10T07:00:00Z'"
seed "$R_SUSP"  "'$B/$R_SUSP/720p/index.m3u8'"  "NULL" SUSPENDED "NULL" "'2026-09-10T06:00:00Z'"
echo "  시드 8건 (레거시 복사 / NULL / -master / 세그먼트 결손 / 미종료 / URL없음 / ended_at없음 / 정지)"

run() { # 나머지 인자는 환경변수. stdout=SQL, stderr=로그, 종료 코드 반환
    env "$@" DEPLOY_TS=2026-09-11T00:00:00Z "$TARGET" > "$WORK/out.sql" 2> "$WORK/err.log"
}

# 부정 어서션("X 가 없다")은 회차가 죽어도 성립한다. 실제로 그렇게 통과한 적이 있어 선행 조건을 둔다.
ran_clean() {
    grep -q 'preflight ok' "$WORK/err.log" || { bad "$1 — 회차가 시작조차 못 했다" "$(tail -2 "$WORK/err.log")"; return 1; }
    grep -q 'unbound variable' "$WORK/err.log" && { bad "$1 — 회차가 중간에 죽었다" "$(grep unbound "$WORK/err.log" | head -1)"; return 1; }
    # 완주 마커. 스크립트는 루프를 끝까지 돈 뒤에만 이 줄을 찍는다 —
    # preflight 통과와 특정 실패 문자열 부재만 보면 psql 오류·set -e 중단을 통과시킨다.
    grep -q '검증 통과 .*건 / 건너뜀' "$WORK/err.log" || { bad "$1 — 회차가 완주하지 못했다" "$(tail -2 "$WORK/err.log")"; return 1; }
    return 0
}

# --- 검증 -----------------------------------------------------------------
echo "== 제거된 운영 seam: 파일 절삭·장기 대기를 입력 단계에서 거부"
printf 'keep\n' > "$WORK/precious"
set +e; run BACKFILL_PAUSE_BETWEEN_COUNTS=999999 BACKFILL_PAUSE_MARKER="$WORK/precious"; seam_rc=$?; set -e
if [ "$seam_rc" = 1 ] && grep -q '더 이상 지원하지 않는다' "$WORK/err.log"; then
    [ "$(cat "$WORK/precious")" = keep ] && [ ! -s "$WORK/out.sql" ] \
        && ok "옛 seam 거부: 파일·SQL 무변경" || bad "seam이 파일 또는 SQL에 영향을 줬다"
else bad "옛 seam이 입력 단계에서 거부되지 않았다"; fi

echo "== 루트 정책과 객체 정책은 독립"
printf root-redirect > "$CDN_ROOT/.mode"
set +e; run BATCH_SIZE=50; root_rc=$?; set -e
if ran_clean "루트만 리다이렉트"; then
    [ "$root_rc" = 11 ] && grep -q "$R_LEGACY" "$WORK/out.sql" \
        && ok "루트 302여도 정상 객체는 SQL 생성" || bad "루트 정책이 정상 객체를 차단"
fi
for mode in object-redirect segment-redirect large-segment; do
    printf '%s' "$mode" > "$CDN_ROOT/.mode"
    set +e; run BATCH_SIZE=50; redirect_rc=$?; set -e
    if [ "$redirect_rc" = 14 ] && grep -q '객체 전달 구성 오류' "$WORK/err.log"; then
        grep -q '^UPDATE' "$WORK/out.sql" && bad "$mode 객체에 SQL 생성" || ok "$mode 구성 오류 14, SQL 없음"
        grep -q 'AFTER_ENDED_AT=' "$WORK/err.log" && bad "$mode 커서 전진 권고" || ok "$mode 커서 고정"
    else bad "$mode 구성 오류 미검출" "$(tail -3 "$WORK/err.log")"; fi
done
printf soft404 > "$CDN_ROOT/.mode"
set +e; run BATCH_SIZE=50; soft_rc=$?; set -e
if ran_clean "soft404"; then
    [ "$soft_rc" = 11 ] && grep -q "$R_LEGACY" "$WORK/out.sql" && ! grep -q "$R_BROKEN" "$WORK/out.sql" \
        && ok "정상 TS는 통과, 200 HTML 결손 세그먼트는 제외" || bad "soft404 분리 실패"
fi
printf '' > "$CDN_ROOT/.mode"

echo "== 1회차: 미종료 방이 있으므로 재실행 필요"
set +e; run BATCH_SIZE=50; rc=$?; set -e
[ "$rc" = 11 ] && ok "1회차 종료 코드 11(적용 대기)" \
    || bad "1회차 종료 코드가 11 이 아니다: $rc" "$(tail -3 "$WORK/err.log")"
ran_clean "1회차" || true
grep -q "UPDATE live_schema.live_rooms" "$WORK/out.sql" \
    && ok "검증 통과 행의 UPDATE 생성" || bad "UPDATE 가 생성되지 않았다" "$(tail -5 "$WORK/err.log")"
grep -q "$R_LEGACY" "$WORK/out.sql" && ok "레거시 복사 행이 대상에 포함" || bad "레거시 복사 행 누락"
grep -q "$R_NULL" "$WORK/out.sql" && ok "NULL 아카이브 행이 대상에 포함" || bad "NULL 아카이브 행 누락"
if ran_clean "결손 행 판정"; then
    grep -q "$R_BROKEN" "$WORK/out.sql" && bad "세그먼트 결손 행이 대상에 들어갔다" || ok "세그먼트 결손 행은 제외"
fi
if ran_clean "미지원 URL 판정"; then
grep -q "unbound" "$WORK/err.log" && bad "루프가 중간에 죽었다(set -u)" "$(grep unbound "$WORK/err.log" | head -1)" \
    || ok "-master 행을 만나도 루프가 끝까지 돈다"
grep -q "$R_MASTER" "$WORK/out.sql" && bad "-master 행이 대상에 들어갔다" || ok "-master 행은 유도 대상이 아니다"
fi

echo "== 보고 전용 모집단: 복구하지 않되 조용히 사라지지도 않는다"
grep -q '라이브 URL 조차 없는 ENDED 행 1건' "$WORK/err.log" && ok "hls_url 없는 행을 보고한다" \
    || bad "hls_url 없는 행이 보고되지 않았다" "$(grep -o '라이브 URL[^/]*' "$WORK/err.log" | head -1)"
grep -q 'ended_at 이 없는 후보 1건' "$WORK/err.log" && ok "ended_at 없는 행을 보고한다" \
    || bad "ended_at 없는 행이 보고되지 않았다"
grep -q '배포 이전 시작·정지 상태 1건' "$WORK/err.log" && ok "정지 상태 행을 보고한다" \
    || bad "정지 상태 행이 보고되지 않았다"
echo "== 적용 후 재실행: 고친 행이 후보에서 빠진다"
psql -X -q -v ON_ERROR_STOP=1 -f "$WORK/out.sql" >/dev/null
applied="$(q "SELECT hls_archive_url FROM live_schema.live_rooms WHERE id='$R_LEGACY'")"
case "$applied" in
    */720p/playlist.m3u8) ok "레거시 행이 EVENT 플레이리스트로 갱신됨" ;;
    *) bad "갱신 값이 기대와 다르다" "$applied" ;;
esac
set +e; run BATCH_SIZE=50; rc2=$?; set -e
if ran_clean "적용 후 재실행"; then
    grep -q "$R_LEGACY" "$WORK/out.sql" && bad "이미 고친 행이 또 후보가 됐다" || ok "멱등 — 적용된 행은 재후보 아님"
fi
# 아직 미종료 방이 남아 있으므로 계약은 10(재실행 필요)이다.
[ "$rc2" = 10 ] && ok "적용 후 재실행은 10(미종료 방 잔존)" || bad "기대 10, 실제 $rc2" "$(tail -2 "$WORK/err.log")"

echo "== WHERE 가드: 생성 후 행이 바뀌면 그 UPDATE 는 무시된다"
R_GUARD=99999999-9999-9999-9999-999999999999
make_archive "$R_GUARD" 2
seed "$R_GUARD" "'$B/$R_GUARD/720p/index.m3u8'" "'$B/$R_GUARD/720p/index.m3u8'" ENDED "'2026-09-10T13:00:00.400Z'" "'2026-09-10T12:00:00Z'"
set +e; run BATCH_SIZE=50; set -e
if grep -q "$R_GUARD" "$WORK/out.sql"; then
    q "UPDATE live_schema.live_rooms SET hls_url='http://other/new-index.m3u8', hls_archive_url=NULL WHERE id='$R_GUARD'" >/dev/null
    psql -X -q -v ON_ERROR_STOP=1 -f "$WORK/out.sql" >/dev/null
    [ -z "$(q "SELECT hls_archive_url FROM live_schema.live_rooms WHERE id='$R_GUARD'")" ] \
        && ok "검증 뒤 live URL이 달라지면 적용하지 않는다" || bad "바뀐 live URL에 과거 검증 결과 적용"
    q "UPDATE live_schema.live_rooms SET hls_url='$B/$R_GUARD/720p/index.m3u8' WHERE id='$R_GUARD'" >/dev/null
    # SQL 을 만든 뒤 누군가 그 행을 먼저 고쳤다고 가정한다. 뒤늦은 UPDATE 는 적용되면 안 된다.
    q "UPDATE live_schema.live_rooms SET hls_archive_url='http://other/keep.m3u8' WHERE id='$R_GUARD'" >/dev/null
    psql -X -q -v ON_ERROR_STOP=1 -f "$WORK/out.sql" >/dev/null
    [ "$(q "SELECT hls_archive_url FROM live_schema.live_rooms WHERE id='$R_GUARD'")" = 'http://other/keep.m3u8' ] \
        && ok "이미 바뀐 행은 덮어쓰지 않는다(WHERE 가드: 아카이브 조건)" || bad "WHERE 가드가 뒤늦은 UPDATE 를 막지 못했다"
    # 가드는 두 조건이다. status 조건이 조용히 빠져도 위 단언만으로는 드러나지 않는다.
    q "UPDATE live_schema.live_rooms SET hls_archive_url=NULL, status='SUSPENDED' WHERE id='$R_GUARD'" >/dev/null
    psql -X -q -v ON_ERROR_STOP=1 -f "$WORK/out.sql" >/dev/null
    [ -z "$(q "SELECT hls_archive_url FROM live_schema.live_rooms WHERE id='$R_GUARD'")" ] \
        && ok "ENDED 가 아닌 행은 덮어쓰지 않는다(WHERE 가드: status 조건)" \
        || bad "status 조건이 뒤늦은 UPDATE 를 막지 못했다"
    # 가드 검증이 끝났으니 이 행을 복구 완료 상태로 되돌린다 — 안 그러면 후보로 남아 뒤 시나리오의 수가 밀린다.
    q "UPDATE live_schema.live_rooms SET status='ENDED', hls_archive_url='$B/$R_GUARD/720p/playlist.m3u8'
       WHERE id='$R_GUARD'" >/dev/null
else
    bad "가드 테스트 준비 실패 — $R_GUARD 이 후보에 없다" "$(tail -2 "$WORK/err.log")"
fi

echo "== 배포 경계: 미종료 방을 종료시키면 완료 조건에 근접"
q "UPDATE live_schema.live_rooms SET status='ENDED', ended_at=now(), hls_archive_url='$B/$R_LIVE/720p/playlist.m3u8' WHERE id='$R_LIVE'" >/dev/null
set +e; run BATCH_SIZE=50; rc3=$?; set -e
grep -q "배포 경계 미종료 방 0건" "$WORK/err.log" && ok "미종료 집계가 0 으로 내려감" \
    || bad "미종료 집계가 0 이 아니다" "$(grep -o '미종료 방 [0-9]*건' "$WORK/err.log" | tail -1)"
[ "$rc3" = 10 ] && ok "미복구 후보가 남아 재실행(10)" || bad "기대 10, 실제 $rc3" "$(tail -2 "$WORK/err.log")"

echo "== 커서: 복구 불가 행만 남은 상태에서 전진하는가"
# 남은 후보를 BROKEN 하나로 만든다(나머지는 위에서 적용 완료).
set +e; run BATCH_SIZE=50; set -e
# 스크립트의 count_candidates 와 같은 조건이어야 한다(ended_at IS NOT NULL 포함).
remaining="$(q "SELECT count(*) FROM live_schema.live_rooms
                WHERE status='ENDED' AND hls_url IS NOT NULL AND ended_at IS NOT NULL
                  AND (hls_archive_url IS NULL OR hls_archive_url = hls_url)")"
# 복구 불가 2건이 남는다: 세그먼트 결손 행과 -master 행(유도 대상이 아니라 항상 SKIP).
[ "$remaining" = 2 ] && ok "남은 후보가 복구 불가 2건" || bad "남은 후보 수가 예상과 다르다" "remaining=$remaining"

set +e; run BATCH_SIZE=50; rc4=$?; set -e
[ "$rc4" = 10 ] && ok "진전 없는 회차는 재실행(10)" || bad "기대 10, 실제 $rc4" "$(tail -2 "$WORK/err.log")"
cursor="$(sed -n 's/.*AFTER_ENDED_AT=\([^ ]*\) .*/\1/p' "$WORK/err.log" | tail -1)"
case "$cursor" in
    *.*Z) ok "커서에 마이크로초가 실린다 ($cursor)" ;;
    "")   bad "커서 안내가 나오지 않았다" "$(tail -3 "$WORK/err.log")" ;;
    *)    bad "커서가 초 단위로 절삭됐다 — 막힌 행을 넘지 못한다" "cursor=$cursor" ;;
esac

if [ -n "$cursor" ]; then
    set +e; run BATCH_SIZE=50 AFTER_ENDED_AT="$cursor"; rc5=$?; set -e
    if ! grep -q 'preflight ok' "$WORK/err.log"; then
        bad "커서를 넘기니 회차가 시작조차 못 했다" "cursor=$cursor / $(tail -2 "$WORK/err.log")"
    elif grep -q "$R_BROKEN" "$WORK/err.log"; then
        bad "커서를 넘겨도 같은 행이 다시 후보가 된다" "cursor=$cursor"
    elif [ "$rc5" = 13 ]; then
        ok "커서 이후 보류(13) — 완료(0)와 구분된다"
        grep -q '커서(AFTER_ENDED_AT) 이전에 미복구 후보 2건' "$WORK/err.log" \
            && ok "커서 뒤로 보류된 행이 별도 보고된다" \
            || bad "보류된 행이 보고되지 않았다" "$(grep -c . "$WORK/err.log") 줄"
    else
        bad "커서 이후 기대 13, 실제 $rc5" "$(tail -2 "$WORK/err.log")"
    fi
fi

echo "== 오리진 5xx: 일시 실패로 분류한다(kill 은 rc=7 만 만들어 이 경로를 못 덮는다)"
kill "$CDN_PID" 2>/dev/null || true; wait "$CDN_PID" 2>/dev/null || true
python3 -c "
import http.server
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(s): s.send_error(503)
    do_HEAD = do_GET
    def log_message(*a): pass
http.server.HTTPServer(('127.0.0.1', $CDN_PORT), H).serve_forever()
" >/dev/null 2>&1 &
CDN_PID=$!
# 기동을 확인하지 않으면 503 서버가 안 떴을 때 CDN 이 "죽은 상태"(rc=7)가 되어,
# 이 케이스가 아래 kill 케이스와 같은 것으로 조용히 퇴화하고도 통과한다.
origin_ready=0
for _ in $(seq 1 20); do
    [ "$(curl -sS -o /dev/null -w '%{http_code}' "$CDN_BASE_URL/x" 2>/dev/null)" = 503 ] && { origin_ready=1; break; }
    sleep 0.3
done
[ "$origin_ready" -eq 1 ] || { echo "  503 오리진 기동 실패 — 이 케이스를 건너뛸 수 없다"; exit 1; }
set +e; run BATCH_SIZE=50; rc7=$?; set -e
grep -q '전송 실패가 있었다' "$WORK/err.log" && ok "5xx 를 일시 실패로 분류한다" \
    || bad "5xx 가 영구 실패로 분류됐다" "$(tail -3 "$WORK/err.log")"
grep -q '아카이브 전송 실패' "$WORK/err.log" && ok "전송 실패 사유가 로그에 남는다" \
    || bad "전송 실패 사유가 로그에 없다"
if ran_clean "5xx 회차"; then
    grep -q 'AFTER_ENDED_AT=' "$WORK/err.log" && bad "5xx 회차인데 커서 전진을 권했다" \
        || ok "5xx 회차에는 커서 안내를 내지 않는다"
fi
[ "$rc7" = 10 ] && ok "5xx 회차는 10(완료로 끝나지 않는다)" || bad "기대 10, 실제 $rc7" "$(tail -2 "$WORK/err.log")"

echo "== CDN 접속 불가: preflight에서 중단한다"
kill "$CDN_PID" 2>/dev/null || true; wait "$CDN_PID" 2>/dev/null || true; CDN_PID=""
set +e; run BATCH_SIZE=50; rc6=$?; set -e
if [ "$rc6" = 1 ] && grep -q 'CDN_BASE_URL 에 도달할 수 없다' "$WORK/err.log"; then
    ok "접속 실패 preflight는 1"
    [ ! -s "$WORK/out.sql" ] && ok "접속 실패에는 SQL 출력 없음" || bad "접속 실패에 SQL 출력"
else
    bad "접속 실패가 preflight에서 차단되지 않았다 (rc=$rc6)" "$(tail -3 "$WORK/err.log")"
fi

echo "== 회차 중 상태 전이: 루프 도중 종료된 방을 놓치지 않는다"
kill "$CDN_PID" 2>/dev/null || true; wait "$CDN_PID" 2>/dev/null || true
# 응답을 늦추는 오리진 — 루프가 도는 동안 DB 를 바꿀 시간을 만든다.
python3 -c "
import http.server, time, functools
class H(http.server.SimpleHTTPRequestHandler):
    def do_GET(s): time.sleep(0.4); super().do_GET()
    def do_HEAD(s): time.sleep(0.4); super().do_HEAD()
    def log_message(*a): pass
h = functools.partial(H, directory='$CDN_ROOT')
http.server.HTTPServer(('127.0.0.1', $CDN_PORT), h).serve_forever()
" >/dev/null 2>&1 &
CDN_PID=$!
slow_ready=0
for _ in $(seq 1 30); do
    [ "$(curl -fsS "$CDN_BASE_URL/.probe" 2>/dev/null)" = marker ] && { slow_ready=1; break; }
    sleep 0.3
done
[ "$slow_ready" -eq 1 ] || { echo "  지연 오리진 기동 실패"; exit 1; }

R_SLOW=aaaaaaaa-0000-0000-0000-00000000aaaa   # 회차를 느리게 만드는 정상 후보
R_RACE=bbbbbbbb-0000-0000-0000-00000000bbbb   # 배포 이전 시작, 회차 도중 종료될 방
make_archive "$R_SLOW" 4
seed "$R_SLOW" "'$B/$R_SLOW/720p/index.m3u8'" "NULL" ENDED "'2026-09-10T14:00:00.100Z'" "'2026-09-10T13:00:00Z'"
seed "$R_RACE" "'$B/$R_RACE/720p/index.m3u8'" "NULL" LIVE  "NULL"                        "'2026-09-10T20:00:00Z'"

# 루프가 도는 동안(지연 오리진 덕에 수 초) 그 방을 ENDED 로 전이시킨다.
( sleep 1
  PGPASSFILE="$PGPASSFILE" psql -X -q -v ON_ERROR_STOP=1 \
    -c "UPDATE live_schema.live_rooms SET status='ENDED', ended_at=now(), hls_archive_url=NULL WHERE id='$R_RACE'" \
    >"$WORK/race.log" 2>&1 ) &
race_pid=$!
set +e; run BATCH_SIZE=50 AFTER_ENDED_AT=2026-09-10T13:00:00.000000Z; rc8=$?; set -e
wait "$race_pid" || { echo "경합 전이 실패: $(cat "$WORK/race.log")"; exit 1; }
[ "$(q "SELECT status FROM live_schema.live_rooms WHERE id='$R_RACE'")" = ENDED ] || exit 1

[ "$rc8" != 0 ] && ok "회차 중 종료된 방이 있으면 완료(0)로 끝나지 않는다 (rc=$rc8)" \
    || bad "회차 중 종료된 방을 놓치고 완료로 끝났다" "$(tail -3 "$WORK/err.log")"

# 두 집계 사이의 창에 전이를 끼워 넣는다. 순서(미종료 먼저 → 후보 나중)가 이 창을 닫는 유일한 장치다.
# 순서를 뒤집으면 그 방은 양쪽 집계에서 모두 빠져 완료(0)로 끝난다 — 이 케이스만 그걸 잡는다.
R_WINDOW=cccccccc-0000-0000-0000-00000000cccc
seed "$R_WINDOW" "'$B/$R_WINDOW/720p/index.m3u8'" "NULL" LIVE "NULL" "'2026-09-10T21:00:00Z'"
# 시간으로 맞추면 전이가 첫 집계 이전에 일어난다(preflight 가 1초 넘게 걸린다). 마커를 기다려 정확히 창에 넣는다.
WINDOW_MARKER="$WORK/window.marker"
# 운영 seam 없이 첫 postloop 집계 뒤에 전이한다. 순서가 뒤집히면 후보 집계 뒤에 전이해야
# 뒤늦은 LIVE 집계가 0을 반환하여 순서 회귀가 검출된다.
REAL_PSQL="$(command -v psql)"; export REAL_PSQL WINDOW_MARKER
COUNT_FILE="$WORK/count"; export COUNT_FILE
cat > "$WORK/psql-window" <<'WRAPPER'
#!/bin/sh
set -eu
"$REAL_PSQL" "$@"
pause=0
case "$*" in
    *"status = 'LIVE'"*)
        n=0; [ ! -f "$COUNT_FILE" ] || read -r n < "$COUNT_FILE"
        n=$((n + 1)); printf '%s\n' "$n" > "$COUNT_FILE"
        [ "$n" != 2 ] || pause=1 ;;
    *"SELECT count(*)"*"ended_at >"*) pause=1 ;;
esac
if [ "$pause" = 1 ] && [ ! -d "$WINDOW_MARKER" ]; then mkdir "$WINDOW_MARKER"; sleep 3; fi
WRAPPER
chmod +x "$WORK/psql-window"
( tries=0; while [ ! -d "$WINDOW_MARKER" ]; do
      tries=$((tries + 1)); [ "$tries" -lt 400 ] || exit 1; sleep 0.05
  done
  PGPASSFILE="$PGPASSFILE" psql -X -q -v ON_ERROR_STOP=1 \
    -c "UPDATE live_schema.live_rooms SET status='ENDED', ended_at=now(), hls_archive_url=NULL WHERE id='$R_WINDOW'" \
    >"$WORK/window.log" 2>&1 ) &
window_pid=$!
# 후보가 0 인 커서를 줘서 ok=0·skip=0 으로 만든다 — 그래야 두 집계만이 결과를 정한다.
set +e; run BATCH_SIZE=50 AFTER_ENDED_AT=2026-09-11T23:00:00.000000Z \
        PSQL_BIN="$WORK/psql-window"; rc9=$?; set -e
wait "$window_pid" || { echo "집계 창 전이 실패: $(cat "$WORK/window.log")"; exit 1; }
[ "$(q "SELECT status FROM live_schema.live_rooms WHERE id='$R_WINDOW'")" = ENDED ] || exit 1
# 종료 코드만 보면 둔감하다(보류가 있으면 어느 쪽이든 13). 집계값 자체를 본다 —
# 순서가 맞으면 미종료 집계가 전이 전 값(1)을 잡고, 뒤집으면 양쪽 모두 0 이 되어 그 방이 사라진다.
grep -q '배포 경계 미종료 방 1건' "$WORK/err.log" \
    && ok "집계 사이에 종료된 방을 미종료로 잡는다 (rc=$rc9)" \
    || bad "집계 순서가 창을 닫지 못했다 — 전이된 방이 양쪽 집계에서 빠졌다" \
           "$(grep -o '미종료 방 [0-9]*건 / 이번 상한 초과 잔여 [0-9]*건' "$WORK/err.log" | tail -1)"

echo "== 집계 사이 LIVE→SUSPENDED도 최종 보고에서 놓치지 않는다"
q "UPDATE live_schema.live_rooms SET status='LIVE', ended_at=NULL WHERE id='$R_WINDOW'" >/dev/null
rmdir "$WINDOW_MARKER"
printf '0\n' > "$COUNT_FILE"
( tries=0; while [ ! -d "$WINDOW_MARKER" ]; do
      tries=$((tries + 1)); [ "$tries" -lt 400 ] || exit 1; sleep 0.05
  done
  q "UPDATE live_schema.live_rooms SET status='SUSPENDED' WHERE id='$R_WINDOW'" >"$WORK/suspend.log" 2>&1
) &
suspend_pid=$!
set +e; run BATCH_SIZE=50 AFTER_ENDED_AT=2026-09-11T23:00:00.000000Z PSQL_BIN="$WORK/psql-window"; suspend_rc=$?; set -e
wait "$suspend_pid" || { echo "정지 전이 실패: $(cat "$WORK/suspend.log")"; exit 1; }
if ran_clean "정지 전이 회차"; then
    [ "$suspend_rc" = 10 ] && grep -q '최종 배포 이전 시작·정지 상태 2건' "$WORK/err.log" \
        && ok "루프 시작 뒤 추가된 정지 방을 최종 재집계" || bad "정지 방 재집계 누락"
fi
# 그 방이 실제로 후보로 살아 있는지 다음 회차로 확인한다(재집계가 빠지면 여기서 사라진다).
set +e; run BATCH_SIZE=50 AFTER_ENDED_AT=2026-09-10T13:00:00.000000Z; set -e
grep -q "$R_RACE" "$WORK/err.log" || grep -q "$R_RACE" "$WORK/out.sql" \
    && ok "전이된 방이 다음 회차 후보로 남는다" \
    || bad "전이된 방이 후보에서 사라졌다" "$(tail -3 "$WORK/err.log")"

echo
echo "통과 $pass / 실패 $fail"
[ "$fail" -eq 0 ]
