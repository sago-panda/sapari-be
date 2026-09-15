#!/bin/sh
# 변경 파일 목록을 .claude/anchors.yml 과 대조해 활성 항목·앵커 글롭·띄울 리뷰어를 낸다.
#
# 사용: resolve-anchors.sh <변경파일목록>
#   stdout  ACTIVE=<쉼표구분 ID>  ANCHORS=<쉼표구분 글롭>  REVIEWERS=<쉼표구분 리뷰어>  세 줄
#           리뷰어는 reviewers.yml 의 name. 도메인 리뷰어는 name:<domain> (예: domain-reviewer:live)
#   stderr  사람이 읽을 요약 (CI 로그용)
#
# 파이썬 표준 라이브러리만 쓴다. CI 리뷰 잡은 node 이미지라 pyyaml 이 없으므로
# yaml 파서를 쓰지 않고 이 파일의 고정된 형태만 읽는다 — anchors.yml 의 구조를
# 바꾸면 여기도 함께 고쳐야 한다.

set -eu
exec python3 - "${1:?변경 파일 목록 경로가 필요합니다}" <<'PY'
import re, sys
sys.path.insert(0, ".claude/scripts")
from anchorlib import logical_lines, items, glob_to_regex, reviewers, prefix_owner

changed = [l.strip() for l in open(sys.argv[1], encoding="utf-8") if l.strip()]



rules, cur, always = [], None, []
for line in logical_lines(".claude/anchors.yml"):
    s = line.strip()
    if s.startswith("always:"):
        always = items(s)
    elif s.startswith("- trigger:"):
        cur = {"trigger": items(s), "content": [], "anchors": [], "activate": []}
        rules.append(cur)
    elif cur is not None and s.startswith("content:"):
        cur["content"] = items(s)
    elif cur is not None and s.startswith("anchors:"):
        cur["anchors"] = items(s)
    elif cur is not None and s.startswith("activate:"):
        cur["activate"] = items(s)

def matches(path, glob):
    return re.fullmatch(glob_to_regex(glob), path) is not None

# content: 는 trigger 를 좁힌다 — 경로가 맞고, 그 파일 본문에 패턴 하나라도 있어야 걸린다.
# 동시성 코드(@Async, synchronized, …)는 경로에 드러나지 않아서 경로만으로는
# "서비스가 바뀌면 항상" 이 되고, 그건 매 MR 에 무의미한 보고를 붙이는 것이다.
# 삭제된 파일은 본문이 없으므로 걸리지 않는다.
def content_hit(path, patterns):
    if not patterns:
        return True
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return False
    return any(re.search(p, text) for p in patterns)

# activate 가 비면 그 규칙은 아무것도 켜지 않는데 매칭은 성공한 것처럼 보인다.
# 오타(activete:)나 누락이 여기로 샌다 — 파싱 직후 걸러 낸다.
empty = [r["trigger"][0] for r in rules if not r["activate"]]
if empty:
    print("ERROR: activate 가 비어 있는 규칙: " + ", ".join(empty), file=sys.stderr)
    sys.exit(1)

active, anchors, hit = set(always), set(), []
for r in rules:
    if any(matches(p, g) and content_hit(p, r["content"]) for p in changed for g in r["trigger"]):
        active.update(r["activate"])
        anchors.update(r["anchors"])
        hit.append(r["trigger"][0] + (" +content" if r["content"] else ""))

if not hit:
    hit.append("(매칭 없음 -> always 만)")

# fail-closed. 활성 항목이 비면 프롬프트가 "전부 범위 외" 가 되어, 아무것도 판정하지
# 않은 리뷰가 "이슈 없음" 으로 나간다.
# 다만 이것이 막는 것은 '0건' 뿐이다. 글롭이 전부 오타여도 always 는 남으므로 여기서는
# 걸리지 않는다 — 그쪽은 harness-check 의 글롭 유효성 검사가 담당한다.
if not active:
    print("ERROR: 활성 항목이 비었습니다. anchors.yml 의 always 를 확인하세요.", file=sys.stderr)
    sys.exit(1)

# 활성 항목의 접두사 -> 소유 리뷰어. 리뷰어는 자기 항목이 하나라도 켜졌을 때만 뜬다.
# always 에 CONV/SEC 가 있으므로 일반·보안은 항상, 나머지는 걸릴 때만 — 리뷰어를
# 늘려도 토큰이 리뷰어 수에 비례해 늘지 않는 이유가 이것이다.
owner = prefix_owner(reviewers())
selected = []
for item in sorted(active):
    prefix = item.rsplit("-", 1)[0]
    r = owner.get(prefix)
    if r is None:
        # check-item-ids 가 빌드에서 잡지만, base 고정 후 어긋난 경우를 위해 여기서도 막는다.
        print(f"ERROR: 활성 항목 {item} 의 접두사를 소유한 리뷰어가 reviewers.yml 에 없다", file=sys.stderr)
        sys.exit(1)
    tag = f"{r['name']}:{prefix.lower()}" if r["domains"] else r["name"]
    if tag not in selected:
        selected.append(tag)

print("ACTIVE=" + ",".join(sorted(active)))
print("ANCHORS=" + ",".join(sorted(anchors)))
print("REVIEWERS=" + ",".join(selected))

print(f"변경 파일 {len(changed)}건 -> 매칭 규칙 {len(hit)}개", file=sys.stderr)
for h in hit:
    print(f"  - {h}", file=sys.stderr)
print(f"활성 항목 {len(active)}건: {' '.join(sorted(active))}", file=sys.stderr)
if anchors:
    print(f"동봉 앵커: {' '.join(sorted(anchors))}", file=sys.stderr)
print(f"리뷰어 {len(selected)}개: {' '.join(selected)}", file=sys.stderr)
PY
