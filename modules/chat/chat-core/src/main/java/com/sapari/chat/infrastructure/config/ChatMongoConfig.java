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
 * <p>chat-core는 라이브러리라 reactive Mongo가 없는 소비처(live-app=blocking mongo)나
 * Redis 전용 테스트 컨텍스트에도 컴포넌트 스캔된다. 그래서 인덱스 생성은 {@code ObjectProvider}로
 * ReactiveMongoTemplate 존재 여부를 런타임에 판정해 없으면 건너뛴다(부팅 실패 방지).
 */
@Configuration
public class ChatMongoConfig {

    /**
     * roomId/senderId가 UUID 필드라 standard BSON binary 인코딩이 필요하다(미설정 시 드라이버가 인코딩 거부).
     * per-dev application.yml에 의존하지 않도록 코드로 고정한다 — yml 누락 시 "테스트 green/런타임 깨짐" 방지.
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
