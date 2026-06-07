/**
 * 순수 도메인 정책 — 상태·외부 I/O 없이 입력만으로 결정되는 규칙.
 *
 * <p>{@code ChatPermissionPolicy}(전송·강퇴 권한)처럼 단위 테스트만으로 전부 검증되는 정책을 둔다.
 * 거부 판정은 boolean(가드)으로 돌려주고, 예외 전환은 호출 서비스가 맡는다.
 */
package com.sapari.chat.domain.service;
