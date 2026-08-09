#!/bin/sh
# sapari-reviewer.md 의 "Enforced by ArchUnit" 목록이 실제 규칙과 맞는지 검사한다.
#
# 목록이 낡으면 두 방향으로 새는데, 둘 다 조용히 샌다.
#   짧으면 -> 리뷰어가 이미 강제되는 규칙을 모르고 중복 지적한다
#   길면   -> 없는 규칙을 근거로 "빌드가 잡아준다"고 넘긴다
#
# 개수가 아니라 '메서드명 집합' 을 대조한다. 개수만 세면 규칙 하나를 지우고 다른 하나를
# 추가했을 때 통과하는데, 그때 통과 로그는 "동기화됨" 으로 읽혀 오히려 해롭다.
# 설명 문구까지 대조하지는 않는다 — 표현이 달라 기계 대조가 안 되고, 억지로 맞추면
# 문구를 스크립트에 맞춰 쓰게 된다. 이름이 일치하면 사람이 문구를 검토할 근거는 선다.

set -eu
exec python3 - <<'PY'
import re, sys

TEST_FILE = "architecture-test/src/test/java/com/sapari/architecture/ArchitectureTest.java"
DOC_FILE = ".claude/agents/sapari-reviewer.md"

# @Test 바로 다음에 오는 메서드명
src = open(TEST_FILE, encoding="utf-8").read()
tests = set(re.findall(r"@Test\s+(?:[\w@\s]*?\s)?void\s+(\w+)\s*\(", src))

# "## Enforced by ArchUnit" 절의 번호 목록에서 줄 끝의 백틱 메서드명
listed, inside = set(), False
for line in open(DOC_FILE, encoding="utf-8"):
    if line.startswith("## Enforced by ArchUnit"):
        inside = True
        continue
    if inside and line.startswith("## "):
        break
    if inside:
        m = re.match(r"\d+\.\s.*—\s`(\w+)`\s*$", line.rstrip())
        if m:
            listed.add(m.group(1))

missing = tests - listed      # 규칙은 있는데 목록에 없다
stale = listed - tests        # 목록에는 있는데 규칙이 없다

if missing or stale:
    print(f"ERROR: ArchUnit 규칙과 {DOC_FILE} 목록이 어긋납니다.")
    if missing:
        print(f"  목록에 없는 규칙 {len(missing)}개: {' '.join(sorted(missing))}")
    if stale:
        print(f"  규칙이 사라진 목록 항목 {len(stale)}개: {' '.join(sorted(stale))}")
    sys.exit(1)

print(f"ArchUnit 규칙 {len(tests)}개 — 목록과 이름 단위로 일치")
PY
