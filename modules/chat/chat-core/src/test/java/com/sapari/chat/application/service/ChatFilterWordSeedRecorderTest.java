package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.domain.model.ChatConstants;
import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;

/**
 * 번들 시드 — <b>"비었을 때만" 이 삭제를 작동하게 만든다</b>는 것을 잰다.
 *
 * <p>매 부팅에 번들을 다시 밀어 넣으면 운영자가 뺀 단어가 배포마다 되살아난다. 그러면
 * {@code remove} 가 작동하지 않는 것과 같고, 증상이 "지웠는데 돌아온다" 라 원인이 시드 쪽으로
 * 수렴하지 않는다 — 배포와 삭제 사이의 시간차만큼 늦게 나타나기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("번들 시드 — 비었을 때만 심는다")
class ChatFilterWordSeedRecorderTest {

    @Mock
    private ChatFilterWordRepository repository;

    private ChatFilterWordSeedRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ChatFilterWordSeedRecorder(repository);
    }

    @Test
    @DisplayName("비어 있으면 두 집합을 심는다 — 작성자는 서버다")
    void seedsBothKindsWhenEmpty() {
        // given
        given(repository.isEmpty()).willReturn(true);

        // when
        boolean seeded = recorder.seedIfEmpty(Set.of("시발"), Set.of("시발점"));

        // then
        assertThat(seeded).isTrue();
        verify(repository).add(FilterWordKind.PROFANITY, "시발", ChatConstants.SYSTEM_SENDER_ID);
        verify(repository).add(FilterWordKind.WHITELIST, "시발점", ChatConstants.SYSTEM_SENDER_ID);
    }

    /**
     * ⭐ <b>정렬 순서로 넣는다 — 교착 때문이다.</b>
     *
     * <p>{@code ON CONFLICT DO NOTHING} 이 <i>결과</i>를 멱등으로 만들지만 <b>락 순서</b>는
     * 말하지 않는다. 입력이 {@code Set} 이고 그 이터레이션 순서가 JVM 실행마다 달라서(실측:
     * 같은 10항목 3회 실행 → 순서 2종) 두 Pod 이 같은 두 행을 반대 순서로 잡으면 postgres 가
     * deadlock 을 낸다(실측 1/1). 레플리카 둘로 첫 배포하면 한 쪽 시드가 통째로 abort 되고
     * 러너가 삼키므로 그 Pod 의 발행 한 번이 유실된다.
     *
     * <p>모든 Pod 이 같은 순서로 잡으면 그 순환이 생기지 않는다. 그래서 재는 것이 "정렬됐는가"
     * 다 — 특정 순서가 중요한 것이 아니라 <b>모든 Pod 이 같은 순서</b>라는 점이 중요하고,
     * 정렬이 그것을 결정론으로 만든다.
     */
    @Test
    @DisplayName("⭐ 정렬 순서로 넣는다 — 두 Pod 이 반대 순서로 잡으면 교착한다")
    void insertsInSortedOrder() {
        // given: 집합 리터럴의 순서와 다른 정렬 순서를 갖는 입력
        given(repository.isEmpty()).willReturn(true);

        // when
        recorder.seedIfEmpty(new LinkedHashSet<>(List.of("시발", "개새끼", "지랄")),
                new LinkedHashSet<>(List.of("시발점", "개새끼손가락")));

        // then: kind 별로 사전식 정렬이다
        InOrder order = inOrder(repository);
        order.verify(repository).add(FilterWordKind.PROFANITY, "개새끼", ChatConstants.SYSTEM_SENDER_ID);
        order.verify(repository).add(FilterWordKind.PROFANITY, "시발", ChatConstants.SYSTEM_SENDER_ID);
        order.verify(repository).add(FilterWordKind.PROFANITY, "지랄", ChatConstants.SYSTEM_SENDER_ID);
        order.verify(repository).add(FilterWordKind.WHITELIST, "개새끼손가락", ChatConstants.SYSTEM_SENDER_ID);
        order.verify(repository).add(FilterWordKind.WHITELIST, "시발점", ChatConstants.SYSTEM_SENDER_ID);
    }

    /**
     * ⭐ 이 단언이 삭제를 작동하게 만든다. 빠지면 운영자가 뺀 단어가 다음 배포에서 되살아난다.
     */
    @Test
    @DisplayName("⭐ 비어 있지 않으면 아무것도 심지 않는다 — 운영자가 뺀 단어가 되살아나면 안 된다")
    void seedsNothingWhenTheTableHasRows() {
        // given
        given(repository.isEmpty()).willReturn(false);

        // when
        boolean seeded = recorder.seedIfEmpty(Set.of("시발"), Set.of("시발점"));

        // then
        assertThat(seeded).isFalse();
        then(repository).should(never())
                .add(ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }
}
