package com.sapari.streamingapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;

/**
 * chat-core는 {@code com.sapari.chat.infrastructure}(Redis·Mongo 어댑터)만 스캔한다.
 * {@code application.service}(SendChatService 등)는 {@code ChatBroadcaster}(T10 미구현)에 의존해 아직
 * 와이어 불가 → T10에서 브로드캐스터 추가 시 스캔 범위를 넓힌다.
 *
 * <p>{@code @EnableReactiveMongoRepositories}: chat의 Spring Data Mongo 리포지토리(ChatMessageMongoRepository)는
 * 기본 스캔(메인 패키지) 밖이라 명시 활성화한다.
 */
@SpringBootApplication(scanBasePackages = {"com.sapari.streamingapp", "com.sapari.chat.infrastructure"})
@EnableReactiveMongoRepositories(basePackages = "com.sapari.chat.infrastructure.persistence.repository")
public class StreamingAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamingAppApplication.class, args);
    }

}
