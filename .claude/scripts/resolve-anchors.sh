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
import fnmatch, re, sys

changed = [l.strip() for l in open(sys.argv[1], encoding="utf-8") if l.strip()]
doc = open(".claude/anchors.yml", encoding="utf-8").read()

def items(line):
    return [x.strip().strip('"') for x in line[line.index("[") + 1:line.rindex("]")].split(",") if x.strip()]

rules, cur, fallback, always = [], None, [], []
for line in doc.splitlines():
    s = line.strip()
    if s.startswith("on_no_match:"):
        fallback = items(s)
    elif s.startswith("always:"):
        always = items(s)
    elif s.startswith("- trigger:"):
        cur = {"trigger": items(s), "anchors": [], "activate": []}
        rules.append(cur)
    elif cur is not None and s.startswith("anchors:"):
        cur["anchors"] = items(s)
    elif cur is not None and s.startswith("activate:"):
        cur["activate"] = items(s)

def matches(path, glob):
    # fnmatch 는 '**' 를 모르므로 정규식으로 옮긴다. '**/' 는 0개 이상의 디렉터리.
    rx = re.escape(glob).replace(r"\*\*/", "(?:.*/)?").replace(r"\*\*", ".*").replace(r"\*", "[^/]*").replace(r"\?", "[^/]")
    return re.fullmatch(rx, path) is not None

active, anchors, hit = set(always), set(), []
for r in rules:
    if any(matches(p, g) for p in changed for g in r["trigger"]):
        active.update(r["activate"])
        anchors.update(r["anchors"])
        hit.append(r["trigger"][0])

if not hit:
    active.update(fallback)
    hit.append("(매칭 없음 -> 기본 집합)")

# fail-closed. 활성 항목이 비면 프롬프트가 "전부 범위 외" 가 되어, 아무것도
# 판정하지 않은 리뷰가 "이슈 없음" 으로 나간다. 침묵보다 실패가 낫다.
if not active:
    print("ERROR: 활성 항목이 비었습니다. anchors.yml 의 always/on_no_match 를 확인하세요.",
          file=sys.stderr)
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
