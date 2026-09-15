---
name: review
description: sapari-be 코드 리뷰를 돌린다 — 변경 경로를 .claude/anchors.yml 과 대조해 켤 항목과 띄울 리뷰어(general/security/concurrency/domain)를 결정론적으로 고르고, 병렬로 실행해 하나의 보고서로 병합한다. "리뷰어 돌려줘", "리뷰해줘", "/review" 에 쓴다. 인자: 없음(작업 트리) | branch(MR 전체) | tree(전체 트리 readiness).
---

# /review — 리뷰어 오케스트레이션

어떤 리뷰어를 띄울지는 **사람이나 모델이 고르지 않는다.** `resolve-anchors.sh` 가 변경 경로(+본문)로
활성 항목을 정하고, 항목 접두사의 소유자(`.claude/reviewers.yml`)가 곧 띄울 리뷰어다. CI(`mr-review.yml`)도
같은 스크립트의 같은 줄을 읽으므로 로컬과 CI 가 같은 조합을 돈다.

## 절차

1. **변경 파일 목록** → 스크래치패드의 `changed.txt` (한 줄에 경로 하나).
   - 기본(인자 없음): `git diff --name-only` + `git diff --staged --name-only` + `git ls-files --others --exclude-standard`
     의 합집합. 비어 있으면 "변경 없음" 이라 말하고 멈춘다.
   - `branch`: `git diff --name-only origin/dev...HEAD` (세 점 — merge-base 기준).
   - `tree`: `git ls-files '*.java' 'db/migration/' '*application*.y*ml'` — 전체 트리 readiness 패스.
2. **라우팅**: `sh .claude/scripts/resolve-anchors.sh changed.txt` 를 실행하고 stdout 의 `ACTIVE=`, `ANCHORS=`,
   `REVIEWERS=` 세 줄을 읽는다. stderr 의 요약(매칭 규칙·활성 항목·리뷰어)을 사용자에게 **그대로** 보여준다 —
   "이 리뷰에서 인가 검사가 돌긴 했나" 에 답하는 기록이다. 스크립트가 실패하면 리뷰하지 않고 오류를 보여준다.
3. **실행**: `REVIEWERS=` 의 항목마다 Agent 를 **하나의 메시지에서 병렬로** 띄운다. `subagent_type` 은 항목의
   이름(`sapari-reviewer`, `security-reviewer`, `concurrency-reviewer`, `domain-reviewer`). `name:domain`
   형식(`domain-reviewer:live`)이면 프롬프트에 `domain=live` 를 넣는다. 각 프롬프트에는 다음을 넣는다:
   - 리뷰 모드(작업 트리 / `branch` 이면 base SHA / `tree`)
   - `Activated items for this change: <ACTIVE>` — 자기 접두사의 것만 판정하고 각각 결과를 보고할 것,
     범위 밖은 Critical/High 만 소유자 ID 로 보고할 것(공통 방법론의 Scope 절 그대로)
   - `Anchor globs: <ANCHORS>` — diff 가 안 건드려도 읽을 것, 못 읽으면 `증거부족`
   - `domain=<x>` (domain-reviewer 만)
4. **병합**: 결과를 (항목 ID, `path:line`) 로 중복 제거한다 — 같은 줄이라도 ID 가 다르고 결과(consequence)가
   다르면 둘 다 남긴다. 심각도 내림차순, **상한 10건**: Critical/High 는 전부 남기고 나머지로 채우며, 빠진
   건수와 심각도를 명시한다. 각 발견은 `confirmed / uncertain / 증거부족` 상태를 유지한다. 한국어로,
   칭찬·코드 재서술 없이. 이슈가 없으면 리뷰어별로 "이슈 없음" 을 한 줄씩 남긴다.
5. **수정하지 않는다.** 리뷰어는 READ-ONLY 이고 이 스킬도 그렇다. 고칠지는 사용자가 정한다.

## 하지 말 것
- `REVIEWERS=` 에 없는 리뷰어를 "혹시 몰라서" 추가로 띄우지 않는다. 그게 필요하면 `anchors.yml` 의 규칙이
  빠진 것이고, 그쪽을 고치는 것이 답이다(그래야 CI 도 같이 켜진다).
- 리뷰어 출력을 요약하면서 ID 나 `path:line` 을 떼지 않는다 — 재리뷰가 ID 로 매칭한다.
