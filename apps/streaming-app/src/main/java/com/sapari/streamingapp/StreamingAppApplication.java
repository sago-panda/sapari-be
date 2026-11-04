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
 *
 * <p><b>이 앱은 관계형 DB를 쓰지 않는다.</b> 그런데 chat-core가 강퇴 로그 때문에 JPA 스타터를 의존하면서
 * 그 스타터가 여기까지 전이로 올라왔고, {@code DataSourceAutoConfiguration}은 클래스패스에 있기만 하면 켜진다.
 * 그러면 이 앱은 있지도 않은 접속 정보를 요구하며 부팅에 실패한다. 자동설정을 잘라 그 연결을 끊는다 —
 * Hibernate·JPA 트랜잭션 자동설정은 DataSource 빈에 조건이 걸려 있어 함께 꺼진다.
 *
 * <p>클래스가 아니라 <b>이름으로</b> 제외한다. 그 스타터는 chat-core가 {@code implementation}으로 들여와
 * 여기엔 런타임에만 올라오므로, {@code exclude = DataSourceAutoConfiguration.class}로 쓰면 컴파일이 깨진다.
 * {@code excludeName}이 그 경우를 위한 것이다. 대신 오타가 나도 컴파일러가 잡아주지 못하고 제외만 조용히
 * 무력해지므로, 그 이름이 실재하는 클래스인지를 {@code AutoConfigurationExclusionTest}가 확인한다.
 *
 * <p>같은 이유로 <b>chat-core의 블로킹 어댑터는 컴포넌트 스캔 대상이 아니다</b>. 스캔되면 여기에도 빈이
 * 만들어지려 하고, 그 의존(JPA·블로킹 Mongo)이 이 앱엔 없다. 블로킹 어댑터는 그 스택을 실제로 가진
 * 앱(live-app)이 명시로 등록한다 — 이 앱이 제외 목록을 관리하는 방식은 어댑터가 늘 때마다 빠뜨릴 자리를
 * 하나씩 만든다.
 */
@SpringBootApplication(
        scanBasePackages = {"com.sapari.streamingapp", "com.sapari.chat"},
        excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@EnableReactiveMongoRepositories(basePackages = "com.sapari.chat.infrastructure.persistence.repository")
public class StreamingAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamingAppApplication.class, args);
    }

}
