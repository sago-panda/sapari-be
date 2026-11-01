package com.sapari.chat.infrastructure.config;

import java.time.Duration;
import java.util.List;

import org.bson.UuidRepresentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.index.ReactiveIndexOperations;

import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

/**
 * chat_messages용 Mongo 설정 — UUID 인코딩 + 인덱스 부트스트랩.
 *
 * <p><b>이 클래스는 streaming-app에서만 뜬다.</b> 다른 소비처인 live-app은 {@code com.sapari.chat}을
 * 컴포넌트 스캔에서 패키지째 빼기 때문이다. 그쪽은 여기 있는 것을 <b>스스로 다시 세워야 한다</b> —
 * 실제로 UUID 설정이 함께 빠져 증거 문서의 UUID가 {@code Binary}로 돌아온 적이 있다.
 * 여기에 무언가를 더한다면 그쪽에도 같은 것이 필요한지 확인할 것.
 *
 * <p>{@code ObjectProvider} 가드는 그래서 남아 있다 — Redis 전용 테스트 컨텍스트처럼 리액티브 Mongo가
 * 없는 자리에서도 이 설정이 뜰 수 있고, 그때 인덱스 생성을 건너뛰어 부팅 실패를 막는다.
 */
@Configuration
public class ChatMongoConfig {

    /**
     * roomId/senderId가 UUID 필드라 standard BSON binary 인코딩이 필요하다(미설정 시 드라이버가 인코딩 거부).
     * per-dev application.yml에 의존하지 않도록 코드로 고정한다 — yml 누락 시 "테스트 green/런타임 깨짐" 방지.
     * <b>이 보호는 이 설정이 뜨는 앱에만 미친다.</b> live-app은 같은 커스터마이저를 자기 설정에 따로 둔다.
     */
    @Bean
    public MongoClientSettingsBuilderCustomizer chatUuidRepresentationCustomizer() {
        return builder -> builder.uuidRepresentation(UuidRepresentation.STANDARD);
    }

    /**
     * 인덱스를 <b>싱글톤 사전 초기화에서</b> 만든다 — 이 자리가 포트 bind 보다 앞이기 때문이다.
     *
     * <p>한때 {@code ApplicationRunner}였다. 러너는 컨텍스트 refresh 가 끝난 <b>뒤</b> 돌고, 리액티브
     * 웹서버는 그 refresh 안({@code finishRefresh})에서 포트를 연다. 그래서 러너가 Mongo 를 기다리는
     * 동안 이 프로세스는 이미 TCP 를 받고 있었고, 그 창에 인덱스 없는 컬렉션으로 WS 핸드셰이크가
     * 라우팅됐다.
     *
     * <p><b>그 창의 길이를 드라이버가 묶지 않는다.</b> 이 줄은 세 번 틀렸으니 재서 적는다 — 기본값
     * <b>실측</b>({@code MongoClientSettings.builder().build()}):
     * <pre>
     * serverSelectionTimeoutMs  30000
     * socketReadTimeoutMs       0       ← 무한
     * socketConnectTimeoutMs    10000
     * 전역 timeoutMs             null
     * </pre>
     * 그리고 이 저장소에는 Mongo 클라이언트 타임아웃 설정이 <b>0건</b>이라 기본값이 그대로 적용된다.
     * 그래서 30초가 묶는 것은 "<b>서버를 영영 못 고르는</b>" 한 가지 모드뿐이고, 서버가 선택된 뒤
     * {@code createIndex} 가 답하지 않으면(큰 컬렉션의 신규 인덱스, 끊겼는데 살아 있는 소켓)
     * 드라이버 쪽에는 그걸 끊을 것이 없다.
     *
     * <p>그래서 <b>이 호출부에서 상한을 건다</b> — {@link #INDEX_CREATE_CEILING}. 그 상한이 없으면
     * 이 가드의 의도("시끄럽게 죽는다")가 <b>조용한 멈춤</b>으로 뒤집힌다.
     *
     * <p>상한은 네 인덱스 <b>전체</b>에 하나로 걸린다({@link #INDEX_CREATE_CEILING}) — 데드라인을
     * 한 번 잡고 각 호출에 남은 시간을 넘긴다. 호출마다 따로 걸면 던지지 않는 느린 건들이 누적돼
     * 최악이 건수에 비례한다(실측: 상한 400ms × 4건, 각 300ms → 총 1,279ms, 던짐 0). "4종이면
     * 120초" 도 "하나 더 만들면 30초 늘어난다" 도 그래서 성립하지 않는다 — 이 수는 인덱스 수와
     * 무관하다.
     *
     * <p><b>그 창에는 아무것도 listen 하지 않는다</b>(한때 "이 앱에는 actuator/health 가 없어 TCP
     * 점검뿐이라 그 창 내내 Ready 로 답한다" 고 적었는데 지금은 양쪽 다 거짓이다 — 이 앱은
     * 액추에이터를 {@code implementation} 으로 갖고 {@code ObservabilityExposureConfig} 가
     * {@code health} 의 실제 노출을 부팅 조건으로 요구한다). 이 빈은 포트 bind 보다 앞이므로
     * {@code httpGet} 프로브든 {@code tcpSocket} 점검이든 <b>둘 다 connection refused</b> 이고 파드가
     * Ready 가 되지 않는다 — 저장소 자체 테스트가 그걸 생 TCP connect 로 단언한다
     * ({@code EagerSingletonsRunBeforeThePortBindsTest}). 즉 이 문단이 두려워한 모양은 러너 시절의
     * 것이고, 지금은 방향이 반대다.
     *
     * <p>이 빈이 던지면 refresh 가 실패해 포트가 아예 열리지 않고 프로세스가 내려간다. 새로 생긴
     * 실패가 아니다 — 러너가 던져도 {@code SpringApplication.run()}은 같이 깨졌다(실측). 바뀐 것은
     * <b>깨지는 시점</b>이고, 그게 "곧 죽을 Pod 에 트래픽이 가지 않는다"를 만든다.
     *
     * <p>{@code ObjectProvider}는 그대로 둔다 — 리액티브 Mongo 가 없는 컨텍스트(Redis 전용 테스트,
     * live-app)에서는 이 설정이 떠도 할 일이 없다. 그 자리에서는 빈 목록이 그 사실을 남긴다.
     */
    @Bean
    public ChatMessagesIndexes chatMessagesIndexes(ObjectProvider<ReactiveMongoTemplate> templateProvider) {
        ReactiveMongoTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            return new ChatMessagesIndexes(List.of());
        }
        return new ChatMessagesIndexes(createChatMessagesIndexes(template));
    }

    /** 선 인덱스 이름. 이 빈이 존재한다는 것 자체가 "인덱스가 포트보다 먼저 섰다"는 사실이다. */
    public record ChatMessagesIndexes(List<String> names) {
    }

    /**
     * 부팅 시 인덱스 생성 <b>전체</b>에 거는 상한 — 건당이 아니다.
     *
     * <p><b>왜 필요한가.</b> 드라이버 기본값이 {@code socketReadTimeoutMs=0}(무한)이고 전역
     * {@code timeoutMs} 도 {@code null} 이라(실측), 서버가 선택된 뒤 {@code createIndex} 가 응답하지
     * 않으면 {@code .block()} 이 <b>영원히</b> 기다린다. 그러면 이 가드의 의도("인덱스 없이 뜨지
     * 않는다 — 시끄럽게 죽는다")가 <b>조용한 멈춤</b>으로 뒤집힌다: 파드는 Ready 가 되지 않고,
     * 로그에는 아무 사유도 남지 않으며, "느린 시작" 과 구분되지 않는다.
     *
     * <p><b>왜 전역 {@code timeoutMs} 를 쓰지 않는가.</b> 그건 런타임 질의까지 전부 묶는다 — VOD
     * 윈도우 조회처럼 느려도 되는 경로가 같은 상한에 걸리고, 그 값을 잘못 고르면 부팅이 아니라
     * 서비스가 깨진다. 여기서 막으려는 것은 <b>부팅 경로</b> 하나라 상한도 그 자리에만 둔다.
     *
     * <p><b>값을 10분으로 둔 근거</b>(Testcontainers mongo:7.0 실측):
     * <pre>
     * 빈 컬렉션에 4종 신규 생성          126 ms
     * 이미 있는 상태로 재실행(재부팅)      3 ms   ← 평소 부팅은 사실상 0 이다
     * 20만건에 4종 신규 생성           1,140 ms   → 건당 0.0057 ms
     * </pre>
     * 그 비율로 1,000만건이면 4종 신규 생성이 약 <b>57초</b>, 1억건에 <b>새 인덱스 하나</b>만 추가해도
     * 약 2.3분이다. 처음 60초로 뒀는데 그 값이면 "큰 컬렉션에 인덱스를 처음 추가하는 배포" 가
     * <b>CrashLoopBackOff</b> 가 된다 — 그건 막으려던 멈춤보다 나쁘다(재시작마다 인덱스 빌드를 다시
     * 쏜다). 반면 평소 부팅은 3ms 라 상한을 넉넉히 둬도 잃는 것이 없다.
     *
     * <p>시간만으로는 "멈춘 소켓" 과 "느리지만 진행 중인 빌드" 를 가를 수 없다 — 전자는 영구적이고
     * 후자는 유한하다는 차이뿐이다. 그래서 상한은 <b>유한한 쪽을 넉넉히 덮고 무한한 쪽만 잡는</b>
     * 크기로 둔다. 자기 데이터 규모에 맞게 다시 고를 때는 위 "건당 0.0057 ms" 를 쓰면 된다.
     *
     * <p>⚠️ 그 건당 값이 검증된 구간이 있다. 5만·20만·80만 건에서 건당 비용이 평평했고
     * (0.00336 / 0.00326 / 0.00349 ms) 0.0057 은 그보다 보수적이다. 다만
     * {@code maxIndexBuildMemoryUsageMegabytes} 기본값이 <b>200MB</b> 라, 수백만 건 위에서는 외부
     * 정렬이 디스크로 흘러 <b>측정 구간을 벗어난다</b>. 그 위에서 상한을 다시 고를 때는 외삽하지 말고
     * 자기 데이터로 한 번 재는 쪽이 맞다.
     *
     * <p><b>이 값은 메서드 <u>전체</u>의 상한이다 — 건당이 아니다.</b> 처음에는 네 호출에 각각
     * 걸었는데, 그때 "첫 던짐에서 메서드가 끝나므로 최악은 이 값 하나" 라고 적은 것이 <b>틀렸다</b>:
     * 그 논증은 <b>던지는 모드만</b> 덮는다. 네 건이 각각 상한 <b>바로 아래</b>로 끝나면 아무것도
     * 던지지 않고 전부 누적된다. 실측(상한 400ms × 4건, 각 건 300ms): 총 경과 <b>1,279ms</b>, 던진
     * 횟수 <b>0</b> — 상한 하나로 묶이지 않고 건수에 비례했다.
     *
     * <p>그래서 호출마다 거는 대신 <b>데드라인을 한 번 잡고 남은 시간을 넘긴다</b>. 그러면 "최악
     * 대기 = 이 값" 이 구조로 참이 되고, 인덱스를 하나 더 만들어도 이 수가 변하지 않는다. 그게
     * startupProbe 사이징의 근거로 쓰이는 수라서 건당/전체 구분이 중요하다 — 건당으로 두면 10분
     * 상한이 최악 40분 부팅이 된다.
     *
     * <p>이 줄의 수가 네 번 틀렸다(90s → 120s → ~30s → 건당/전체 혼동). 앞의 셋은 <b>무엇이 그 수를
     * 묶는지</b>를 안 재서였고, 네 번째는 <b>묶이는 단위</b>를 안 재서였다 — 건당인지 전체인지.
     */
    private static final Duration INDEX_CREATE_CEILING = Duration.ofMinutes(10);

    /**
     * 인덱스 4종 생성(멱등). clientMsgId unique partial($type:"string")은 애너테이션으로 표현 불가해
     * 인덱스 정의 전체를 여기로 일원화한다. $type을 쓰는 이유: $exists:true는 null 저장 필드도 매칭해
     * clientMsgId=null 2건째에 DuplicateKey가 터진다. 생성된 인덱스 이름 목록 반환(테스트 검증용).
     */
    public static List<String> createChatMessagesIndexes(ReactiveMongoTemplate mongoTemplate) {
        return createChatMessagesIndexes(mongoTemplate, INDEX_CREATE_CEILING);
    }

    /**
     * 상한을 받는 오버로드 — 테스트가 그 상한이 <b>실제로 걸리는지</b> 재려면 10분을 기다릴 수 없다.
     *
     * <p>상한만 바꿔 끼울 수 있게 두는 것이 요점이다. 운영 경로는 위 메서드가 상수를 넘긴다.
     */
    static List<String> createChatMessagesIndexes(ReactiveMongoTemplate mongoTemplate, Duration ceiling) {
        ReactiveIndexOperations ops = mongoTemplate.indexOps(ChatMessageDocument.class);
        // 데드라인을 한 번 잡는다 — 호출마다 상한을 걸면 던지지 않는 느린 건들이 누적돼 최악이
        // 건수에 비례한다(실측: 상한 400ms × 4건, 각 300ms → 총 1,279ms, 던짐 0).
        long deadline = System.nanoTime() + ceiling.toNanos();

        String paging = awaitIndex(ops.createIndex(new Index()
                .on("roomId", Sort.Direction.ASC)
                .on("_id", Sort.Direction.ASC)), deadline, ceiling, "roomId+_id(페이징)");

        String dedup = awaitIndex(ops.createIndex(new Index()
                .on("roomId", Sort.Direction.ASC)
                .on("senderId", Sort.Direction.ASC)
                .on("clientMsgId", Sort.Direction.ASC)
                .unique()
                .partial(PartialIndexFilter.of(
                        new org.bson.Document("clientMsgId", new org.bson.Document("$type", "string"))))),
                deadline, ceiling, "clientMsgId unique partial(중복 제거)");

        String ttl = awaitIndex(ops.createIndex(new Index()
                .on("createdAt", Sort.Direction.ASC)
                .expire(Duration.ofDays(730))), deadline, ceiling, "createdAt TTL(2년 자동 삭제)");

        // VOD 윈도우(roomId 등치 + createdAt 범위)의 범위 필드를 인덱스 경계로 묶는다.
        //
        // 없으면 플래너가 위 TTL 의 createdAt 단일 인덱스를 고르는데, 그건 그 시간대의 <b>모든 방</b>을
        // 훑는다 — 비용이 방 길이가 아니라 동시 방송 수에 비례한다. 실측(explain, 12방·12만건, 재생
        // 2분 지점): 반환 112건에 docsExamined 1,344(=방 수 배). 이 인덱스가 붙으면 112/112 가 된다.
        //
        // 방향은 ASC 다. 정렬 키가 _id 라 두 번째 필드의 방향이 경계에 영향을 주지 않는다(실측: ASC·DESC
        // 모두 112/112). 나머지 인덱스가 전부 ASC 라 그쪽에 맞춘다.
        String window = awaitIndex(ops.createIndex(new Index()
                .on("roomId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.ASC)), deadline, ceiling, "roomId+createdAt(VOD 윈도우)");

        return List.of(paging, dedup, ttl, window);
    }

    /**
     * 인덱스 생성 하나를 상한 안에서 기다린다 — 넘기면 <b>무엇을 어떻게 하라</b>까지 담아 번역한다.
     *
     * <p>{@code .block(Duration)} 이 던지는 것은
     * {@code IllegalStateException: Timeout on blocking read} 다. 그 문장만 보면 당직자가
     * "Mongo 가 죽었나" 와 "큰 컬렉션에 인덱스를 새로 만드는 중이었나" 를 가를 수 없는데, 조치가
     * 정반대다 — 전자는 Mongo 를 보고 후자는 <b>기다리거나 상한을 올려야</b> 한다. 저장소의 다른
     * 부팅 가드도 같은 이유로 전부 번역한다({@code ObservabilityExposureConfig} 의 포트 파싱 실패).
     *
     * <p>⚠️ <b>타입이 아니라 {@code cause} 로 가른다.</b> 처음엔 {@code IllegalStateException} 을
     * 통째로 잡았는데, 그러면 <b>타임아웃이 아닌</b> ISE 까지 삼켜 "10분을 기다렸다" 로 라벨을 갈아
     * 붙인다. 드라이버 자신이 그 타입을 만든다 — {@code mongodb-driver-core} 의
     * {@code Assertions} 가 {@code new IllegalStateException("state should be: ...")} 를 던지고,
     * {@code Mono.error(그 ISE).block(상한)} 은 그것을 <b>그대로 재던진다</b>({@code cause == null}).
     * 그러면 0ms 에 죽은 것을 당직자가 10분 기다린 줄 알고 Mongo 를 본다.
     *
     * <p>갈라내는 수는 {@code cause} 다 — <b>타임아웃만</b>
     * {@code cause = java.util.concurrent.TimeoutException} 이다(실측). 저장소 선례도 전부 갈라서
     * 잡는다({@code ChatKickRedisRepository} 의 {@code onErrorMap(RedisWrongType::matches, ..)},
     * {@code ObservabilityExposureConfig} 의 {@code catch (NumberFormatException)}).
     *
     * <p>이 번역이 실제로 덮는 갈래는 <b>소켓이 조용한 쪽 하나</b>다 — "서버를 영영 못 고르는" 모드는
     * {@code DataAccessResourceFailureException} 이라 ISE 가 아니고 여기 걸리지 않는다. 메시지가 두
     * 갈래를 적는 것은 당직자가 <b>무엇을 봐야 하는가</b>를 가리기 위해서이고, 이 catch 가 두 갈래를
     * 구분해 잡는다는 뜻은 아니다.
     */
    private static String awaitIndex(reactor.core.publisher.Mono<String> creation,
            long deadline, Duration ceiling, String label) {
        // 남은 시간을 그대로 넘긴다. 0 이나 음수에 보정을 두지 않는 이유는 실측이다 —
        //   block(Duration.ZERO)        즉시 던진다 (Timeout on blocking read for 0 NANOSECONDS)
        //   block(Duration.ofNanos(1))  즉시 던진다
        //   block(Duration.ofMillis(-5)) 즉시 던진다
        // 무한인 것은 <b>인자 없는</b> block() 뿐이다(CountDownLatch.await(0, ns) 가 바로 false).
        // 한때 여기 ofMillis(1) 폴백을 두고 "ZERO 는 무한이라" 고 적었는데 그건 재지 않은 추측이었고,
        // 그 폴백이 오히려 "최악 = 이 값" 을 "이 값 + (건수−1)×1ms" 로 만들었다 — 단위를 따진
        // 커밋에서 같은 종류의 흠을 하나 더 만든 셈이다.
        Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
        try {
            return creation.block(remaining);
        } catch (IllegalStateException e) {
            if (!(e.getCause() instanceof java.util.concurrent.TimeoutException)) {
                throw e;   // 타임아웃이 아닌 ISE — 라벨을 갈아붙이지 않고 그대로 올린다
            }
            throw new IllegalStateException(
                    "chat_messages 인덱스 생성이 " + humanize(ceiling) + " 안에 끝나지 않았다"
                            + "(이 상한은 네 인덱스 전체에 걸린다) — " + label
                            + ". 둘 중 하나다: (1) Mongo 가 응답하지 않는다(드라이버 기본값이 "
                            + "socketReadTimeoutMs=0 이라 이 상한이 유일한 경계다) — Mongo 를 볼 것. "
                            + "(2) 큰 컬렉션에 이 인덱스를 처음 만드는 중이다 — 실측 건당 0.0057ms 로 "
                            + "환산해 상한(INDEX_CREATE_CEILING)을 올리거나, 인덱스를 운영 절차로 "
                            + "미리 만들고 배포할 것. 이 상한으로 죽으면 재시작마다 빌드를 다시 쏘므로 "
                            + "CrashLoopBackOff 가 된다.", e);
        }
    }

    /**
     * 상한을 사람이 읽는 꼴로 — {@code Duration.toString()} 은 {@code PT10M} 으로 찍힌다.
     *
     * <p>이 문장은 당직자가 장애 중에 읽는다. 공들여 쓴 메시지 안에 ISO-8601 기간 표기가 섞이면
     * 그 공이 거기서 끊긴다.
     *
     * <p>분 단위가 따로 있는 이유도 같다 — 처음엔 초까지만 내렸는데 현재 상한
     * ({@link #INDEX_CREATE_CEILING})이 그러면 <b>{@code "600초"}</b>로 찍힌다. 당직자가 상한을
     * 올릴지 판단하는 수인데, 읽는 사람이 머리로 60 을 나눠야 하는 꼴로 주지 않는다. 테스트가 쓰는
     * 400ms 가 {@code "400ms"} 로 남는 것도 같은 이유다({@code "0초"} 면 수가 사라진다).
     */
    private static String humanize(Duration ceiling) {
        if (ceiling.toSecondsPart() == 0 && ceiling.toMillisPart() == 0 && ceiling.toMinutes() >= 1) {
            return ceiling.toMinutes() + "분";
        }
        return ceiling.toSeconds() >= 1 ? ceiling.toSeconds() + "초" : ceiling.toMillis() + "ms";
    }
}
