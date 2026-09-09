#!/bin/sh
# reviewers.yml 에서 CI 가 쓰는 목록을 뽑는다. 손으로 적은 목록은 리뷰어를 추가할 때
# 한 곳을 빠뜨리고, 빠뜨린 쪽이 "없어도 통과" 로 조용히 샌다.
#
# 사용: list-reviewers.sh names   리뷰어 이름 한 줄씩 (에이전트 md 는 .claude/agents/<name>[-ci].md)
#       list-reviewers.sh files   판정 기준 파일 한 줄씩 (공통 방법론 + 각 방법론 + 도메인 체크리스트)

set -eu
exec python3 - "${1:?names 또는 files}" <<'PY'
import sys
sys.path.insert(0, ".claude/scripts")
from anchorlib import reviewers, COMMON_FILE

revs, mode = reviewers(), sys.argv[1]
if mode == "names":
    for r in revs:
        print(r["name"])
elif mode == "files":
    print(COMMON_FILE)
    for r in revs:
        for f in r["files"]:
            print(f)
else:
    sys.exit(f"알 수 없는 모드: {mode} (names | files)")
PY
