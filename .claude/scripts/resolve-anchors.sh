#!/bin/sh
# 변경 파일 목록을 .claude/anchors.yml 과 대조해 활성 항목과 앵커 글롭을 낸다.
#
# 사용: resolve-anchors.sh <변경파일목록>
#   stdout  ACTIVE=<쉼표구분 ID>  ANCHORS=<쉼표구분 글롭>  두 줄
#   stderr  사람이 읽을 요약 (CI 로그용)
#
# 파이썬 표준 라이브러리만 쓴다. CI 리뷰 잡은 node 이미지라 pyyaml 이 없으므로
# yaml 파서를 쓰지 않고 이 파일의 고정된 형태만 읽는다 — anchors.yml 의 구조를
# 바꾸면 여기도 함께 고쳐야 한다.

set -eu
exec python3 - "${1:?변경 파일 목록 경로가 필요합니다}" <<'PY'
import re, sys
sys.path.insert(0, ".claude/scripts")
from anchorlib import logical_lines, items, glob_to_regex

changed = [l.strip() for l in open(sys.argv[1], encoding="utf-8") if l.strip()]



rules, cur, always = [], None, []
for line in logical_lines(".claude/anchors.yml"):
    s = line.strip()
    if s.startswith("always:"):
        always = items(s)
    elif s.startswith("- trigger:"):
        cur = {"trigger": items(s), "anchors": [], "activate": []}
        rules.append(cur)
    elif cur is not None and s.startswith("anchors:"):
        cur["anchors"] = items(s)
    elif cur is not None and s.startswith("activate:"):
        cur["activate"] = items(s)

def matches(path, glob):
    return re.fullmatch(glob_to_regex(glob), path) is not None

# activate 가 비면 그 규칙은 아무것도 켜지 않는데 매칭은 성공한 것처럼 보인다.
# 오타(activete:)나 누락이 여기로 샌다 — 파싱 직후 걸러 낸다.
empty = [r["trigger"][0] for r in rules if not r["activate"]]
if empty:
    print("ERROR: activate 가 비어 있는 규칙: " + ", ".join(empty), file=sys.stderr)
    sys.exit(1)

active, anchors, hit = set(always), set(), []
for r in rules:
    if any(matches(p, g) for p in changed for g in r["trigger"]):
        active.update(r["activate"])
        anchors.update(r["anchors"])
        hit.append(r["trigger"][0])

if not hit:
    hit.append("(매칭 없음 -> always 만)")

# fail-closed. 활성 항목이 비면 프롬프트가 "전부 범위 외" 가 되어, 아무것도 판정하지
# 않은 리뷰가 "이슈 없음" 으로 나간다.
# 다만 이것이 막는 것은 '0건' 뿐이다. 글롭이 전부 오타여도 always 는 남으므로 여기서는
# 걸리지 않는다 — 그쪽은 harness-check 의 글롭 유효성 검사가 담당한다.
if not active:
    print("ERROR: 활성 항목이 비었습니다. anchors.yml 의 always 를 확인하세요.", file=sys.stderr)
    sys.exit(1)

print("ACTIVE=" + ",".join(sorted(active)))
print("ANCHORS=" + ",".join(sorted(anchors)))

print(f"변경 파일 {len(changed)}건 -> 매칭 규칙 {len(hit)}개", file=sys.stderr)
for h in hit:
    print(f"  - {h}", file=sys.stderr)
print(f"활성 항목 {len(active)}건: {' '.join(sorted(active))}", file=sys.stderr)
if anchors:
    print(f"동봉 앵커: {' '.join(sorted(anchors))}", file=sys.stderr)
PY
