#!/bin/sh
# anchors.yml 의 글롭이 저장소의 실제 파일을 잡는지 검사한다.
#
# 이것이 막는 실패는 fail-closed 가 못 막는 쪽이다.
# 활성 항목이 0건이 되는 경우는 리뷰 잡이 잡지만, 글롭이 전부 오타여서 어떤 규칙도
# 안 걸리는 경우는 always 5건이 남아 초록불로 "이슈 없음" 이 게시된다.
# 오타는 결정론적으로 판정되므로, 리뷰 시점이 아니라 여기서 토큰 없이 잡는다.
#
# "이 MR 에서 매칭 0건" 은 검사하지 않는다 — 문서만 고친 MR 은 정상이다.
# 검사 대상은 "저장소 전체에서 0건" 이다.

set -eu
exec python3 - <<'PY'
import re, subprocess, sys
sys.path.insert(0, ".claude/scripts")
from anchorlib import logical_lines, glob_to_regex

# split() 이 아니라 splitlines(). 공백이 든 경로가 두 개로 쪼개져 매칭이 어긋난다.
tracked = subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout.splitlines()

def matches_any(glob):
    rx = re.compile(glob_to_regex(glob))
    return any(rx.fullmatch(p) for p in tracked)



dead, bad_rx, cold_rx = [], [], []
for line in logical_lines(".claude/anchors.yml"):
    s = line.strip()
    if s.startswith("content:"):
        # content: 는 정규식이다. 컴파일이 안 되면 resolve-anchors 가 리뷰 시점에 죽으므로
        # 여기서 잡는다. 저장소에 아직 없는 구문(@Async 등)을 미리 겨냥하는 것은 정상이라
        # 0건 매칭은 경고만 한다 — 글롭과 달리 오타를 결정론적으로 가릴 수 없다.
        for rx in re.findall(r'"([^"]+)"', s):
            try:
                crx = re.compile(rx)
            except re.error as e:
                bad_rx.append((rx, str(e)))
                continue
            if not any(crx.search(open(p, encoding="utf-8", errors="replace").read())
                       for p in tracked if p.endswith(".java")):
                cold_rx.append(rx)
        continue
    if not (s.startswith("- trigger:") or s.startswith("anchors:")):
        continue
    kind = "trigger" if s.startswith("- trigger:") else "anchor"
    for glob in re.findall(r'"([^"]+)"', s):
        if not matches_any(glob):
            dead.append((kind, glob))

if bad_rx:
    print("ERROR: content: 에 컴파일되지 않는 정규식이 있습니다.")
    for rx, err in bad_rx:
        print(f"  {rx!r}: {err}")
    sys.exit(1)
if cold_rx:
    print("경고: 저장소의 어떤 .java 도 잡지 못하는 content 패턴: " + " ".join(repr(r) for r in cold_rx))

if dead:
    print("ERROR: 저장소의 어떤 파일도 잡지 못하는 글롭이 있습니다.")
    for kind, glob in dead:
        print(f"  [{kind}] {glob}")
    print("  오타이거나, 저장소 구조가 바뀌어 규칙이 낡은 것입니다.")
    print("  아직 존재하지 않는 코드를 미리 겨냥한 글롭이라면 주석으로 남기고 규칙에서 빼세요.")
    sys.exit(1)

print("anchors.yml 글롭 — 전부 실재 파일에 매칭 · content 정규식 — 전부 컴파일됨")
PY
