package com.sapari.chat.infrastructure.config;

import java.time.Duration;
import java.util.List;

import org.bson.UuidRepresentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
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

    /** reactive Mongo가 있는 런타임(streaming-app 등)에서만 인덱스 생성. 없으면 skip(live-app·redis-only test 안전). */
    @Bean
    public ApplicationRunner chatMessagesIndexInitializer(ObjectProvider<ReactiveMongoTemplate> templateProvider) {
        return args -> {
            ReactiveMongoTemplate template = templateProvider.getIfAvailable();
            if (template != null) {
                createChatMessagesIndexes(template);
            }
        };
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
