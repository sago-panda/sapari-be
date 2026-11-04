package com.sapari.chat.infrastructure.persistence.repository;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatFilterWordRepository} 구현.
 *
 * <p>트랜잭션을 열지 않는다 — 같은 패키지의 {@code ChatKickLogRepositoryImpl} 과 같은 이유로,
 * 쓰기의 경계는 호출자가 정한다. 사전 쓰기에서는 그 경계가 특히 의미가 있다: <b>커밋이 확정된
 * 뒤에</b> 스냅샷을 발행해야 하고, 그 전에 발행하면 롤백된 단어가 함대에 깔린다.
 *
 * <p><b>스테레오타입을 붙이지 않는다.</b> chat-core 를 리액티브 앱과 블로킹 앱이 함께 의존하고,
 * 스캔되는 순간 관계형 DB 가 없는 streaming-app 에서도 빈이 만들어지려 한다 — 그쪽에서는 그 시도가
 * 곧 부팅 실패다. 그래서 live-app 이 {@code @Bean} 으로 등록한다.
 */
@RequiredArgsConstructor
public class ChatFilterWordRepositoryImpl implements ChatFilterWordRepository {

    private final ChatFilterWordJpaRepository jpaRepository;

    /**
     * {@code LinkedHashSet} 으로 받는다 — 질의가 {@code List} 를 주고 정본에 중복이 없음은
     * {@code uk_chat_filter_word} 가 보장하지만, 순서를 잃으면 실패 메시지와 로그에 뜨는 항목
     * 나열이 매 부팅 달라져 두 Pod 의 출력을 눈으로 비교할 수 없다.
     */
    @Override
    public Set<String> findWords(FilterWordKind kind) {
        return new LinkedHashSet<>(jpaRepository.findWordsByKind(kind.name()));
    }

    @Override
    public boolean add(FilterWordKind kind, String word, UUID createdBy) {
        // ON CONFLICT DO NOTHING 은 많아야 한 행에 영향을 준다 — 0이면 이미 있었다는 뜻이다.
        return jpaRepository.insertIfAbsent(kind.name(), word, createdBy) > 0;
    }

    @Override
    public boolean remove(FilterWordKind kind, String word) {
        return jpaRepository.deleteByKindAndWord(kind.name(), word) > 0;
    }

    @Override
    public boolean isEmpty() {
        return jpaRepository.count() == 0;
    }
}
