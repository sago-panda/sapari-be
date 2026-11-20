#!/bin/sh
# =============================================================================
# SPR-148 다시보기 URL 백필 — 검증기 (DB 에 쓰지 않는다. 적용 SQL 만 stdout 으로 낸다)
#
# 왜 필요한가: `endLive` 가 `streamInfo.hlsArchiveUrl()` 을 쓰기 시작하면서, 그 컬럼이 없던 시절의 행은
# 다시보기가 404 로 남는다. 두 모집단이 있다.
#   (a) hls_archive_url IS NULL       — 구 코드가 시작하고 신 코드가 종료한 방(배포 경계)
#   (b) hls_archive_url = hls_url     — 구 종료 경로가 라이브 URL 을 그대로 복사한 레거시 행
# 둘 중 하나만 고치면 나머지는 영원히 숨은 채 "완료"처럼 보인다.
#
# 재실행을 전제로 만들었다. 배포 경계의 방송은 최대 max_duration_seconds(기본 7200s) 뒤에야 ENDED 가
# 되므로 1회 실행으로는 끝나지 않는다. 생성되는 UPDATE 는 WHERE 로 자기 조건을 다시 확인하므로
# 몇 번을 돌려도 같은 최종 상태가 된다.
#
# 종료 코드 = 배포 완료 조건:
#   0  완료  — 생성된 SQL 0건 && 남은 후보 0건 && 미종료 방 0건 && 커서 뒤 보류 0건.
#   13 보류 있음 — 이 회차 범위는 끝났지만 커서 이전에 미복구 행이 남았다. 완료가 아니다.
#   11 적용 대기 — SQL 을 생성했다. 이 스크립트는 DB 에 쓰지 않으므로 아직 아무것도 고쳐지지 않았다.
#                 psql -f 로 적용한 뒤 다시 돌려 0 이 나오는지 확인할 것.
#   10 재실행 필요 — 미종료 방이 남았거나, 검증 실패로 건너뛴 행이 있다.
#   12 self-test 실패 — 로직 점검이 깨졌다. 백필을 돌리기 전에 고칠 것.
#   14 객체 전달 구성 오류 — 리다이렉트/Range 응답을 확인할 것. 생성된 SQL은 별도 검토한다.
#   1  오류 — 입력 검증 또는 preflight(접속·스키마·대상 DB) 실패. 백필이 일어나지 않았다.
#              preflight 통과 이후의 psql 실패는 psql 자신의 종료 코드로 빠져나간다(예: 3).
#
# 접속(자격증명을 인자로 넘기지 않는다 — argv 는 같은 호스트의 다른 사용자가 ps 로 읽는다):
#   libpq 표준 환경변수만 쓴다. 비밀번호는 ~/.pgpass(0600) 또는 PGSERVICE 로 둘 것.
#     PGHOST=... PGPORT=5432 PGDATABASE=sapari_db PGUSER=... \
#     CDN_BASE_URL=https://cdn.example.com ./infra/replay-backfill/backfill-archive-url.sh > backfill.sql
#   또는  PGSERVICE=sapari-primary CDN_BASE_URL=... DEPLOY_TS=... ./...sh > backfill.sql
#
# DEPLOY_TS(필수): **마지막 구 replica 가 내려간 시각**(롤아웃 완료 시각). 배포 "시작" 시각을 넣으면 안 된다 —
#   롤아웃 중에도 구 replica 가 새 방송을 시작할 수 있고, 그 방들은 이 경계 밖으로 빠져 영구 누락된다.
#   전체 비-ENDED 를 세면 상시 존재하는 예약(SCHEDULED)·정지(SUSPENDED) 방 때문에 완료 조건이 영원히 안 선다.
#
# 적용(사람이 한다. 이 스크립트는 읽기만 한다):
#   psql -f backfill.sql        # 행마다 독립 트랜잭션이라 중간에 끊겨도 앞부분은 유효하다
#
# status 컬럼은 절대 쓰지 않는다 — 벌크 status 변경 전 orphan-media 잡을 내리는 절차는 이 도구에 해당 없다.
#
# 이 스크립트가 증명하지 않는 것: 디코딩 가능 여부. EVENT/ENDLIST 와 전 세그먼트 도달까지만 본다.
# 승인 전 최소 한 행은 실제 플레이어로 EOS 까지 재생해 확인할 것.
#
# 로직 자체 점검(DB 불필요):  ./infra/replay-backfill/backfill-archive-url.sh --self-test
# =============================================================================
set -eu
set -f  # 글로빙 차단 — 플레이리스트 줄이 로컬 파일명으로 치환되지 않게 한다.

log() { echo "$@" >&2; }
die() { log "ERROR: $*"; exit 1; }

PSQL_BIN="${PSQL_BIN:-psql}"
CURL_BIN="${CURL_BIN:-curl}"
# 기본 화질. 출처: LiveKitMediaManager.DEFAULT_RENDITION = HlsRendition.P720 의 pathSegment.
# 그쪽을 바꾸면 여기도 같이 바꿔야 한다(안 바꾸면 전 행이 검증 실패로 떨어진다).
DEFAULT_RENDITION="${DEFAULT_RENDITION:-720p}"
# 이 값은 유도 결과 경로 한가운데에 들어간다. 검사하지 않으면 '../../other/720p' 같은 값이
# CDN 접두사 검사를 통과한 채 다른 방의 아카이브를 이 방 행에 배정한다.
case "$DEFAULT_RENDITION" in
    ''|*[!A-Za-z0-9]*) die "DEFAULT_RENDITION 은 영숫자만 허용한다: $DEFAULT_RENDITION" ;;
esac
TABLE="live_schema.live_rooms"
CR="$(printf '\r')"
# 모든 임시 파일은 한 전용 디렉터리 아래에 둔다. 서브셸에서 만들어도 부모 trap이 정리한다.
# (BODY 는 verify_archive 가 쓰므로 self-test 보다 앞에서 만들어야 한다.)
WORK="$(mktemp -d)"
cleanup_all() { rm -rf "$WORK"; }
trap cleanup_all EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
new_temp() { mktemp "$WORK/file.XXXXXX"; }
new_tempdir() { mktemp -d "$WORK/dir.XXXXXX"; }

BODY="$(new_temp)"
SEGMENT_BODY="$(new_temp)"


# --- 순수 로직 (self-test 대상) -------------------------------------------------

# 라이브 URL -> 아카이브 URL. 없는 객체를 만들어내지 않는 범위에서만 유도한다.
#   .../<화질>/index.m3u8 -> .../<화질>/playlist.m3u8
#   .../master.m3u8       -> .../<기본화질>/playlist.m3u8
# 레거시 master 행에 archive-master.m3u8 을 붙이지 않는다 — 그 객체는 SPR-148 이후에만 생기고,
# 키가 있더라도 두 번째 master 업로드 실패로 남은 고아일 수 있다(infra/AGENTS.md).
derive_archive_url() {
    url="${1%%\?*}"   # 쿼리스트링은 떼고 판단한다(CDN 캐시버스팅이 붙어도 모집단에서 빠지지 않게).
    # 상위 경로 참조는 정규화되면 다른 방의 객체를 가리킬 수 있다.
    # 대소문자 혼합·이중 인코딩(%252e)까지 개별로 나열하는 대신 '%' 자체를 거부한다 —
    # 정상 산출물 키에는 퍼센트 인코딩이 없다(roomId 는 UUID, 파일명은 고정 리터럴).
    case "$url" in *..*|*%*) echo ""; return ;; esac
    case "$url" in
        */master.m3u8) out="${url%/master.m3u8}/$DEFAULT_RENDITION/playlist.m3u8" ;;
        */index.m3u8)  out="${url%/index.m3u8}/playlist.m3u8" ;;
        *)             echo ""; return ;;
    esac
    # 입력뿐 아니라 출력도 본다 — 조립 과정에서 끼어든 상위 경로를 놓치지 않기 위해서다.
    case "$out" in *..*|*%*) echo ""; return ;; esac
    echo "$out"
}

# SQL 작은따옴표 리터럴 이스케이프. 호출부는 값에 따옴표가 있으면 아예 건너뛰지만,
# 이스케이프도 함께 둔다(둘 중 하나만 남기면 나중에 조용히 뚫린다).
sql_escape() { printf '%s' "$1" | sed "s/'/''/g"; }

has_quote() { case "$1" in *\'*) return 0 ;; *) return 1 ;; esac; }

# 종료 코드 결정. 순수 함수로 떼어 self-test 로 덮는다 — 직전 라운드의 High 가 여기서 났다.
# 우선순위: 적용 대기(11) > 재실행 필요(10) > 보류(13) > 완료(0).
decide_exit() { # <ok> <skip> <unfinished> <remaining> <stuck>
    # 순서가 중요하다. 생성된 SQL 이 있으면 다음 행동은 언제나 "적용"이므로 11 이 먼저다.
    # 10 을 먼저 내면 "SQL 을 버리고 재실행"으로 읽혀, 백로그가 BATCH_SIZE 보다 큰 정상 경우에
    # 매 회차 같은 SQL 만 다시 만들고 한 행도 고쳐지지 않는다.
    # 13 은 "이 회차 범위는 끝났지만 커서 뒤에 미복구 행이 남았다" — 0 과 반드시 구분해야 한다.
    # 둘을 합치면 커서를 끝까지 민 실행이 "완료"로 읽혀 미복구 방송을 남긴 채 티켓이 닫힌다.
    if [ "$1" -gt 0 ]; then echo 11
    elif [ "$2" -gt 0 ] || [ "$3" -gt 0 ] || [ "$4" -gt 0 ]; then echo 10
    elif [ "${5:-0}" -gt 0 ]; then echo 13
    else echo 0; fi
}

self_test() {
    fail=0
    stub_err="$(new_temp)"
    check() { # check <설명> <기대> <실제>
        if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: 기대=[$2] 실제=[$3]"; fail=1; fi
    }
    check "720p index -> playlist" \
        "https://cdn/live/r1/720p/playlist.m3u8" "$(derive_archive_url https://cdn/live/r1/720p/index.m3u8)"
    check "master -> 기본화질 playlist" \
        "https://cdn/live/r1/$DEFAULT_RENDITION/playlist.m3u8" "$(derive_archive_url https://cdn/live/r1/master.m3u8)"
    check "쿼리스트링 제거 후 유도" \
        "https://cdn/live/r1/720p/playlist.m3u8" "$(derive_archive_url 'https://cdn/live/r1/master.m3u8?v=1')"
    check "archive-master 는 대상 아님" "" "$(derive_archive_url https://cdn/live/r1/archive-master.m3u8)"
    check "알 수 없는 형태는 대상 아님" "" "$(derive_archive_url https://cdn/live/r1/foo.m3u8)"
    check "상위 경로 참조는 대상 아님" "" "$(derive_archive_url https://cdn/a/../b/720p/index.m3u8)"
    check "인코딩된 상위 경로도 대상 아님" "" "$(derive_archive_url https://cdn/a/%2e%2e/b/720p/index.m3u8)"
    check "따옴표 이스케이프" "a''b" "$(sql_escape "a'b")"
    if has_quote "a'b" && ! has_quote "ab"; then echo "ok   따옴표 탐지"; else echo "FAIL 따옴표 탐지"; fail=1; fi
    # 출력만 비교하면 서브셸이 죽어도 빈 문자열이라 "ok" 로 찍힌다(실제로 그렇게 결함을 놓쳤다).
    # 종료 상태까지 본다 — set -u 위반 같은 실패가 통과로 기록되지 않게.
    for u in https://cdn/r/720p/index.m3u8 https://cdn/r/master.m3u8 https://cdn/r/archive-master.m3u8 \
             https://cdn/r/foo.m3u8 https://cdn/a/../b/index.m3u8; do
        if derive_archive_url "$u" >/dev/null 2>"$stub_err"; then
            [ -s "$stub_err" ] && { echo "FAIL derive 가 stderr 를 냈다: $u"; fail=1; } \
                               || echo "ok   derive 정상 종료: $u"
        else
            echo "FAIL derive 가 비정상 종료했다: $u"; fail=1
        fi
    done
    check "생성 SQL 이 있으면 완료가 아니다(적용 대기)" "11" "$(decide_exit 5 0 0 0 0)"
    check "건너뜀이 있어도 생성 SQL 적용이 먼저" "11" "$(decide_exit 5 1 0 0 0)"
    check "배포 경계 미종료 방이 있으면 재실행" "10" "$(decide_exit 0 0 3 0 0)"
    check "상한 초과분이 남아도 생성 SQL 적용이 먼저" "11" "$(decide_exit 50 0 0 120 0)"
    check "생성 0 + 상한 초과분만 남으면 재실행" "10" "$(decide_exit 0 0 0 120 0)"
    check "생성 0 + 건너뜀만 있으면 재실행" "10" "$(decide_exit 0 3 0 0 0)"
    check "커서 뒤 보류만 남으면 보류(13)" "13" "$(decide_exit 0 0 0 0 2)"
    check "보류가 있어도 잔여가 있으면 재실행이 먼저" "10" "$(decide_exit 0 0 0 5 2)"
    check "생성 0·건너뜀 0·미종료 0·잔여 0·보류 0 이면 완료" "0" "$(decide_exit 0 0 0 0 0)"
    # verify_archive — CURL_BIN 주입으로 DB·네트워크 없이 검증한다.
    stub_dir="$(new_tempdir)"
    cat > "$stub_dir/curl" <<'STUB'
#!/bin/sh
# 실제 curl 과 같은 계약을 흉내낸다: -f 없이는 4xx/5xx 도 종료 코드 0 이고 상태 코드는 -w 로 나온다.
# FIXTURE 의 파일이 있으면 200, 없으면 404. FIXTURE_CODE 가 있으면 그 코드를 강제한다.
out=""; want_code=0; head=0; url=""; prev=""
for a in "$@"; do
    case "$prev" in -o) out="$a" ;; esac
    case "$a" in -w) want_code=1 ;; -I) head=1 ;; esac
    prev="$a"; url="$a"
done
name="$(printf '%s' "$url" | sed 's#.*/##')"
code="${FIXTURE_CODE:-}"
if [ -z "$code" ]; then
    [ -f "$FIXTURE/$name" ] && code=200 || code=404
fi
if [ "$code" = 200 ] && [ "$head" = 0 ]; then
    if [ -n "$out" ]; then cat "$FIXTURE/$name" > "$out"; else cat "$FIXTURE/$name"; fi
fi
[ "$want_code" = 1 ] && printf '%s' "$code"
exit 0
STUB
    chmod +x "$stub_dir/curl"
    FIXTURE="$stub_dir"; export FIXTURE
    old_curl="$CURL_BIN"; CURL_BIN="$stub_dir/curl"

    printf '#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\nsegment_0.ts\nsegment_1.ts\n#EXT-X-ENDLIST\n' > "$stub_dir/ok.m3u8"
    for f in segment_0.ts segment_1.ts; do
        { for packet in 1 2 3; do printf '\107'; dd if=/dev/zero bs=187 count=1 2>/dev/null; done; } > "$stub_dir/$f"
    done
    check "정상 EVENT 아카이브는 세그먼트 수를 낸다" "2" "$(verify_archive "https://cdn/x/ok.m3u8" || echo ERR)"

    printf '#EXTM3U\r\n#EXT-X-PLAYLIST-TYPE:EVENT\r\nsegment_0.ts\r\n#EXT-X-ENDLIST\r\n' > "$stub_dir/crlf.m3u8"
    check "CRLF 플레이리스트도 통과" "1" "$(verify_archive "https://cdn/x/crlf.m3u8" || echo ERR)"

    printf '#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\nsegment_0.ts\n' > "$stub_dir/noend.m3u8"
    check "ENDLIST 없으면 거부" "ERR" "$(verify_archive "https://cdn/x/noend.m3u8" 2>/dev/null || echo ERR)"

    printf '#EXTM3U\nsegment_0.ts\n#EXT-X-ENDLIST\n' > "$stub_dir/noevent.m3u8"
    check "ENDLIST와 정상 세그먼트가 있어도 EVENT 없으면 거부" "ERR" "$(verify_archive https://cdn/x/noevent.m3u8 2>/dev/null || echo ERR)"
    printf '#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXT-X-ENDLIST\n' > "$stub_dir/empty.m3u8"
    check "EVENT와 ENDLIST가 있어도 세그먼트 0개면 거부" "ERR" "$(verify_archive https://cdn/x/empty.m3u8 2>/dev/null || echo ERR)"
    printf '#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\nsub/segment_0.ts\n#EXT-X-ENDLIST\n' > "$stub_dir/slash.m3u8"
    check "다른 문자가 없는 하위 경로도 거부" "ERR" "$(verify_archive https://cdn/x/slash.m3u8 2>/dev/null || echo ERR)"
    printf '<html>not found</html>\n' > "$stub_dir/html.ts"
    printf '#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\nhtml.ts\n#EXT-X-ENDLIST\n' > "$stub_dir/soft404.m3u8"
    check "HTTP 200 HTML 세그먼트 거부" "ERR" "$(verify_archive https://cdn/x/soft404.m3u8 2>/dev/null || echo ERR)"

    printf '#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\nhttp://evil/x.ts\n#EXT-X-ENDLIST\n' > "$stub_dir/abs.m3u8"
    check "절대 URL 세그먼트는 거부" "ERR" "$(verify_archive "https://cdn/x/abs.m3u8" 2>/dev/null || echo ERR)"

    printf '#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n%%2e%%2e/other/seg.ts\n#EXT-X-ENDLIST\n' > "$stub_dir/trav.m3u8"
    check "인코딩된 상위 경로 세그먼트는 거부" "ERR" "$(verify_archive "https://cdn/x/trav.m3u8" 2>/dev/null || echo ERR)"

    printf '#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXT-X-MAP:URI="init.mp4"\nsegment_0.ts\n#EXT-X-ENDLIST\n' > "$stub_dir/map.m3u8"
    check "EXT-X-MAP(fMP4)은 거부" "ERR" "$(verify_archive "https://cdn/x/map.m3u8" 2>/dev/null || echo ERR)"

    printf '#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\nsegment_0.ts\nmissing.ts\n#EXT-X-ENDLIST\n' > "$stub_dir/gap.m3u8"
    check "세그먼트가 하나라도 없으면 거부" "ERR" "$(verify_archive "https://cdn/x/gap.m3u8" 2>/dev/null || echo ERR)"

    check "5xx 는 일시 실패(2)" "2" "$(classify_http 0 503 || echo $?)"
    check "429 는 일시 실패(2)" "2" "$(classify_http 0 429 || echo $?)"
    check "404 는 영구 실패(1)" "1" "$(classify_http 0 404 || echo $?)"
    check "403 은 영구 실패(1)" "1" "$(classify_http 0 403 || echo $?)"
    check "max-filesize 초과(63)는 영구 실패(1)" "1" "$(classify_http 63 0 || echo $?)"
    check "접속 불가(7)는 일시 실패(2)" "2" "$(classify_http 7 000 || echo $?)"
    check "객체 리다이렉트는 구성 오류(3)" "3" "$(classify_http 0 302 || echo $?)"
    check "200 은 성공" "0" "$(classify_http 0 200; echo $?)"

    # set -e 아래에서는 서브셸이 즉시 죽어 `; echo $?` 가 실행되지 않는다 — rc 를 따로 받는다.
    export FIXTURE_CODE=503
    va_rc=0; verify_archive https://cdn/x/ok.m3u8 >/dev/null 2>&1 || va_rc=$?
    check "503 아카이브는 일시 실패로 분류" "2" "$va_rc"
    export FIXTURE_CODE=404
    va_rc=0; verify_archive https://cdn/x/ok.m3u8 >/dev/null 2>&1 || va_rc=$?
    check "404 아카이브는 영구 실패로 분류" "1" "$va_rc"
    unset FIXTURE_CODE

    CURL_BIN="$old_curl"

    # 리터럴 드리프트 감지 — Java 쪽 기본 화질이 바뀌면 여기서 잡는다(리포지토리 안에서 실행할 때만).
    mgr="$(dirname "$0")/../../modules/live/live-core/src/main/java/com/sapari/live/infrastructure/media"
    if [ -r "$mgr/LiveKitMediaManager.java" ] && [ -r "$mgr/HlsRendition.java" ]; then
        preset="$(sed -n 's/.*DEFAULT_RENDITION = HlsRendition\.\([A-Z0-9]*\);.*/\1/p' "$mgr/LiveKitMediaManager.java")"
        segment="$(sed -n "s/^ *$preset(\"\([^\"]*\)\".*/\1/p" "$mgr/HlsRendition.java")"
        check "기본 화질이 Java 소스와 일치" "$DEFAULT_RENDITION" "$segment"
        check "라이브 플레이리스트 파일명이 Java 소스와 일치" "index.m3u8" \
            "$(sed -n 's/.*LIVE_PLAYLIST_NAME = "\([^"]*\)".*/\1/p' "$mgr/HlsRendition.java")"
        check "아카이브 플레이리스트 파일명이 Java 소스와 일치" "playlist.m3u8" \
            "$(sed -n 's/.*ARCHIVE_PLAYLIST_NAME = "\([^"]*\)".*/\1/p' "$mgr/HlsRendition.java")"
    else
        echo "WARNING: Java 소스 대조 3건 미실행 — 리포지토리의 동일 스크립트로 --self-test를 실행해야 배포 검증이 완료된다"
    fi
    [ "$fail" -eq 0 ] || return 1
    echo "실행된 self-test 통과 (위 미실행 경고가 있으면 전체 배포 검증 완료가 아님)"
}


# --- DB / 네트워크 ---------------------------------------------------------------

psql_query() { "$PSQL_BIN" -X -q -At -F '|' -v ON_ERROR_STOP=1 -c "$1"; }

# 스키마 선검증. 이름이 틀리면 여기서 크게 실패한다 — "대상 행 없음"으로 조용히 끝나는 일이 없게.
preflight() {
    psql_query "SELECT 1" >/dev/null 2>&1 || die "DB 접속 실패. libpq 환경변수(PGHOST/PGDATABASE/PGUSER, PGSERVICE)를 확인할 것."
    missing="$(psql_query "
        SELECT string_agg(c.name, ', ')
        FROM (VALUES ('id'),('status'),('hls_url'),('hls_archive_url'),('ended_at'),('started_at')) AS c(name)
        WHERE NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'live_schema' AND table_name = 'live_rooms'
              AND column_name = c.name)")"
    [ -z "$missing" ] || die "$TABLE 에 기대한 컬럼이 없다: $missing (스키마가 다르거나 잘못된 DB 를 가리키고 있다)"
    # 컬럼만 맞고 데이터가 없는 DB(스테이징·초기화된 로컬)를 "완료"로 읽지 않게 한다.
    # 종료된 방이 하나도 없는 DB 는 이 도구의 대상이 아니다.
    ended="$(psql_query "SELECT count(*) FROM $TABLE WHERE status = 'ENDED'")"
    [ "$ended" -gt 0 ] || die "$TABLE 에 ENDED 행이 하나도 없다 — 대상 DB 가 맞는지 확인할 것(잘못된 DB 를 완료로 판정하지 않는다)"
    psql_query "SELECT '$(sql_escape "$DEPLOY_TS")'::timestamptz, '$(sql_escape "$AFTER_ENDED_AT")'::timestamptz" \
        >/dev/null 2>&1 || die "DEPLOY_TS 또는 AFTER_ENDED_AT 이 실제 시각이 아니다 (DEPLOY_TS=$DEPLOY_TS, AFTER_ENDED_AT=$AFTER_ENDED_AT)"
    # 루트는 연결 여부만 확인한다. 루트의 목록·리다이렉트 정책은 객체 응답을 보증하지 않는다.
    # 객체 3xx는 verify_archive에서 별도 구성 오류로 분류한다. -L은 목적지 범위를 넓혀 쓰지 않는다.
    cdn_rc=0
    cdn_code="$("$CURL_BIN" -sS -o /dev/null --max-time 10 -w '%{http_code}' -- "$CDN_BASE_URL/" 2>/dev/null)" || cdn_rc=$?
    [ "$cdn_rc" -eq 0 ] && [ "$cdn_code" != 000 ] || die "CDN_BASE_URL 에 도달할 수 없다 (curl=$cdn_rc HTTP=$cdn_code)"
    log "preflight ok — ENDED ${ended}건"
}

# HTTP 결과를 영구(1)/일시(2) 로 가른다. 두 호출 지점이 같은 규칙을 쓰도록 한 곳에 모은다.
#   4xx        → 영구: 객체가 없거나 접근 불가. 다시 시도해도 같다.
#   5xx·429    → 일시: 오리진이 흔들리는 중일 수 있다. 커서를 전진시키면 멀쩡한 행이 보류된다.
#   curl 63    → 영구: --max-filesize(1MB) 초과. 정상 플레이리스트는 최대 방송 길이(7200s)를 1초 세그먼트로
#                채워도 파일명·EXTINF만 약 225KB이며 날짜 태그 등으로 더 커질 수 있다. 1MB는 운영 상한이다.
#   그 외 rc   → 일시: 접속 불가·타임아웃.
classify_http() { # <curl rc> <http code>
    if [ "$1" -eq 0 ]; then
        case "$2" in 2*) return 0 ;; esac
    fi
    [ "$1" -eq 63 ] && return 1
    case "$2" in
        3*)     return 3 ;;
        429|5*) return 2 ;;
        4*)     return 1 ;;
    esac
    return 2
}

# EVENT + ENDLIST 확인 후 나열된 세그먼트를 전수 확인한다. 성공 시 세그먼트 수를 stdout 에 낸다.
verify_archive() {
    archive_url="$1"
    base="${archive_url%/*}"
    # 반환값: 0 성공 / 1 콘텐츠 판정 실패(영구) / 2 전송 실패(일시적일 수 있음).
    # 둘을 뭉치면 CDN 장애 회차에 커서 전진을 권하게 되고, 멀쩡한 행이 통째로 보류된다.
    # `if ! cmd` 안의 $? 는 부정 결과라 curl 코드가 잡히지 않는다 — 반드시 이렇게 받아야 한다.
    rc=0; code=0
    playlist="$("$CURL_BIN" -sS --max-time 30 --max-filesize 1048576 \
                          -w '%{http_code}' -o "$BODY" -- "$archive_url" 2>/dev/null)" || rc=$?
    code="$playlist"
    [ "$rc" -ne 0 ] || playlist="$(cat "$BODY")"
    case "$code" in
        3*) log "    리다이렉트 응답($code) — CDN_BASE_URL 구성을 확인할 것(-L 은 쓰지 않는다)" ;;
    esac
    classify_http "$rc" "$code" || return $?
    printf '%s\n' "$playlist" | tr -d '\r' | grep -qx '#EXT-X-PLAYLIST-TYPE:EVENT' || return 1
    printf '%s\n' "$playlist" | tr -d '\r' | grep -qx '#EXT-X-ENDLIST' || return 1
    # fMP4 라면 init 세그먼트(EXT-X-MAP)가 필요한데 이 검증기는 그걸 확인하지 않는다.
    # 통과시키면 init 누락 URL 이 확정되므로, 모르는 형식은 거부한다(현재 egress 는 TS 라 해당 없음).
    if printf '%s\n' "$playlist" | grep -q '^#EXT-X-MAP'; then
        log "    거부: EXT-X-MAP 이 있는 플레이리스트는 이 도구가 검증하지 않는다"
        return 1
    fi

    count=0
    # 절대 URL 줄은 거부한다 — 검증 대상 객체가 검증기의 요청 목적지를 고르게 두지 않는다(SSRF).
    while IFS= read -r line; do
        line="${line%"$CR"}"   # CRLF 플레이리스트의 줄 끝 CR 제거(안 떼면 정상 아카이브가 원인 불명 FAIL 이 된다)
        case "$line" in
            ''|'#'*) continue ;;
            # 허용 목록. 세그먼트는 같은 디렉터리의 파일명이어야 한다 — '/' 도 '%' 도 허용할 이유가 없다.
            # 차단 목록(.. 나열)으로는 %2e%2e·%252e 같은 변형을 계속 놓친다.
            *[!A-Za-z0-9._-]*) log "    거부: 세그먼트 이름에 허용되지 않는 문자 — $line"; return 1 ;;
            ..|.) log "    거부: 상위 경로 참조 — $line"; return 1 ;;
        esac
        rc=0
        # 3개 TS 패킷만 요청한다. HTML soft-404(200)를 세그먼트로 오인하지 않는다.
        # Range를 무시하는 원점은 최대 1MB까지만 받으며, 초과하면 보수적으로 거부한다.
        code="$("$CURL_BIN" -sS -o "$SEGMENT_BODY" --range 0-563 --max-filesize 1048576 --max-time 30 -w '%{http_code}' -- "$base/$line" 2>/dev/null)" || rc=$?
        if [ "$rc" -eq 63 ]; then
            log "    Range 요청의 응답이 상한을 초과했다 — 세그먼트 결손으로 보류하지 말고 CDN Range 지원을 확인할 것"
            return 3
        fi
        classify_http "$rc" "$code" || return $?
        [ "$(wc -c < "$SEGMENT_BODY")" -ge 564 ] || return 1
        for offset in 0 188 376; do
            [ "$(od -An -tu1 -j "$offset" -N 1 "$SEGMENT_BODY" | tr -d ' ')" = 71 ] || return 1
        done
        count=$((count + 1))
    done <<EOF
$playlist
EOF
    [ "$count" -gt 0 ] || return 1
    echo "$count"
}

# verify_archive 까지 정의된 뒤에 self-test 를 돌린다(그 함수도 케이스에 포함되므로).
[ "${1:-}" != "--self-test" ] || { self_test || exit 12; exit 0; }

# 예전 테스트 seam은 운영에서 파일을 절삭하거나 무기한 대기할 수 있어 제거했다.
[ -z "${BACKFILL_PAUSE_BETWEEN_COUNTS:-}${BACKFILL_PAUSE_MARKER:-}" ] || die "BACKFILL_PAUSE_* 는 더 이상 지원하지 않는다"

# 아래 필수 입력 검사는 self-test 뒤에 둔다 — 로직 점검에는 DB·CDN 설정이 필요 없다.
# ${VAR:?} 는 dash 에서 2, bash 에서 1 로 끝나 종료 코드 계약이 셸에 따라 갈린다. die 로 1 에 고정한다.
[ -n "${CDN_BASE_URL:-}" ] || die "CDN_BASE_URL is required (예: https://cdn.example.com) — 유도한 URL 이 이 접두사로 시작하는지 검사한다"
CDN_BASE_URL="${CDN_BASE_URL%/}"
# 접두사 검사(${var#pattern})는 글로브 매칭이라 '*' 하나로 무력해진다. 메타문자를 아예 막는다.
case "$CDN_BASE_URL" in
    *[!A-Za-z0-9.:/_-]*) die "CDN_BASE_URL 에 허용되지 않는 문자가 있다: $CDN_BASE_URL" ;;
esac
[ -n "${DEPLOY_TS:-}" ] || die "DEPLOY_TS is required (마지막 구 replica 가 내려간 시각, 예: 2026-09-11T00:00:00Z)"
# 형식을 좁혀 둔다 — 오타(시간대 누락 등)가 조용히 통과하면 미종료 집계가 어긋나 완료 판정이 틀어진다.
case "$DEPLOY_TS" in
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]Z) : ;;
    *) die "DEPLOY_TS 는 YYYY-MM-DDThh:mm:ssZ 형식이어야 한다: $DEPLOY_TS" ;;
esac
# 한 회차 상한. 방 하나당 (플레이리스트 1 + 세그먼트 수)회 요청을 직렬로 보낸다 —
# 220 세그먼트/방(SPR-147 실측) 기준 BATCH_SIZE=50 이면 한 회차 약 11,000 요청이다.
# 멱등하므로 남은 분량은 다음 회차가 이어받는다(종료 코드 10).
# 커서. 선두 행이 복구 불가(세그먼트 만료 등)로 계속 실패하면 그 뒤가 영영 처리되지 않으므로,
# 회차 끝에 찍히는 마지막 ended_at 을 넣어 다음 회차가 그 뒤부터 보게 한다.
AFTER_ENDED_AT="${AFTER_ENDED_AT:-1970-01-01T00:00:00Z}"
# 소수부(마이크로초)를 허용한다 — 커서가 그걸 잃으면 막힌 행을 넘지 못한다.
case "$AFTER_ENDED_AT" in
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]Z) : ;;
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9]*Z) : ;;
    *) die "AFTER_ENDED_AT 은 YYYY-MM-DDThh:mm:ss[.uuuuuu]Z 형식이어야 한다: $AFTER_ENDED_AT" ;;
esac
BATCH_SIZE="${BATCH_SIZE:-50}"
case "$BATCH_SIZE" in
    ''|*[!0-9]*) die "BATCH_SIZE 는 양의 정수여야 한다: $BATCH_SIZE" ;;
esac
[ "$BATCH_SIZE" -ge 1 ] && [ "$BATCH_SIZE" -le 500 ] || die "BATCH_SIZE 는 1~500 범위여야 한다: $BATCH_SIZE"


preflight

# 위험 모집단은 "배포 이전에 시작돼 아직 안 끝난 방"뿐이다. 배포 후 시작된 방은 신 코드가 아카이브를 쓴다.
count_unfinished() {
    # LIVE 만 센다. SUSPENDED 는 자동으로 ENDED 가 되는 경로가 없어(findStaleLiveRoomIds 는 LIVE 만 훑는다)
    # 여기 포함시키면 배포 이전 정지된 방 하나로 완료 조건이 영구히 안 선다. 그쪽은 아래에서 따로 보고만 한다.
    psql_query "SELECT count(*) FROM $TABLE
                WHERE status = 'LIVE'
                  AND started_at IS NOT NULL
                  AND started_at < '$(sql_escape "$DEPLOY_TS")'::timestamptz"
}
UNFINISHED="$(count_unfinished)"

# 두 모집단 어디에도 안 잡히지만 다시보기가 불가능한 행 — 조용히 사라지지 않게 집계만 해 둔다.
NO_LIVE_URL="$(psql_query "SELECT count(*) FROM $TABLE
                           WHERE status = 'ENDED' AND hls_url IS NULL AND hls_archive_url IS NULL")"

# 배포 이전에 시작돼 정지된 방 — 자동 종료 경로가 없어 완료 조건에 넣지 않고 보고만 한다(수동 처리 대상).
# ended_at 이 비어 정렬·커서 기준을 못 만드는 행 — 후보에서 빠지므로 조용히 사라지지 않게 보고만 한다.
NO_ENDED_AT="$(psql_query "SELECT count(*) FROM $TABLE
                           WHERE status = 'ENDED' AND ended_at IS NULL AND hls_url IS NOT NULL
                             AND (hls_archive_url IS NULL OR hls_archive_url = hls_url)")"

# 커서 이전 구간에 남아 있는 후보 — 이 회차 범위 밖이지만 완료(0)를 막는 보류(13)다.
count_stuck() {
    psql_query "SELECT count(*) FROM $TABLE
                WHERE status = 'ENDED' AND hls_url IS NOT NULL AND ended_at IS NOT NULL
                  AND (hls_archive_url IS NULL OR hls_archive_url = hls_url)
                  AND ended_at <= '$(sql_escape "$AFTER_ENDED_AT")'::timestamptz"
}
STUCK_BEFORE_CURSOR="$(count_stuck)"

count_suspended() { psql_query "SELECT count(*) FROM $TABLE
                                WHERE status = 'SUSPENDED' AND started_at IS NOT NULL
                                  AND started_at < '$(sql_escape "$DEPLOY_TS")'::timestamptz"; }
SUSPENDED_BEFORE="$(count_suspended)"

# 후보 총수는 루프 직후에 다시 센다(count_candidates). 루프가 길어 그 사이 종료된 방이 생길 수 있다.
# 잔여는 반드시 후보 조회와 같은 범위여야 한다 — 커서 조건이 빠지면 커서를 쓰는 순간
# 커서 이전 구간이 계속 잔여로 잡혀 완료 판정이 영원히 서지 않는다.
count_candidates() {
    psql_query "SELECT count(*) FROM $TABLE
                WHERE status = 'ENDED' AND hls_url IS NOT NULL AND ended_at IS NOT NULL
                  AND (hls_archive_url IS NULL OR hls_archive_url = hls_url)
                  AND ended_at > '$(sql_escape "$AFTER_ENDED_AT")'::timestamptz"
}
CANDIDATES="$(psql_query "
    -- 커서로 되먹일 수 있도록 ended_at 을 UTC ISO(마이크로초 포함)로 낸다.
    -- to_char 의 "T"/"Z" 인용은 쓰지 않는다 — 이 SQL 은 셸 큰따옴표 안에 들어가 있어 그 따옴표가 먹힌다.
    -- 초 단위로 절삭하면 그 행이 다음 회차에 다시 걸려 커서가 영영 전진하지 못한다.
    SELECT id, hls_url,
           to_char(ended_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') || 'T'
        || to_char(ended_at AT TIME ZONE 'UTC', 'HH24:MI:SS.US') || 'Z'
    FROM $TABLE
    WHERE status = 'ENDED'
      AND hls_url IS NOT NULL
      AND (hls_archive_url IS NULL OR hls_archive_url = hls_url)
      AND ended_at IS NOT NULL
      AND ended_at > '$(sql_escape "$AFTER_ENDED_AT")'::timestamptz
    -- ended_at 이 같은 형제 행이 BATCH_SIZE 경계에 걸치면 커서 전진 시 뒤쪽이 건너뛰어진다.
    -- 조용히 사라지지는 않는다 — STUCK_BEFORE_CURSOR 가 세어 보고하므로 수동 처리 대상으로 드러난다.
    ORDER BY ended_at
    LIMIT $BATCH_SIZE")"

TOTAL="$(printf '%s' "$CANDIDATES" | grep -c . || true)"
log "후보 ${TOTAL}건 / 배포 이전 시작·미종료 방 ${UNFINISHED}건 / 라이브 URL 조차 없는 ENDED 행 ${NO_LIVE_URL}건"
[ "$NO_LIVE_URL" -eq 0 ] || log "  주의: 그 ${NO_LIVE_URL}건은 유도할 근거가 없어 이 도구로 복구되지 않는다(수동 조사 대상)."
[ "$STUCK_BEFORE_CURSOR" -eq 0 ] || log "  주의: 커서(AFTER_ENDED_AT) 이전에 미복구 후보 ${STUCK_BEFORE_CURSOR}건이 남아 있다 — 완료가 아니며 별도로 처리할 것."
[ "$NO_ENDED_AT" -eq 0 ] || log "  주의: ended_at 이 없는 후보 ${NO_ENDED_AT}건 — 커서 기준을 만들 수 없어 이 도구가 다루지 않는다(수동 조사 대상)."
[ "$SUSPENDED_BEFORE" -eq 0 ] || log "  주의: 배포 이전 시작·정지 상태 ${SUSPENDED_BEFORE}건 — 자동 종료되지 않으므로 수동 처리 대상(완료 조건에는 넣지 않는다)." 

echo "-- SPR-148 다시보기 URL 백필. 검증을 통과한 행만 들어 있다."
echo "--       DEPLOY_TS=$DEPLOY_TS AFTER_ENDED_AT=$AFTER_ENDED_AT CDN_BASE_URL=$CDN_BASE_URL"
echo "-- 행마다 독립 트랜잭션이다 — 중간에 끊겨도 적용된 부분은 유효하고, 재실행해도 안전하다."
echo "-- psql -1 로 적용하지 말 것(파일 전체가 한 트랜잭션이 되어 적용 내내 행 잠금이 유지된다)."
echo "-- 아래 지시는 AUTOCOMMIT 을 off 로 둔 psqlrc 대비용이다. psql -1 은 이걸로 해제되지 않는다."
echo "\\set AUTOCOMMIT on"
echo "SET standard_conforming_strings = on;  -- 이스케이프가 이 설정을 가정한다"

RESULT="$(new_temp)"; CURSOR="$(new_temp)"; SKIPPED_IDS="$(new_temp)"
# 전송 실패 마커는 디렉터리로 둔다 — mktemp 로 이름만 잡고 지우면 그 경로를 남이 재할당할 수 있다.
TRANSPORT_FAIL="$(new_tempdir)/seen"
DELIVERY_FAIL="$(new_tempdir)/seen"
printf '0 0\n' > "$RESULT"

printf '%s\n' "$CANDIDATES" | while IFS='|' read -r room_id live_url row_ended_at; do
    [ -n "$room_id" ] || continue
    printf '%s' "$row_ended_at" > "$CURSOR"
    read -r ok skip < "$RESULT"
    archive_url="$(derive_archive_url "$live_url")"

    if [ -z "$archive_url" ]; then
        log "SKIP $room_id — 유도 규칙에 없는 라이브 URL: $live_url"
    elif has_quote "$archive_url" || has_quote "$room_id"; then
        log "SKIP $room_id — 값에 작은따옴표가 있어 SQL 을 만들지 않는다"
    elif [ "${archive_url#"$CDN_BASE_URL"/}" = "$archive_url" ]; then
        log "SKIP $room_id — CDN_BASE_URL 밖의 URL: $archive_url"
    elif segments="$(verify_archive "$archive_url")"; then  # rc 는 아래 else 에서 다시 본다
        log "OK   $room_id — 세그먼트 ${segments}건 확인: $archive_url"
        # WHERE 에 검증 시점 조건을 되담는다 — 그 사이 행이 바뀌었으면 적용되지 않는다(멱등).
        printf "UPDATE %s SET hls_archive_url = '%s'\n" "$TABLE" "$(sql_escape "$archive_url")"
        printf "  WHERE id = '%s' AND status = 'ENDED'\n" "$(sql_escape "$room_id")"
        printf "    AND hls_url = '%s'\n" "$(sql_escape "$live_url")"
        printf "    AND (hls_archive_url IS NULL OR hls_archive_url = hls_url);\n"
        printf '%s %s\n' "$((ok + 1))" "$skip" > "$RESULT"
        continue
    else
        verify_rc=$?
        case "$verify_rc" in
            2) log "FAIL $room_id — 아카이브 전송 실패(HTTP/타임아웃): $archive_url"; mkdir -p "$TRANSPORT_FAIL" ;;
            3) log "FAIL $room_id — 객체 전달 구성 오류(리다이렉트/Range): CDN 설정을 확인할 것"; mkdir -p "$DELIVERY_FAIL" ;;
            *) log "FAIL $room_id — 아카이브 검증 실패(EVENT/ENDLIST/세그먼트). 행을 건드리지 않는다: $archive_url" ;;
        esac
    fi
    printf '%s ' "$room_id" >> "$SKIPPED_IDS"
    printf '%s %s\n' "$ok" "$((skip + 1))" > "$RESULT"
done

read -r OK_COUNT SKIP_COUNT < "$RESULT"
# 검증 루프는 세그먼트를 전수 확인하느라 길다. 그 사이 시작된 방을 놓치지 않도록 판정 직전에 다시 센다.
# 순서가 중요하다: 미종료를 **먼저**, 후보를 **나중에** 센다.
#   전이가 t1 이전이면 그 행은 t2 의 후보 조회에 반드시 잡히고(구 컬럼 종료라 archive 가 비고 ended_at > 커서),
#   t1 시점에 아직 LIVE 면 UNFINISHED>=1 이라 어차피 10 이 된다.
#   즉 t1 < t2 자체가 "회차 중 종료된 방"의 창을 닫는 장치다. 두 줄을 바꾸면 그 방이 양쪽 모두에서 빠진다.
UNFINISHED="$(count_unfinished)"
SUSPENDED_BEFORE="$(count_suspended)"
log "최종 배포 이전 시작·정지 상태 ${SUSPENDED_BEFORE}건 — 별도 수동 처리 대상"
# 이번 회차가 손대지 못한 분량 = 지금 후보 총수 - 이번에 살펴본 수. BATCH_SIZE 초과분과 새로 생긴 행을 함께 잡는다.
REMAINING="$(count_candidates)"
REMAINING=$((REMAINING - OK_COUNT - SKIP_COUNT))
STUCK_BEFORE_CURSOR="$(count_stuck)"
[ "$REMAINING" -ge 0 ] || REMAINING=0
LAST_ENDED_AT="$(cat "$CURSOR" 2>/dev/null || true)"
[ ! -s "$SKIPPED_IDS" ] || log "건너뛴 방(수동 처리 대상): $(cat "$SKIPPED_IDS")"
# 커서는 "진전이 전혀 없을 때"만 권한다. 정상 회차 뒤에 되먹이면 그 회차의 FAIL 행이 통째로 커서 뒤로 밀린다.
if [ -d "$DELIVERY_FAIL" ]; then
    log "객체 전달 구성 오류 — 커서를 전진시키지 말고 CDN 객체 경로와 Range 응답을 확인할 것. 생성 SQL ${OK_COUNT}건은 별도 검토 필요."
    exit 14
elif [ -d "$TRANSPORT_FAIL" ]; then
    log "전송 실패가 있었다 — CDN/네트워크 문제일 수 있으니 커서를 전진시키지 말고 그대로 재실행할 것."
elif [ -n "$LAST_ENDED_AT" ] && [ "$OK_COUNT" -eq 0 ] && [ "$SKIP_COUNT" -gt 0 ]; then
    log "이번 회차에 진전이 없다. 선두 행이 복구 불가라면 AFTER_ENDED_AT=${LAST_ENDED_AT} 로 다음 회차를 돌릴 것"
    log "  (그 경우 위 건너뛴 방들도 함께 제외되며, 커서 이전 잔여로 따로 보고된다)."
fi
log "검증 통과 ${OK_COUNT}건 / 건너뜀 ${SKIP_COUNT}건 / 배포 경계 미종료 방 ${UNFINISHED}건 / 이번 상한 초과 잔여 ${REMAINING}건"

case "$(decide_exit "$OK_COUNT" "$SKIP_COUNT" "$UNFINISHED" "$REMAINING" "$STUCK_BEFORE_CURSOR")" in
    10) log "재실행 필요 — 건너뛴 행은 원인을 보고 개별 처리하고, 배포 경계 방은 전부 ENDED 가 된 뒤 다시 돌릴 것."
        exit 10 ;;
    11) log "적용 대기 — SQL ${OK_COUNT}건 생성. psql -f 로 적용한 뒤 다시 돌릴 것"
        log "         (건너뜀 ${SKIP_COUNT}건 / 잔여 ${REMAINING}건 / 미종료 ${UNFINISHED}건은 다음 회차가 이어받는다)."
        exit 11 ;;
    13) log "보류 있음 — 이 회차 범위는 끝났지만 커서 이전에 미복구 행 ${STUCK_BEFORE_CURSOR}건이 남았다."
        log "         완료가 아니다. 그 행들을 수동 처리하거나 AFTER_ENDED_AT 을 되돌려 다시 확인할 것."
        exit 13 ;;
    *)  log "백필 완료 조건 충족 — 생성 0건, 남은 후보 0건, 미종료 0건, 커서 뒤 보류 0건." ;;
esac
