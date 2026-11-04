package com.sapari.liveapp.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.sapari.chat.application.service.ChatFilterWordSeedRecorder;
import com.sapari.chat.application.service.ProfanityDictionaryPublishService;
import com.sapari.chat.infrastructure.config.ProfanityDictionary;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 부팅 때 사전 정본을 세우고 한 장 발행한다 — 이 앱이 사전 전파의 <b>쓰는 쪽</b>이다.
 *
 * <p><b>왜 이 앱인가.</b> 정본이 Postgres 인데 streaming-app 에는 DataSource 가 없다
 * ({@code DataSourceAutoConfiguration} 을 통째로 빼고 {@code AutoConfigurationExclusionTest} 가
 * 그것을 못 박는다). 정본을 읽을 수 있는 앱이 읽어서, 읽을 수 없는 앱이 닿는 자리에 놓아야 한다.
 *
 * <p><b>두 일의 순서가 계약이다.</b> 시드가 트랜잭션 안이고 발행이 그 밖이다 — 트랜잭션 안에서
 * 발행하면 롤백된 단어가 함대에 깔리고, 그 상태는 정본에 흔적이 없어 어디서 왔는지 찾을 수 없다.
 *
 * <p><b>발행은 매 부팅 한다.</b> 멱등이고(정본을 읽어 덮어쓴다) 받는 쪽 재빌드가 0.54 ms 라 비용이
 * 없는데, 그 대신 "스냅샷이 없다/낡았다" 가 배포 한 번으로 스스로 정리된다.
 *
 * <p><b>설정 CSV 를 시드에 넣지 않는다.</b> {@code chat.profanity.words} 는 방송 중에 쓰라고 둔
 * 배포 단위 긴급 통로이고, 그것을 테이블에 심으면 임시 조치가 영구 정본이 된다 — 그 환경을
 * 걷어내도 단어가 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatDictionaryBootstrap implements ApplicationRunner {

    private final ChatFilterWordSeedRecorder seedRecorder;
    private final ProfanityDictionaryPublishService publishService;

    /**
     * ⚠️ <b>실패해도 부팅을 세우지 않는다</b> — 이 저장소의 "예외를 삼키지 말 것" 을 여기서
     * 의도적으로 뒤집는다. 근거는 잃는 것의 크기다: 이 앱의 본업은 방송을 여는 것이고, 사전
     * 발행이 안 됐을 때 streaming-app 은 <b>이전 스냅샷이나 번들 사전으로 계속 집행한다</b> —
     * 마스킹이 꺼지지 않는다. 그 대가로 부팅을 세우면 Redis 한 번 흔들린 것이 방송 전체를 막는다.
     *
     * <p>조용히 넘기지도 않는다. 이 줄이 안 보이면 그 Pod 가 심은 사전이 함대에 안 깔린 것을
     * 아무도 모른다 — 발행은 관리자 쓰기에서 다시 일어나므로 복구는 되지만, 그때까지 정본과
     * 함대가 다르다. 그래서 ERROR 다({@code ShedLockTableGuard} 가 부팅을 세우는 것과 다른
     * 판단이고, 다른 이유는 그쪽이 없으면 예약 작업의 <b>정확성</b>이 깨지기 때문이다).
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            bootstrap();
        } catch (RuntimeException e) {
            log.error("사전 정본 준비 실패 — 이 Pod 는 계속 뜬다. streaming-app 은 이전 스냅샷이나 "
                    + "번들 사전으로 집행하므로 마스킹이 꺼지지는 않지만, 정본과 함대가 다를 수 있다 "
                    + "(관리자 쓰기나 다음 부팅에서 다시 발행된다)", e);
        }
    }

    private void bootstrap() {
        ProfanityDictionary bundled = ProfanityDictionary.load(null, null);
        boolean seeded = seedRecorder.seedIfEmpty(bundled.words(), bundled.whitelist());
        if (seeded) {
            log.info("사전 정본을 번들로 시드했다 — 욕설 {}개 · 화이트리스트 {}개. 이제부터 저자는 테이블이다",
                    bundled.words().size(), bundled.whitelist().size());
        }
        long version = publishService.publishFromSource();
        log.info("사전 스냅샷을 발행했다 — version={} (시드 {})", version, seeded ? "수행" : "생략");
    }
}
