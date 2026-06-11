package com.sapari.chat.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.index.ReactiveIndexOperations;

import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

import lombok.RequiredArgsConstructor;

/**
 * chat_messages 인덱스 부팅 시 생성 (멱등 — 동일 스펙 재생성은 no-op).
 *
 * <p>clientMsgId unique partial($type:"string")은 애너테이션으로 표현이 불가능해
 * 인덱스 정의 전체를 여기 한곳으로 일원화한다. partial 필터를 $type으로 거는 이유:
 * $exists:true는 null 저장 필드도 매칭해 clientMsgId=null 2건째에 DuplicateKey가 터진다.
 */
@Configuration
@RequiredArgsConstructor
public class ChatMongoConfig {

    private final ReactiveMongoTemplate mongoTemplate;

    @Bean
    public ApplicationRunner chatMessagesIndexInitializer() {
        // 부팅 시 1회, 메인 스레드 — 이벤트루프 밖이라 block 허용
        return args -> createChatMessagesIndexes();
    }

    /** 인덱스 3종 생성 후 생성된 이름 목록 반환 (테스트 검증용). */
    public java.util.List<String> createChatMessagesIndexes() {
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

        return java.util.List.of(paging, dedup, ttl);
    }
}
