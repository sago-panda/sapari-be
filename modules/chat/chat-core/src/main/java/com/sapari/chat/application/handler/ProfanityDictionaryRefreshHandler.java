package com.sapari.chat.application.handler;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ProfanityDictionaryChangedSource;
import com.sapari.chat.application.port.ProfanityDictionarySnapshot;
import com.sapari.chat.application.port.ProfanityDictionarySnapshotReader;
import com.sapari.chat.application.port.ProfanityFilterFactory;
import com.sapari.chat.application.port.ProfanityFilterInstaller;
import com.sapari.chat.domain.rule.ProfanityFilter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 정본에서 전파된 사전을 이 Pod 의 필터에 갈아 끼운다 — 사전 변경의 유일한 길이 배포였던 것을 끊는다.
 *
 * <p><b>깨우는 것이 셋, 하는 일은 하나다.</b> 부팅 1회 · 알림 1건 · 주기 확인. 셋을 한 스트림으로
 * 모아 {@code concatMap} 으로 흘리므로 <b>새로 고치는 작업은 한 번에 하나</b>다. 그래서 두
 * 새로고침이 겹쳐 더 낡은 사전이 나중에 깔리는 경로가 없고, 단조 판정이 필드 비교 하나로 끝난다
 * (락도 CAS 도 필요 없다 — 쓰는 스레드가 하나다).
 *
 * <p><b>주기 확인이 있어야 하는 이유.</b> Redis Pub/Sub 은 비영속이다. 구독이 끊긴 사이에 나간
 * 알림은 재연결해도 오지 않고, 그 알림을 놓친 Pod 은 재시작할 때까지 옛 사전으로 집행한다 —
 * 증상이 "어떤 Pod 은 막고 어떤 Pod 은 안 막는다" 라 원인이 사전 쪽으로 수렴하지 않는다.
 * 알림은 주기를 <b>기다리지 않게</b> 해 주는 빠른 길이고, 전파를 성립시키는 것은 주기 쪽이다.
 *
 * <p><b>세 길이 같은 코드를 지난다.</b> 알림이 실어 온 버전을 교체 판단에 쓰지 않는다 — 알림과
 * 스냅샷 읽기 사이에 또 발행이 나면 그 수는 이미 낡았고, 그때 적용되는 것은 읽어 온 스냅샷이다.
 * 그래서 판단은 <b>스냅샷에 박힌 버전</b>으로만 하고, 알림의 수는 로그에만 쓴다.
 *
 * <p><b>교체 실패는 교체 포기다.</b> 전파된 사전도 번들·설정과 같은 문을 지나고
 * ({@link ProfanityDictionary#of}) 거기서 떨어지면 <b>이전 사전을 계속 쓴다.</b> 리소스 입구가
 * 부팅을 세우는 것과 방향이 다른데, 도는 Pod 을 사전 한 줄의 오타로 죽이는 것이 그 오타보다
 * 나쁘기 때문이다 — ADMIN 요청 한 번이 전 파드 CrashLoop 가 되면 안 된다. 쓰기 경로가 같은
 * 검사를 먼저 돌려 애초에 이 상태에 도달하지 않게 하고, 여기는 그 경로를 지나지 않은 쓰기
 * (DB·Redis 직접 수정)를 받는 마지막 문이다.
 *
 * <p><b>트라이 빌드를 {@code boundedElastic} 으로 뗀다.</b> 1,078 항목에서 p50 0.54 ms ·
 * max 1.45 ms 이고 항목 수에 선형이다. 알림은 Redis 구독 스레드에서, 주기는 parallel 스케줄러에서
 * 오는데 둘 다 그 일을 할 자리가 아니다.
 *
 * <p>이 Pod 은 이 핸들러가 없어도 <b>뜬다</b> — 부팅 때 번들 사전으로 조립된 필터가 이미 셀에
 * 들어 있고({@code ChatBeansConfig}) 이 핸들러는 그것을 정본 쪽 것으로 바꾸는 일만 한다.
 * 그래서 전파가 통째로 고장 나도 마스킹이 꺼지지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfanityDictionaryRefreshHandler {

    /**
     * 주기 버전 확인 간격.
     *
     * <p>사전 변경 빈도는 사람이 타이핑하는 속도라 분 단위로 충분하다. 비용은 Pod 당 이 간격마다
     * {@code GET} 하나(몇 바이트)다 — 스냅샷 전체(수십 KB)가 아닌 것이 버전 키를 따로 둔 이유다.
     *
     * <p>이 값이 정하는 것은 <b>알림을 놓친 Pod 이 얼마나 오래 다른 사전으로 집행하는가</b>다.
     * 줄이면 그 창이 좁아지고 {@code GET} 이 늘지만, 늘리면 그 창이 그대로 넓어진다.
     */
    static final Duration VERSION_CHECK_INTERVAL = Duration.ofMinutes(1);

    /** 실패 메시지에 실을 출처 — 사전이 들어오는 세 입구 중 어디서 떨어졌는지가 당직자에게 가야 한다. */
    private static final String ORIGIN = "chat:dict";

    private final ProfanityDictionaryChangedSource changedSource;
    private final ProfanityDictionarySnapshotReader snapshotReader;
    private final ProfanityFilterFactory filterFactory;
    private final ProfanityFilterInstaller filterInstaller;

    /**
     * 이미 처리한 버전 — 적용했든 거부했든 포함한다.
     *
     * <p>거부한 버전도 여기 넣는 것이 중요하다. 내용 때문에 거부된 스냅샷은 <b>다시 읽어도 같은
     * 이유로 거부</b>되므로, 넣지 않으면 분마다 같은 ERROR 를 전 Pod 가 찍는다. 그 스냅샷이
     * 고쳐지면 새 발행이고 버전이 오르므로 그때 다시 받는다.
     *
     * <p>0 은 "아직 정본 사전을 적용하지 않았다"(번들로 떠 있다)는 뜻이다 — 발행 버전은
     * {@code INCR} 결과라 1부터다.
     *
     * <p>쓰는 스레드가 하나다({@code concatMap}). {@code volatile} 은 로그·테스트가 다른
     * 스레드에서 읽기 때문이다.
     */
    private volatile long processedVersion;

    private Disposable subscription;

    @PostConstruct
    void start() {
        subscription = triggers()
                // defer 로 감싼다 — refresh() 안의 단계가 조립 시점에 동기 throw 하면 Mono 를
                // 돌려주기 전에 터져 아래 onErrorResume 이 못 잡는다. 그러면 이 Pod 의 구독이 죽어
                // 이후 모든 사전 변경을 영구히 놓친다(재시작 전까지 번들/옛 사전으로 집행한다).
                .concatMap(reason -> Mono.defer(() -> refresh(reason))
                        .onErrorResume(e -> {
                            // 여기 오는 것은 위 refresh 가 못 분류한 실패다. 버전을 올리지 않으므로
                            // 다음 주기가 다시 시도한다.
                            log.warn("사전 새로고침 실패 — 이전 사전을 계속 쓴다 trigger={} cause={}",
                                    reason, e.getClass().getSimpleName());
                            return Mono.empty();
                        }))
                .subscribe();
    }

    @PreDestroy
    void stop() {
        if (subscription != null) {
            subscription.dispose();
        }
    }

    /**
     * 셋을 한 스트림으로 모은다. {@code concatMap} 이 아래에서 직렬화하므로 여기서는 순서를
     * 보장하지 않아도 된다 — 어느 것이 먼저 와도 하는 일이 같다("지금 정본이 뭔지 보고 와라").
     */
    private Flux<String> triggers() {
        return Flux.merge(
                Mono.just("boot"),
                changedSource.changed().map(version -> "notify:v" + version),
                Flux.interval(VERSION_CHECK_INTERVAL, VERSION_CHECK_INTERVAL).map(tick -> "poll"));
    }

    /**
     * 버전을 먼저 보고, 바뀌었을 때만 스냅샷을 끌어온다. (테스트 진입점)
     *
     * <p>버전 확인과 스냅샷 읽기가 두 왕복이라 그 사이에 또 발행이 날 수 있다. 그래도 어긋나지
     * 않는 것은 <b>적용 기준이 읽어 온 스냅샷에 박힌 버전</b>이기 때문이다 — 확인 때 본 수는
     * "뭔가 바뀌었다" 는 신호로만 쓰고 버리고, 기록하는 것은 실제로 손에 든 쪽의 수다.
     */
    Mono<Void> refresh(String reason) {
        return snapshotReader.readVersion()
                // 아직 한 번도 발행되지 않았다. 이 Pod 은 번들 사전으로 집행하는데 그것이
                // 정본과 다를 수 있으므로 조용히 넘기지 않는다 — 부팅 때만 적는다(주기마다
                // 적으면 발행 전에는 분마다 같은 줄이 쌓인다).
                .switchIfEmpty(Mono.fromRunnable(() -> {
                    if ("boot".equals(reason)) {
                        log.info("정본 사전 스냅샷이 아직 없다 — 번들 사전으로 집행한다"
                                + "(발행되면 알림이나 주기 확인에서 받는다)");
                    }
                }))
                .filter(published -> published != processedVersion)
                .flatMap(published -> snapshotReader.read()
                        .flatMap(snapshot -> apply(snapshot, reason))
                        // 코덱이 포맷을 거부한 것이다. 다시 읽어도 같은 이유로 거부되므로
                        // <확인 때 본 버전>을 처리한 것으로 적는다 — 손에 든 스냅샷이 없어
                        // 거기 박힌 수를 쓸 수 없는 자리다. 안 적으면 분마다 같은 ERROR 를
                        // 전 Pod 가 찍는다.
                        .onErrorResume(IllegalStateException.class, e -> {
                            processedVersion = published;
                            log.error("전파된 사전의 포맷이 깨졌다 — 이전 사전을 계속 쓴다 "
                                    + "version={} trigger={} 이유={}", published, reason, e.getMessage());
                            return Mono.empty();
                        }))
                .onErrorResume(e -> {
                    // 전송 실패다 — 버전을 올리지 <않는다>. 올리면 이 Pod 은 다음 주기 확인에서
                    // "일치" 로 보고 영구히 재시도하지 않고, 주기 확인이 자가치유인 근거가
                    // 불일치가 남아 있는 것이므로 그 순서가 자가치유의 하중점이다.
                    log.warn("사전 스냅샷을 읽지 못했다 — 이전 사전을 계속 쓴다(다음 주기에 재시도) "
                            + "trigger={} localVersion={} cause={}", reason, processedVersion,
                            e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    /**
     * 스냅샷을 문에 통과시키고 필터를 만들어 갈아 끼운다.
     *
     * <p>단조 비교를 여기서 한 번 더 한다. 위 {@code filter} 는 "확인 때 본 수" 로 걸렀고 이것은
     * "손에 든 스냅샷의 수" 다 — 그 사이에 더 낡은 스냅샷이 올 경로는 없지만(발행이 덮어쓰기라)
     * 비교가 없으면 그 사실이 코드에 안 적힌다.
     */
    private Mono<Void> apply(ProfanityDictionarySnapshot snapshot, String reason) {
        if (snapshot.version() <= processedVersion) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> build(snapshot))
                // 트라이 빌드는 CPU 다. 알림은 Redis 구독 스레드에서, 주기는 parallel 에서 오는데
                // 둘 다 이 일을 할 자리가 아니다.
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(filter -> {
                    // ⚠️ 버전 갱신이 <이 콜백 안>에 있는 것이 하중을 받는다. 위 체인 어디로든
                    // 올리면 그 지점 뒤의 실패가 버전만 올려 두고, 그러면 이 Pod 은 다음 주기
                    // 확인에서 "일치" 로 보고 영구히 재시도하지 않는다 — 주기 확인이 자가치유인
                    // 근거가 불일치가 남아 있는 것이다. 되돌림으로 확인했다(전송 실패 분기에
                    // 버전 갱신을 넣으면 재시도 테스트가 깨진다).
                    //
                    // 반면 이 두 줄의 <서로간> 순서는 관측되지 않는다 — 여기 오면 빌드가 이미
                    // 끝났고 install 은 null 에만 던진다. 한때 "교체가 먼저여야 한다" 고 적었는데
                    // 되돌려 재면 아무것도 깨지지 않으므로 그 문장은 거짓이었다.
                    filterInstaller.install(filter);
                    processedVersion = snapshot.version();
                    log.info("사전을 정본 것으로 갈아 끼웠다 — 욕설 {}개 · 화이트리스트 {}개 "
                                    + "version={} trigger={}",
                            snapshot.words().size(), snapshot.whitelist().size(),
                            snapshot.version(), reason);
                })
                .onErrorResume(IllegalStateException.class, e -> {
                    // 내용 때문에 떨어진 것이다 — 다시 읽어도 같은 이유로 떨어지므로 이 버전을
                    // 처리한 것으로 적는다. 안 적으면 분마다 같은 ERROR 를 전 Pod 가 찍는다.
                    //
                    // ERROR 인 이유: 아무도 요청하지 않은 거부다. 쓰기 경로 선검사를 지나왔다면
                    // 애초에 발행되지 않았을 값이므로, 여기로 온 것은 그 경로를 지나지 않은 쓰기
                    // (DB·Redis 직접 수정)다 — 운영자가 알 길이 이 줄뿐이다.
                    processedVersion = snapshot.version();
                    log.error("전파된 사전을 거부했다 — 이전 사전을 계속 쓴다 version={} trigger={} 이유={}",
                            snapshot.version(), reason, e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    /**
     * 번들·설정과 <b>같은 문</b>을 지난다. 여기서 K 예산(사전 최장 항목 길이)도 함께 걸린다 —
     * 그 예산을 교체 지점에 둔 것이 설계다: 게이트의 평면과 런타임의 평면이 같아야, 쓰기 경로를
     * 지나지 않은 값도 집행에 들어오기 전에 걸린다.
     */
    private ProfanityFilter build(ProfanityDictionarySnapshot snapshot) {
        return filterFactory.from(ORIGIN, snapshot.words(), snapshot.whitelist());
    }
}
