package com.sapari.chat.view;

import java.util.Set;

/**
 * 사전 전체 — 정본({@code live_schema.chat_filter_word})을 그대로 읽은 값이다.
 *
 * <p><b>Pod 들이 실제로 집행하는 것과 다를 수 있다.</b> 정본이 바뀌면 스냅샷이 발행되고 Pod 들이
 * 그것을 받아 교체하는데, 그 교체는 거부될 수 있다(항목이 사전 문을 통과하지 못하면 그 Pod 은
 * 이전 필터를 계속 쓴다). 그래서 이 목록은 "무엇을 막기로 했는가" 이고 "무엇이 막히고 있는가" 가
 * 아니다 — 쓰기 경로가 같은 검사를 먼저 돌려 그 간격을 좁히지만, 정본을 직접 고친 경로는
 * 그 검사를 지나지 않는다.
 *
 * @param words     마스킹 대상
 * @param whitelist 마스킹에서 덮어 빼는 정상어
 */
public record FilterWordsView(Set<String> words, Set<String> whitelist) {
}
