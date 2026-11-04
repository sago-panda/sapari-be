package com.sapari.chat.application.service;

import java.util.Set;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.chat.domain.model.ChatConstants;
import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;

import lombok.RequiredArgsConstructor;

/**
 * 번들 사전을 정본에 한 번 심는다 — <b>저자를 한 곳으로 모으는 작업</b>이다.
 *
 * <p>번들 {@code .txt} 는 이제 시드의 원본이고, 심은 뒤로는 테이블이 저자다. 그래야 운영자가
 * 추가한 단어와 번들 단어가 같은 목록에 있고, "지금 무엇을 막는가" 를 한 곳에서 읽을 수 있다 —
 * 두 곳에 두면 갈라지는 순간 어느 쪽이 참인지 알 수 없다.
 *
 * <p><b>비었을 때만 심는다.</b> 매 부팅에 다시 밀어 넣으면 운영자가 뺀 단어가 배포마다
 * 되살아나고, 그러면 삭제가 작동하지 않는 것과 같다.
 *
 * <p>⚠️ "비었다" 는 <b>테이블 전체</b>가 비었다는 뜻이다({@code count() == 0}) — "욕설이 0개" 가
 * 아니다. 그래서 <i>화이트리스트 행은 있는데 욕설이 0개</i>인 상태는 다시 심지 않는다. 앱
 * 경로로는 그 상태에 닿을 수 없다: 욕설 집합을 비우는 제거가 거부되고
 * ({@code ManageFilterWordsService.guardRemoval}) 채우는 것은 이 시드뿐이다. 정본을 직접 고친
 * 경우에만 생기고, 그때도 <b>안전하게 막힌다</b> — 발행된 0개 스냅샷은 받는 쪽 문이 거부해 각
 * Pod 이 이전 사전을 계속 쓰고, 첫 단어를 넣으려는 ADMIN 쓰기는 409 다. 복구는 그 행들을 지워
 * 테이블을 비우고 이 앱을 다시 띄우는 것이다.
 *
 * <p><b>한 트랜잭션이어야 한다.</b> 나누면 Pod 이 중간에 죽을 때 테이블이 "비지 않았지만 절반인"
 * 상태로 남고, 그 뒤로는 비어 있지 않으니 다시 심지 않는다 — 절반짜리 사전이 영구히 정본이 된다.
 *
 * <p>별도 빈인 이유는 {@code @Transactional} 이 프록시로 걸리려면 그래야 하기 때문이다. 같은
 * 클래스 안의 메서드로 두면 자기 호출이라 프록시를 타지 않고, 붙여 놓고 안 걸리는 상태가 된다
 * (같은 패키지의 {@code ChatKickRecorder} 와 같은 이유다).
 */
@RequiredArgsConstructor
public class ChatFilterWordSeedRecorder {

    private final ChatFilterWordRepository repository;

    /**
     * 정본이 비어 있으면 두 집합을 심는다.
     *
     * <p>{@code REQUIRED} 로 둔다 — 부팅 러너가 트랜잭션 밖에서 부르므로 여기서 새로 열린다.
     * 쓰기가 {@code ON CONFLICT DO NOTHING} 이라 live-app Pod 여러 개가 동시에 "비었다" 를 보고
     * 함께 심어도 결과가 같다. 그래서 락을 두지 않았다 — 락은 결과가 달라질 때 필요하다.
     *
     * @return 실제로 심었는가. {@code false} 는 이미 있었다는 뜻이고 오류가 아니다
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean seedIfEmpty(Set<String> words, Set<String> whitelist) {
        if (!repository.isEmpty()) {
            return false;
        }
        for (String word : words) {
            repository.add(FilterWordKind.PROFANITY, word, ChatConstants.SYSTEM_SENDER_ID);
        }
        for (String entry : whitelist) {
            repository.add(FilterWordKind.WHITELIST, entry, ChatConstants.SYSTEM_SENDER_ID);
        }
        return true;
    }
}
