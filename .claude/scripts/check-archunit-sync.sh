#!/bin/sh
# sapari-reviewer.md 의 "Enforced by ArchUnit" 목록이 실제 규칙 수와 맞는지 검사한다.
#
# 목록이 낡으면 두 방향으로 새는데, 둘 다 조용히 새는 것이 문제다.
#   짧으면 -> 리뷰어가 이미 강제되는 규칙을 모르고 중복 지적한다
#   길면   -> 없는 규칙을 근거로 "빌드가 잡아준다"고 넘긴다
#
# 규칙 '내용'까지 대조하지는 않는다. 메서드명과 목록 문구는 표현이 달라 기계 대조가 안 되고,
# 억지로 맞추면 문구를 스크립트에 맞춰 쓰게 된다. 개수 불일치만으로 사람을 부르는 것으로 충분하다.

set -eu

TEST_FILE=architecture-test/src/test/java/com/sapari/architecture/ArchitectureTest.java
DOC_FILE=.claude/agents/sapari-reviewer.md

# @Test 를 센다. 들여쓰기나 메서드 시그니처 형태에 기대지 않기 위해서다.
rules=$(grep -c '^[[:space:]]*@Test' "$TEST_FILE")

# "## Enforced by ArchUnit" 절 안의 번호 목록만 센다 (다음 '## ' 헤딩에서 끊는다)
listed=$(awk '
    /^## Enforced by ArchUnit/ { inside = 1; next }
    inside && /^## / { exit }
    inside && /^[0-9]+\. / { n++ }
    END { print n + 0 }
' "$DOC_FILE")

if [ "$rules" -ne "$listed" ]; then
    echo "ERROR: ArchUnit 규칙 $rules 개, $DOC_FILE 목록 $listed 개 — 불일치"
    echo "  $TEST_FILE 의 @Test 를 보고 목록을 맞춰 주세요."
    exit 1
fi

echo "ArchUnit 규칙 $rules 개 — 목록과 일치"
