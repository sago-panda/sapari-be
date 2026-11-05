package com.sapari.chat.application.port;

import java.util.Set;

/**
 * 정본에서 떠 온 사전 한 장 — <b>버전과 두 집합이 한 덩어리</b>다.
 *
 * <p>셋을 한 레코드로 묶는 것이 이 타입의 요점이다. 버전을 따로 들고 다니면 "이 버전의 사전" 이
 * 아니라 "어떤 버전과 어떤 사전" 이 되고, 둘이 어긋난 조합으로 교체하는 경로가 생긴다 —
 * 그러면 단조 비교가 거짓을 지킨다(더 낮은 버전 번호로 더 새 사전을 거부하거나 반대).
 *
 * <p>그래서 어댑터는 Redis 키 <b>하나</b>에서 셋을 함께 읽는다. 읽기가 원자인 것이 구조로
 * 성립하고, 두 번 읽는 사이에 바뀌는 창이 없다.
 *
 * @param version    정본 쪽 단조 카운터. 교체는 이 수가 지금 것보다 <b>클 때만</b> 한다
 * @param words      마스킹 대상
 * @param whitelist  마스킹에서 덮어 빼는 정상어
 */
public record ProfanityDictionarySnapshot(long version, Set<String> words, Set<String> whitelist) {
}
