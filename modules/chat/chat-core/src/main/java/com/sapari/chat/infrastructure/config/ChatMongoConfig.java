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
     * 동안 이 프로세스는 이미 TCP 를 받고 있었다 — 그리고 이 앱에는 actuator/health 가 없어 쿠버네티스가
     * 걸 수 있는 프로브는 TCP 소켓 점검류뿐이라, 그 창 내내 Ready 로 답한다. 서버 선택 상한 30초가
     * 인덱스마다 걸리니 최악 90초 동안 <b>인덱스 없는 컬렉션으로 WS 핸드셰이크가 라우팅된다</b>.
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
     * 인덱스 3종 생성(멱등). clientMsgId unique partial($type:"string")은 애너테이션으로 표현 불가해
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

        return List.of(paging, dedup, ttl);
    }
}
