package com.sapari.chat.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.sapari.chat.application.port.ProfanityDictionaryChangedSource;
import com.sapari.chat.application.port.ProfanityDictionarySnapshot;
import com.sapari.chat.application.port.ProfanityDictionarySnapshotReader;
import com.sapari.chat.domain.rule.ProfanityFilter;
import com.sapari.chat.infrastructure.config.DictionaryProfanityFilterFactory;
import com.sapari.chat.infrastructure.config.SwappableProfanityFilter;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 사전 새로고침 — <b>자가치유가 어느 순서에 걸려 있는지</b>를 잰다.
 *
 * <p>이 핸들러의 fail-safe 는 "실패하면 이전 사전을 계속 쓴다" 인데, 그것이 성립하려면
 * <b>로컬 버전이 교체 성공 뒤에만 올라가야</b> 한다. 먼저 올리면 그 Pod 은 다음 주기 확인에서
 * "일치" 로 보고 <b>영구히 재시도하지 않는다</b> — 주기 확인이 자가치유인 근거가 불일치가 남아
 * 있는 것이기 때문이다. 그래서 여기서 가장 세게 박는 단언이 "읽기 실패 뒤에 다시 시도한다" 다.
 *
 * <p>반대로 <b>내용 때문에</b> 거부된 버전은 다시 시도하지 않아야 한다. 다시 읽어도 같은 이유로
 * 거부되므로, 재시도하면 분마다 같은 ERROR 를 전 Pod 가 찍는다. 두 방향을 가르는 것이 이
 * 핸들러에서 가장 틀리기 쉬운 자리라 양쪽 다 벡터를 둔다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("사전 새로고침 — 실패의 방향이 재시도 여부를 가른다")
class ProfanityDictionaryRefreshHandlerTest {

    /** 반응형 종결자 상한 — 없으면 회귀가 테스트를 실패시키는 대신 멈추게 한다. */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    @Mock
    private ProfanityDictionaryChangedSource changedSource;
    @Mock
    private ProfanityDictionarySnapshotReader snapshotReader;

    private SwappableProfanityFilter cell;
    private ProfanityDictionaryRefreshHandler handler;

    /** 번들 자리를 대신하는 출발 사전 — 교체가 실제로 일어났는지는 이것이 바뀌는지로 본다. */
    private static final ProfanityFilter BUNDLED =
            new ProfanityFilter(Set.of("번들욕설"), Set.of());

    @BeforeEach
    void setUp() {
        cell = new SwappableProfanityFilter(BUNDLED);
        // 문은 실제 구현을 쓴다 — 목으로 바꾸면 "예산을 넘는 사전을 거부한다" 가 목의 설정을
        // 재는 테스트가 되고, 그 거부가 실제로 그 문에서 나오는지는 안 재진다.
        handler = new ProfanityDictionaryRefreshHandler(
                changedSource, snapshotReader, new DictionaryProfanityFilterFactory(), cell);
    }

    private static ProfanityDictionarySnapshot snapshot(long version, String... words) {
        return new ProfanityDictionarySnapshot(version, Set.of(words), Set.of());
    }

    private void refresh(String reason) {
        StepVerifier.create(handler.refresh(reason)).expectComplete().verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("⭐ 스냅샷이 있으면 번들 사전을 정본 것으로 갈아 끼운다")
    void appliesThePublishedSnapshot() {
        // given
        given(snapshotReader.readVersion()).willReturn(Mono.just(1L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(1, "정본욕설")));

        // when
        refresh("boot");

        // then: 셀이 든 필터가 바뀌었다 — 번들 단어는 더 안 걸리고 정본 단어가 걸린다
        assertThat(cell.current().filter("정본욕설")).isEqualTo("***");
        assertThat(cell.current().filter("번들욕설")).isEqualTo("번들욕설");
    }

    @Test
    @DisplayName("버전이 그대로면 스냅샷을 끌어오지 않는다 — 주기 확인이 수십 KB 를 매분 당기지 않는다")
    void anUnchangedVersionDoesNotFetchTheSnapshot() {
        // given: 한 번 적용해 로컬 버전을 3으로 만든다
        given(snapshotReader.readVersion()).willReturn(Mono.just(3L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(3, "정본욕설")));
        refresh("boot");

        // when: 같은 버전으로 주기 확인
        refresh("poll");

        // then: read() 는 처음 한 번뿐이다
        then(snapshotReader).should(times(1)).read();
    }

    /**
     * ⭐ <b>자가치유의 하중점.</b> 읽기가 실패했는데 로컬 버전을 올리면 그 Pod 은 다음 확인에서
     * "일치" 로 보고 영구히 재시도하지 않는다. 그러면 알림을 놓친 Pod 이 재시작할 때까지 옛
     * 사전으로 집행하고, 증상이 "어떤 Pod 은 막고 어떤 Pod 은 안 막는다" 라 원인이 사전 쪽으로
     * 수렴하지 않는다.
     */
    @Test
    @DisplayName("⭐ 읽기가 실패하면 버전을 올리지 않는다 — 다음 확인이 다시 시도한다")
    void aTransportFailureDoesNotAdvanceTheVersion() {
        // given: 첫 시도는 전송 실패
        given(snapshotReader.readVersion()).willReturn(Mono.just(5L));
        given(snapshotReader.read()).willReturn(Mono.error(new RuntimeException("redis blip")));
        refresh("notify:v5");

        assertThat(cell.current().filter("번들욕설"))
                .as("실패했으면 이전 사전을 계속 쓴다")
                .isEqualTo("***");

        // when: 같은 버전으로 다시 — 재시도해야 한다
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(5, "정본욕설")));
        refresh("poll");

        // then
        then(snapshotReader).should(times(2)).read();
        assertThat(cell.current().filter("정본욕설")).isEqualTo("***");
    }

    /**
     * ⭐ 반대 방향 — <b>내용</b> 때문에 거부된 버전은 다시 시도하지 않는다.
     *
     * <p>{@code ProfanityDictionary.of} 는 순수 계산이라 같은 스냅샷을 다시 읽으면 같은 이유로
     * 거부된다. 재시도하면 분마다 같은 ERROR 를 전 Pod 가 찍는다. 그 스냅샷이 고쳐지면 새
     * 발행이고 버전이 오르므로 그때 다시 받는다 — 아래 {@code aNewerVersionIsPickedUp} 가 그것이다.
     */
    @Test
    @DisplayName("⭐ 예산을 넘는 항목이 든 사전은 거부하고, 같은 버전을 다시 시도하지 않는다")
    void aRefusedSnapshotIsNotRetried() {
        // given: 17자 — K 예산(16) 밖이다
        given(snapshotReader.readVersion()).willReturn(Mono.just(7L));
        given(snapshotReader.read())
                .willReturn(Mono.just(snapshot(7, "가".repeat(17))));

        // when
        refresh("notify:v7");

        // then: 교체 거부 — 이전 사전이 그대로다
        assertThat(cell.current().filter("번들욕설")).isEqualTo("***");

        // when: 같은 버전으로 주기 확인
        refresh("poll");

        // then: 두 번째 read 가 없다
        then(snapshotReader).should(times(1)).read();
    }

    @Test
    @DisplayName("거부된 뒤에 새 버전이 오면 받는다 — 고치는 길이 막히지 않는다")
    void aNewerVersionIsPickedUpAfterARefusal() {
        // given: 거부되는 사전
        given(snapshotReader.readVersion()).willReturn(Mono.just(7L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(7, "가".repeat(17))));
        refresh("notify:v7");

        // when: 운영자가 고쳐 새로 발행했다
        given(snapshotReader.readVersion()).willReturn(Mono.just(8L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(8, "정본욕설")));
        refresh("notify:v8");

        // then
        assertThat(cell.current().filter("정본욕설")).isEqualTo("***");
    }

    @Test
    @DisplayName("포맷이 깨진 스냅샷도 거부하고 같은 버전을 다시 시도하지 않는다")
    void aMalformedSnapshotIsNotRetried() {
        // given: 코덱이 던지는 상황을 reader 가 그대로 올린다
        given(snapshotReader.readVersion()).willReturn(Mono.just(9L));
        given(snapshotReader.read())
                .willReturn(Mono.error(new IllegalStateException("사전 스냅샷의 줄 수가 선언과 다르다")));

        refresh("poll");
        refresh("poll");

        then(snapshotReader).should(times(2)).readVersion();
        then(snapshotReader).should(times(1)).read();
        assertThat(cell.current().filter("번들욕설")).isEqualTo("***");
    }

    @Test
    @DisplayName("스냅샷이 아직 없으면 번들 사전으로 남는다 — 실패가 아니다")
    void noSnapshotYetLeavesTheBundledDictionary() {
        given(snapshotReader.readVersion()).willReturn(Mono.empty());

        refresh("boot");

        then(snapshotReader).should(never()).read();
        assertThat(cell.current().filter("번들욕설")).isEqualTo("***");
    }

    /**
     * ⭐ 손에 든 스냅샷의 버전이 로컬보다 낮으면 적용하지 않는다.
     *
     * <p>발행이 덮어쓰기라 이 조합이 나올 경로는 지금 없다. 단언을 두는 이유는 그 사실이
     * 코드에 적혀 있게 하려는 것이다 — 나중에 발행이 둘로 갈리면 여기가 먼저 깨진다.
     */
    @Test
    @DisplayName("⭐ 더 낮은 버전의 스냅샷은 적용하지 않는다")
    void anOlderSnapshotIsNotApplied() {
        given(snapshotReader.readVersion()).willReturn(Mono.just(4L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(4, "정본욕설")));
        refresh("boot");

        // 확인 때는 다른 수가 보이는데 실제로 손에 든 것은 더 낮다
        given(snapshotReader.readVersion()).willReturn(Mono.just(2L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(2, "옛욕설")));
        refresh("poll");

        assertThat(cell.current().filter("옛욕설")).isEqualTo("옛욕설");
        assertThat(cell.current().filter("정본욕설")).isEqualTo("***");
    }

    /**
     * ⭐ 트라이 빌드가 호출 스레드에서 돌지 않는다.
     *
     * <p>1,078 항목에서 p50 0.54 ms · max 1.45 ms 이고 항목 수에 선형이다. 알림은 Redis 구독
     * 스레드에서, 주기는 parallel 스케줄러에서 오는데 둘 다 그 일을 할 자리가 아니다 —
     * 구독 스레드를 붙잡으면 그 Pod 의 다른 Pub/Sub 수신이 함께 밀린다.
     */
    @Test
    @DisplayName("⭐ 트라이 빌드는 boundedElastic 에서 돈다 — 구독 스레드를 붙잡지 않는다")
    void theTrieIsBuiltOffTheCallingThread() {
        // given: 사전 문이 항목을 순회할 때의 스레드를 잡는다. 셀이 final 이라 install 을 가로챌 수
        // 없고, doFinally 는 '완료한 스레드' 라 빌드가 돈 자리와 다를 수 있다 — 순회가
        // ProfanityDictionary.of 안에서 일어나므로 이 자리가 빌드 그 자체다.
        AtomicReference<String> buildThread = new AtomicReference<>();
        Set<String> probed = new AbstractSet<>() {
            private final Set<String> delegate = Set.of("정본욕설");

            @Override
            public Iterator<String> iterator() {
                buildThread.set(Thread.currentThread().getName());
                return delegate.iterator();
            }

            @Override
            public int size() {
                return delegate.size();
            }
        };
        given(snapshotReader.readVersion()).willReturn(Mono.just(1L));
        given(snapshotReader.read())
                .willReturn(Mono.just(new ProfanityDictionarySnapshot(1, probed, Set.of())));

        // when
        refresh("notify:v1");

        // then
        assertThat(buildThread.get())
                .as("빌드가 호출 스레드에서 돌면 그 구독 스레드가 그만큼 붙잡힌다")
                .startsWith("boundedElastic-");
        assertThat(cell.current().filter("정본욕설"))
                .as("스레드를 뗐어도 교체는 실제로 일어나야 한다")
                .isEqualTo("***");
    }

    @Test
    @DisplayName("주기 확인 간격은 분 단위다 — 알림을 놓친 Pod 이 다른 사전으로 집행하는 창")
    void theVersionCheckIntervalIsBounded() {
        assertThat(ProfanityDictionaryRefreshHandler.VERSION_CHECK_INTERVAL)
                .as("이 값이 '알림을 놓친 Pod 이 얼마나 오래 틀린 사전으로 집행하는가' 다")
                .isEqualTo(Duration.ofMinutes(1));
    }
}
