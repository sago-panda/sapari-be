package com.sapari.streamingapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;

/**
 * chat-core 전체({@code com.sapari.chat})를 스캔한다 — infrastructure 어댑터 + application.service(SendChatService 등)
 * + application.handler(ChatBroadcastSubscriber). 순수 정책(ChatPermissionPolicy·ProfanityFilter)·TimeProvider는
 * @Component가 아니라 {@code ChatBeansConfig}가 @Bean으로 제공한다.
 *
 * <p>{@code @EnableReactiveMongoRepositories}: chat의 Spring Data Mongo 리포지토리(ChatMessageMongoRepository)는
 * 기본 스캔(메인 패키지) 밖이라 명시 활성화한다.
 */
@SpringBootApplication(scanBasePackages = {"com.sapari.streamingapp", "com.sapari.chat"})
@EnableReactiveMongoRepositories(basePackages = "com.sapari.chat.infrastructure.persistence.repository")
public class StreamingAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamingAppApplication.class, args);
    }

}
