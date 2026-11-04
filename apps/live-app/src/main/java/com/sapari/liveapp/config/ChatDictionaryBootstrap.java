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
     * 의도적으로 뒤집는다.
     *
     * <p><b>근거를 고쳐 적는다.</b> 처음에 "streaming-app 은 이전 스냅샷이나 번들 사전으로 계속
     * 집행하므로 마스킹이 꺼지지 않는다" 를 근거로 적었다. 그 문장은 참이지만 <b>근거로는
     * 약하다</b> — 그것은 streaming-app 에서 "사전 오타 한 줄로 채팅 파드를 죽이지 않는다" 를
     * 고를 때의 근거이고, 이 클래스는 live-app 에 있다. 그 결정이 앱을 건너오면서 전제를 잃었다.
     *
     * <p>이 앱에서 계속 뜨는 것이 안전한 <b>실제 근거는 둘</b>이다:
     * <ul>
     *   <li><b>발행이 시드와 같은 try 안이다.</b> 시드가 던지면 발행도 안 된다 — 빈 테이블이
     *       스냅샷으로 나가 함대를 덮는 경로가 없다. 함대는 이전 스냅샷이나 번들로 돈다.
     *   <li><b>정본이 세워지지 않은 동안 사전 쓰기가 거부된다.</b> {@code ManageFilterWordsService}
     *       의 선검사가 <i>변경 전</i> 집합을 사전 문에 통과시키고, 빈 욕설 집합은 그 문에서
     *       떨어진다 — 그래서 첫 단어를 넣으려는 요청이 409 로 막힌다. 이 게이트가 없으면
     *       운영자의 단어 하나가 함대의 마스킹을 1,078개에서 <b>1개로</b> 내려앉힌다
     *       (실측 벡터: {@code addingTheFirstWordToAnEmptySourceIsRefused}).
     * </ul>
     * 즉 "계속 떠도 된다" 를 떠받치는 것은 마스킹이 안 꺼진다는 사실이 아니라 <b>쓰기가 닫혀
     * 있다</b>는 사실이다. 그 게이트를 지우면 이 결정도 함께 틀려진다.
     *
     * <p>부팅을 세우는 쪽을 안 고른 이유는 그대로다 — 이 앱의 본업은 방송을 여는 것이고
     * (채팅 트래픽은 streaming-app 이 나른다) DB·Redis 가 늦게 준비되는 환경에서 CrashLoop 가
     * 된다. {@code ShedLockTableGuard} 가 부팅을 세우는 것과 다른 판단이고, 다른 이유는 그쪽이
     * 없으면 예약 작업의 <b>정확성</b>이 깨지기 때문이다.
     *
     * <p>조용히 넘기지도 않는다. 이 줄이 안 보이면 그 Pod 가 심은 사전이 함대에 안 깔린 것을
     * 아무도 모른다.
     */
    @Override
    public void run(ApplicationArguments args) {
        ProfanityDictionary bundled = ProfanityDictionary.load(null, null);
        if (!seed(bundled)) {
            return;
        }
        publish();
    }

    /**
     * 시드 실패와 발행 실패를 <b>갈라 적는다.</b> 결과가 다르기 때문이다 — 시드가 실패하면
     * 정본이 비어 있어 <b>사전 쓰기까지 막힌다</b>(위 javadoc), 발행만 실패하면 정본은 멀쩡하고
     * 쓰기도 되며 다음 관리자 쓰기가 발행을 다시 한다. 한 줄로 뭉개면 당직자가 "ADMIN 이 단어를
     * 못 넣는 상태인가" 를 그 줄에서 알 수 없다.
     *
     * @return 계속 진행해도 되는가. {@code false} 면 발행하지 않는다 — 빈 테이블을 스냅샷으로
     *         내보내면 함대의 사전이 그것으로 교체된다
     */
    private boolean seed(ProfanityDictionary bundled) {
        try {
            boolean seeded = seedRecorder.seedIfEmpty(bundled.words(), bundled.whitelist());
            if (seeded) {
                log.info("사전 정본을 번들로 시드했다 — 욕설 {}개 · 화이트리스트 {}개. "
                                + "이제부터 저자는 테이블이다",
                        bundled.words().size(), bundled.whitelist().size());
            }
            return true;
        } catch (RuntimeException e) {
            log.error("사전 정본 시드 실패 — 이 Pod 는 계속 뜨지만 정본이 비어 있을 수 있다. "
                    + "그 동안 ADMIN 사전 쓰기는 409 로 거부되고(빈 정본에 첫 단어를 넣는 것이 "
                    + "함대의 사전을 그 한 단어로 바꾸기 때문이다) streaming-app 은 번들 사전으로 "
                    + "집행한다. 복구는 DB 를 고치고 이 앱을 다시 띄우는 것이다", e);
            return false;
        }
    }

    private void publish() {
        try {
            log.info("사전 스냅샷을 발행했다 — version={}", publishService.publishFromSource());
        } catch (RuntimeException e) {
            log.error("사전 스냅샷 발행 실패 — 정본은 멀쩡하고 ADMIN 쓰기도 된다. 함대는 이전 "
                    + "스냅샷이나 번들 사전으로 집행하므로 마스킹이 꺼지지는 않지만, 정본과 함대가 "
                    + "다를 수 있다(다음 관리자 쓰기나 다음 부팅에서 다시 발행된다)", e);
        }
    }
}
