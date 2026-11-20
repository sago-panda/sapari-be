#!/bin/sh
# anchors.yml 이 켜는 항목 ID 가 리뷰어 방법론 md 에 실재하는지, 그리고 정의된 파일이
# 그 접두사의 소유자(reviewers.yml)인지 검사한다.
#
# ID 는 정의(방법론 md)와 참조(anchors.yml)가 다른 파일에 있다. 오타나 이름 변경이
# 조용히 새는데, 새는 방향이 둘 다 나쁘다.
#   없는 ID 를 켠다      -> 그 규칙은 아무 일도 하지 않는다
#   정의만 있고 안 켜진다 -> 어떤 MR 에서도 발화하지 않는다 (LGU-2 문서의
#                          "한 번도 발화하지 않은 항목" 과 같은 문제)
# 앞은 실패시키고, 뒤는 경고만 한다 — 의도적으로 안 켜는 항목이 있을 수 있다.
#
# 소유권 검사도 여기서 한다. 어떤 md 가 남의 접두사로 항목을 정의하면 두 리뷰어가
# 같은 ID 를 다른 뜻으로 쓰게 되고, 병합 단계의 ID 기준 중복 제거가 어긋난다.

set -eu
exec python3 - <<'PY'
import re, sys
sys.path.insert(0, ".claude/scripts")
from anchorlib import logical_lines, reviewers

revs = reviewers()
prefixes = sorted({p for r in revs for p in r["prefixes"]})
ID_RX = re.compile(r"\s*(?:[-*]|\d+\.)\s+`((?:%s)-\d{2})`" % "|".join(map(re.escape, prefixes)))

defined, misowned = set(), []
for r in revs:
    for path in r["files"]:
        with open(path, encoding="utf-8") as f:
            for line in f:
                # 정의는 항상 목록 항목 머리의 백틱 ID 다: "- `CONV-09` **..." / "9. `SEC-09` **..."
                m = ID_RX.match(line)
                if not m:
                    continue
                item = m.group(1)
                if item.rsplit("-", 1)[0] not in r["prefixes"]:
                    misowned.append((item, path, r["name"]))
                defined.add(item)

referenced = set()
for line in logical_lines(".claude/anchors.yml"):
    if re.match(r"\s*(activate:|always:)", line.strip()):
        referenced.update(re.findall(r"(?:%s)-\d{2}" % "|".join(map(re.escape, prefixes)), line))

# SEC-00 / CONV-11 은 "어느 항목에도 안 걸리는 발견" 을 담는 자리라 정의부에 목록으로
# 존재하지 않는다. 방법론 md 의 출력 절이 문장으로 규정한다.
CATCH_ALL = {"SEC-00", "CONV-11"}
defined |= CATCH_ALL

if misowned:
    print("ERROR: 소유하지 않은 접두사의 항목을 정의하는 파일이 있습니다.")
    for item, path, name in misowned:
        print(f"  {item} in {path} ({name} 소유 아님)")
    sys.exit(1)

unknown = referenced - defined
if unknown:
    print(f"ERROR: anchors.yml 이 정의에 없는 ID 를 켠다: {' '.join(sorted(unknown))}")
    print("  정의는 .claude/reviewers.yml 이 가리키는 방법론 md 에 있습니다.")
    sys.exit(1)

never = defined - referenced - CATCH_ALL
if never:
    print(f"경고: 어떤 경로에서도 켜지지 않는 항목: {' '.join(sorted(never))}")
    print("  anchors.yml 에 트리거를 넣거나, 쓰지 않는 항목이면 md 에서 지우세요.")

print(f"항목 ID {len(defined - CATCH_ALL)}개 정의, {len(referenced - CATCH_ALL)}개 활성 — 참조·소유 무결 (접두사 {' '.join(prefixes)})")
PY
