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
     * <p><b>그 창의 길이는 최악 ~30초다.</b> 서버 선택 상한이 인덱스마다 걸리지만 누적되지 않는다 —
     * 이 클래스에 {@code catch} 가 0건이고 인덱스 생성은 {@code .block()} 순차 대입 넷이라, 첫
     * {@code block()} 이 상한을 소진하고 던지면 메서드가 그 자리에서 끝난다(나머지 셋은 평가조차
     * 되지 않는다). 같은 이유로 "인덱스를 하나 더 만들면 30초가 늘어난다" 도 성립하지 않는다.
     * 한때 이 자리에 "4종이면 최악 120초" 라고 적었는데, 그 근거로 든 실측값(상한 소진 → <b>던짐</b>)이
     * 바로 누적을 불가능하게 만든다 — 수와 근거가 서로를 부정하고 있었다.
     *
     * <p><b>프로브가 그 창에 무엇을 보는지는 설정에 달렸다</b>(한때 "이 앱에는 actuator/health 가 없어
     * TCP 점검뿐" 이라 적었는데 지금은 거짓이다 — 이 앱은 액추에이터를 {@code implementation} 으로
     * 갖고, {@code ObservabilityExposureConfig} 가 {@code health} 의 실제 노출을 부팅 조건으로 요구한다):
     * <ul>
     *   <li>{@code httpGet} 프로브 — 이 빈은 포트 bind 보다 앞이라 그 창에서 connection refused 가
     *       되고 파드가 Ready 가 <b>되지 않는다</b>. 즉 이 문단이 두려워한 모양의 반대다.</li>
     *   <li>TCP 소켓 점검 — 여전히 그 창 내내 Ready 로 답한다. 매니페스트가 이쪽을 쓰면 위험이 남는다.</li>
     * </ul>
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
     * 인덱스 4종 생성(멱등). clientMsgId unique partial($type:"string")은 애너테이션으로 표현 불가해
     * 인덱스 정의 전체를 여기로 일원화한다. $type을 쓰는 이유: $exists:true는 null 저장 필드도 매칭해
     * clientMsgId=null 2건째에 DuplicateKey가 터진다. 생성된 인덱스 이름 목록 반환(테스트 검증용).
     */
    public static List<String> createChatMessagesIndexes(ReactiveMongoTemplate mongoTemplate) {
        ReactiveIndexOperations ops = mongoTemplate.indexOps(ChatMessageDocument.class);

        String paging = ops.createIndex(new Index()
                .on("roomId", Sort.Direction.ASC)
                .on("_id", Sort.Direction.ASC)).block();

        String dedup = ops.createIndex(new Index()
                .on("roomId", Sort.Direction.ASC)
                .on("senderId", Sort.Direction.ASC)
                .on("clientMsgId", Sort.Direction.ASC)
                .unique()
                .partial(PartialIndexFilter.of(
                        new org.bson.Document("clientMsgId", new org.bson.Document("$type", "string"))))).block();

        String ttl = ops.createIndex(new Index()
                .on("createdAt", Sort.Direction.ASC)
                .expire(Duration.ofDays(730))).block();   // 2년 자동 삭제

        // VOD 윈도우(roomId 등치 + createdAt 범위)의 범위 필드를 인덱스 경계로 묶는다.
        //
        // 없으면 플래너가 위 TTL 의 createdAt 단일 인덱스를 고르는데, 그건 그 시간대의 <b>모든 방</b>을
        // 훑는다 — 비용이 방 길이가 아니라 동시 방송 수에 비례한다. 실측(explain, 12방·12만건, 재생
        // 2분 지점): 반환 112건에 docsExamined 1,344(=방 수 배). 이 인덱스가 붙으면 112/112 가 된다.
        //
        // 방향은 ASC 다. 정렬 키가 _id 라 두 번째 필드의 방향이 경계에 영향을 주지 않는다(실측: ASC·DESC
        // 모두 112/112). 나머지 인덱스가 전부 ASC 라 그쪽에 맞춘다.
        String window = ops.createIndex(new Index()
                .on("roomId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.ASC)).block();

        return List.of(paging, dedup, ttl, window);
    }
}
