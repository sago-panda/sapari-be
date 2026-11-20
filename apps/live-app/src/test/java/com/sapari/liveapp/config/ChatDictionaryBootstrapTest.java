package com.sapari.liveapp.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.sapari.chat.application.service.ChatFilterWordSeedRecorder;
import com.sapari.chat.application.service.ProfanityDictionaryPublishService;

/**
 * 부팅 러너 — <b>시드가 실패하면 발행도 안 된다</b>는 것을 잰다.
 *
 * <p>그 순서가 이 클래스에서 유일하게 하중을 받는 것이다. 발행이 시드 실패와 무관하게 돌면
 * <b>빈 테이블이 스냅샷으로 나가 함대의 사전을 덮는다</b> — 마스킹이 1,078개에서 0개로 가고,
 * 받는 쪽 문이 빈 사전을 거부하므로 각 Pod 은 옛 사전으로 굳는다. 그 상태의 유일한 신호가
 * 부팅 로그 한 줄이다.
 *
 * <p>그리고 이 러너가 <b>부팅을 세우지 않는 것</b>이 안전한 근거의 절반이 여기 있다. 나머지
 * 절반은 사전 쓰기 경로가 빈 정본에서 거부된다는 것이고, 그건
 * {@code ManageFilterWordsServiceTest.addingTheFirstWordToAnEmptySourceIsRefused} 가 잰다.
 * 둘 중 하나가 깨지면 이 결정이 함께 틀려진다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("사전 부팅 러너 — 시드가 실패하면 발행하지 않는다")
class ChatDictionaryBootstrapTest {

    @Mock
    private ChatFilterWordSeedRecorder seedRecorder;
    @Mock
    private ProfanityDictionaryPublishService publishService;

    private ChatDictionaryBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new ChatDictionaryBootstrap(seedRecorder, publishService);
        given(publishService.publishFromSource()).willReturn(1L);
    }

    @Test
    @DisplayName("정상 — 번들로 시드하고 발행한다")
    void seedsThenPublishes() {
        // given: 테이블이 비어 있었다
        given(seedRecorder.seedIfEmpty(anySet(), anySet())).willReturn(true);

        // when
        bootstrap.run(null);

        // then: 번들 사전이 실려 나간다 — 1,078 / 38 은 번들 데이터의 수다
        verify(seedRecorder).seedIfEmpty(anySet(), anySet());
        verify(publishService).publishFromSource();
    }

    @Test
    @DisplayName("이미 시드돼 있어도 발행은 한다 — 스냅샷 유실이 배포 한 번으로 정리된다")
    void publishesEvenWhenAlreadySeeded() {
        given(seedRecorder.seedIfEmpty(anySet(), anySet())).willReturn(false);

        bootstrap.run(null);

        verify(publishService).publishFromSource();
    }

    /**
     * ⭐ 이 단언이 "빈 테이블이 함대를 덮는" 경로를 막는다.
     *
     * <p>시드와 발행을 각자 try 로 감싸면서 가장 틀리기 쉬운 자리다 — 둘을 독립으로 다루면
     * "시드는 실패했지만 발행은 해 보자" 가 자연스러워 보이는데, 그때 발행되는 것이 <b>빈
     * 정본</b>이다.
     */
    @Test
    @DisplayName("⭐ 시드가 실패하면 발행하지 않는다 — 빈 정본이 함대를 덮으면 안 된다")
    void aFailedSeedBlocksThePublish() {
        // given
        willThrow(new RuntimeException("db down"))
                .given(seedRecorder).seedIfEmpty(anySet(), anySet());

        // when: 부팅은 계속된다 — 이 앱의 본업은 방송을 여는 것이다
        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();

        // then
        then(publishService).should(never()).publishFromSource();
    }

    /**
     * 발행만 실패하는 것은 다른 등급이다 — 정본은 멀쩡하고 ADMIN 쓰기도 되며, 다음 관리자
     * 쓰기가 발행을 다시 한다. 그래서 던지지 않고 로그로 끝낸다.
     */
    @Test
    @DisplayName("발행만 실패해도 부팅은 계속된다 — 정본은 멀쩡하고 다음 쓰기가 다시 발행한다")
    void aFailedPublishDoesNotStopTheBoot() {
        given(seedRecorder.seedIfEmpty(anySet(), anySet())).willReturn(true);
        given(publishService.publishFromSource()).willThrow(new RuntimeException("redis down"));

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();

        verify(seedRecorder).seedIfEmpty(anySet(), anySet());
        verify(publishService).publishFromSource();
    }
}
