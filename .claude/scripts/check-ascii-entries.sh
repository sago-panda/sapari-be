#!/bin/sh
# 순 ASCII 욕설 항목이 평범한 영단어의 부분문자열이 되는지 영어 사전 전수로 대조한다.
#
# 테스트가 아니라 스크립트인 이유: 영단어 목록이 저장소에 없고 OS 제공 파일에 기댄다.
# 테스트로 들이면 CI 이미지에 그 파일이 없을 때 조용히 초록이 된다 — 가드가 있고 통과하는데
# 아무것도 재지 않는 상태이고, 이 기능이 반복해서 밟은 모양이 정확히 그것이다.
# 스크립트는 목록이 없으면 아래처럼 시끄럽게 실패한다.
#
# 들어오는 순간을 막는 것은 ProfanityDictionaryTest 의 집합 고정(28건)이다. 이 스크립트는
# 그 집합을 바꿀 때 근거를 다시 재기 위한 것이고, 사전 머리글의 '영단어 5건' 문단이 마지막
# 실행 결과다. 상시 CI 가 아니라 사전을 손댈 때 돌린다.
#
# 사용: sh .claude/scripts/check-ascii-entries.sh [영단어목록경로]

set -eu
WORDLIST="${1:-/usr/share/dict/web2}"

if [ ! -r "$WORDLIST" ]; then
  echo "영단어 목록을 읽을 수 없다: $WORDLIST" >&2
  echo "  macOS 는 /usr/share/dict/web2 를 기본 제공한다. 리눅스는 'words' 패키지." >&2
  echo "  경로를 인자로 넘길 수도 있다: sh $0 /path/to/words" >&2
  exit 2
fi

exec python3 - "$WORDLIST" <<'PY'
import re, sys

WORDS = "modules/chat/chat-core/src/main/resources/chat/profanity-words.txt"

entries = sorted({
    line.strip().lower()
    for line in open(WORDS, encoding="utf-8")
    if line.strip() and not line.strip().startswith("#")
    and re.fullmatch(r"[A-Za-z]+", line.strip())
})
corpus = [w.strip().lower() for w in open(sys.argv[1], encoding="utf-8", errors="ignore") if w.strip()]

hits = {e: [w for w in corpus if e in w and w != e] for e in entries}
hits = {e: m for e, m in hits.items() if m}

print(f"순 ASCII 항목 {len(entries)}건 · 영단어 {len(corpus):,}개 ({sys.argv[1]})")
for entry, matches in sorted(hits.items(), key=lambda kv: -len(kv[1])):
    sample = ", ".join(sorted(matches, key=len)[:6])
    print(f"  {entry:<8} {len(matches):4d}건  {sample}")
print(f"→ 영단어에 걸리는 항목 {len(hits)}/{len(entries)}")
print()
print("판단은 사람이 한다 — 걸린다고 전부 빼는 것이 아니라, 한국어 라이브 커머스 본문에")
print("나올 말인지를 본다. passivation(siva) · BusinessException(sex) 은 그래서 뺐고,")
print("pussycat · pennisetum 류는 그래서 남겼다. 바꾸면 사전 머리글과 ASCII 집합 테스트를")
print("같이 고친다.")
PY
