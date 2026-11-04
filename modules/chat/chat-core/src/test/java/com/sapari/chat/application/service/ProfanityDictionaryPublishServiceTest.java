package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.application.port.ProfanityDictionarySnapshotPublisher;
import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;

/**
 * 정본 → 스냅샷 — <b>두 종류가 함께 나가는지</b>를 잰다.
 *
 * <p>한쪽만 발행하면 받는 쪽이 반쪽 사전으로 교체한다. 화이트리스트 항목은 자기가 덮는 욕설과
 * 함께여야 의미가 있으므로, 욕설만 보내면 그 항목들이 보호하던 정상어가 통째로 마스킹된다
 * ({@code 시발점} → {@code ***점}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("사전 발행 — 두 종류를 함께 떠서 보낸다")
class ProfanityDictionaryPublishServiceTest {

    @Mock
    private ChatFilterWordRepository repository;
    @Mock
    private ProfanityDictionarySnapshotPublisher snapshotPublisher;

    private ProfanityDictionaryPublishService service;

    @BeforeEach
    void setUp() {
        service = new ProfanityDictionaryPublishService(repository, snapshotPublisher);
    }

    @Test
    @DisplayName("⭐ 욕설과 화이트리스트를 함께 발행하고 새 버전을 돌려준다")
    void publishesBothKindsAndReturnsTheVersion() {
        // given
        given(repository.findWords(FilterWordKind.PROFANITY)).willReturn(Set.of("시발"));
        given(repository.findWords(FilterWordKind.WHITELIST)).willReturn(Set.of("시발점"));
        given(snapshotPublisher.publish(Set.of("시발"), Set.of("시발점"))).willReturn(12L);

        // when
        long version = service.publishFromSource();

        // then: 버전을 돌려줘야 호출자가 "내가 밀어 넣은 것이 함대에 깔렸는가" 를 받는 쪽 로그와
        // 맞출 수 있다
        assertThat(version).isEqualTo(12);
        verify(snapshotPublisher).publish(Set.of("시발"), Set.of("시발점"));
    }

    /**
     * 정본이 빈 것을 발행하는 것 자체는 막지 않는다 — 거부는 받는 쪽 문
     * ({@code ProfanityDictionary.of})이 하고, 거기서 떨어지면 교체를 포기한다. 여기서 막으면
     * "정본이 정말 비었다" 를 발행할 길이 없어지고, 그 상태는 운영자가 전부 지운 결과일 수 있다.
     */
    @Test
    @DisplayName("빈 정본도 발행한다 — 거부는 받는 쪽 문이 한다")
    void anEmptySourceIsStillPublished() {
        given(repository.findWords(FilterWordKind.PROFANITY)).willReturn(Set.of());
        given(repository.findWords(FilterWordKind.WHITELIST)).willReturn(Set.of());
        given(snapshotPublisher.publish(Set.of(), Set.of())).willReturn(1L);

        assertThat(service.publishFromSource()).isEqualTo(1);
    }
}
