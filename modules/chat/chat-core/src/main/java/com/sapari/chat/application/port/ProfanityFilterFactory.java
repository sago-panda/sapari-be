package com.sapari.chat.application.port;

import java.util.Set;

import com.sapari.chat.domain.rule.ProfanityFilter;

/**
 * 두 집합을 <b>사전의 문</b>에 통과시켜 필터로 만든다.
 *
 * <p><b>문이 포트인 이유.</b> 사전은 세 입구로 들어온다 — 번들 리소스 · 설정 CSV · 정본 스냅샷.
 * 앞의 둘은 부팅 때 호스트 앱이 직접 들여보내지만 세 번째는 런타임에 전파돼 오고, 그것을 받는
 * 쪽은 <b>같은 검사</b>를 지나야 한다: 보이지 않는 글자가 든 항목은 평범한 입력을 영원히 못
 * 잡고(막은 것처럼 보인다), 예산을 넘는 항목 하나는 함대 처리량을 떨어뜨리고, 욕설 그 자체인
 * 화이트리스트 항목 하나는 그 욕설을 사전 전체에서 푼다.
 *
 * <p>그 검사의 구현(리소스 파싱·정규화·유니코드 분류)은 어댑터의 몫이라 여기서는 결과만 받는다.
 *
 * <p><b>거부는 예외다.</b> 호출자가 그것을 잡아 <b>교체를 포기하고 이전 필터를 계속 쓴다</b> —
 * 부팅 입구가 부팅을 세우는 것과 방향이 다른데, 도는 Pod 을 사전 한 줄의 오타로 죽이는 것이
 * 그 오타보다 나쁘기 때문이다.
 */
public interface ProfanityFilterFactory {

    /**
     * @param origin 실패 메시지에 실을 출처 이름(예: {@code "chat:dict"}). 세 입구 중 어디서
     *               떨어졌는지가 당직자에게 가야 한다
     * @throws IllegalStateException 항목이 문을 통과하지 못했을 때. 메시지에 무엇이 왜
     *         떨어졌는지가 들어 있어야 한다 — 호출자가 그것을 로그에 그대로 싣는다
     */
    ProfanityFilter from(String origin, Set<String> words, Set<String> whitelist);
}
