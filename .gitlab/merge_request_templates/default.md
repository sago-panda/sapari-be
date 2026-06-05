<!-- 제목: [SPR-XXX] <type>(<scope>): <한글 요약>   예) [SPR-123] feat(live): LiveKit HLS Egress 통합 -->

## 관련 이슈

<!-- 연관 Jira 티켓 (예: SPR-30) -->
SPR-

## 작업 내용

<!-- 무엇을 했는지 한두 줄 -->

## 배경 / 이유

<!-- 왜 이 변경이 필요한지 -->

## 변경 유형

- [ ] feat — 기능 추가
- [ ] fix — 버그 수정
- [ ] refactor — 리팩터링 (동작 변화 없음)
- [ ] test — 테스트 추가/수정
- [ ] docs / chore — 문서·설정

## 테스트

<!-- 어떻게 검증했는지 / 추가한 테스트 / 실행 결과 -->

## 체크리스트

- [ ] 단위 테스트 추가·수정 및 통과 (`./gradlew :modules:<X>:<X>-core:test`)
- [ ] 모듈 의존 규칙 통과 — cross-domain은 `*-api`/도메인 이벤트 경유, 다른 도메인 `-core` 직접 의존 없음
- [ ] 도메인 모델 불변 유지 — `@Entity`·가변 필드 없음, 시간은 `TimeProvider`
- [ ] 시크릿/`.env`/자격증명 미포함
- [ ] Breaking change 시 BREAKING CHANGE 표기
