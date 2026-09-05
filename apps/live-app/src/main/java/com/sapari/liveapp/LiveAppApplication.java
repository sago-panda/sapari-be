package com.sapari.liveapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.sapari.common.securityjwt.jwt.JwtTokenLifecycle;

// 리액티브 Mongo 자동설정을 끈다. chat-core 가 streaming-app 과 공유되면서 리액티브 드라이버를
// 런타임 클래스패스에 얹는데, 자동설정은 스테레오타입이 아니라 클래스패스 존재만 보고 뜬다 —
// 그대로 두면 이 앱이 쓰지도 않는 리액티브 클라이언트를 만들고 부팅 때마다 27017 에 접속을 시도한다
// (실측: Connection refused 스택이 매번 남는다). 강퇴 증거는 블로킹 MongoTemplate 으로 읽는다.
// 문자열로 적는 이유는 이 앱의 컴파일 클래스패스에 없는 클래스라서다.
@SpringBootApplication(excludeName = {
        "org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration",
        "org.springframework.boot.data.mongodb.autoconfigure.DataMongoReactiveAutoConfiguration",
        "org.springframework.boot.data.mongodb.autoconfigure.DataMongoReactiveRepositoriesAutoConfiguration"
})
// live-app은 토큰을 검증만 하고 발급/갱신하지 않는다. 발급 담당인 JwtTokenLifecycle은
// RefreshTokenStore 등 stateful store(common/auth)를 요구하므로 스캔에서 제외한다.
// 토큰 검증용 JwtTokenProvider는 그대로 스캔된다.
// chat 은 통째로 스캔에서 뺀다. chat-core 는 streaming-app 과 공유하는 모듈이라 리액티브 어댑터가
// @Repository 를 달고 있고, 그중 브로드캐스터는 생성자에서 Redis 에 즉시 접속한다 — 스캔에 걸리면
// 이 앱이 쓰지도 않는 리액티브 스택을 요구하고 채팅 팬아웃 노드가 되어 버린다.
// 클래스가 아니라 패키지로 빼는 것이 요점이다: chat 에 어댑터가 늘어도 여기를 고칠 일이 없다.
// 필요한 블로킹 조각은 ChatModerationBeansConfig 가 명시로 등록한다.
@ComponentScan(
        basePackages = "com.sapari",
        excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = JwtTokenLifecycle.class
                ),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.sapari\\.chat\\..*"
                )
        }
)
@EnableJpaRepositories({
        "com.sapari.live.infrastructure.persistence",
        "com.sapari.chat.infrastructure.persistence.repository"
})
@EntityScan({
        "com.sapari.live.infrastructure.persistence",
        "com.sapari.chat.infrastructure.persistence.entity"
})
@ConfigurationPropertiesScan
public class LiveAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiveAppApplication.class, args);
    }

}
