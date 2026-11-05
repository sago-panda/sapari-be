package com.sapari.liveapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.sapari.common.auth.RefreshTokenRedisRepository;
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
// live-app은 토큰을 검증만 하고 발급/갱신하지 않는다. 발급 담당인 JwtTokenLifecycle과,
// 그것이 요구하는 RefreshTokenStore 구현(RefreshTokenRedisRepository)을 스캔에서 제외한다.
// 토큰 검증용 JwtTokenProvider는 그대로 스캔된다.
//
// common/auth 는 클래스패스에 있다 — 폐기 조회(TokenRevocationFilter)가 그 모듈의 읽는 구현
// 둘을 쓴다(AccessTokenBlacklistRedisRepository · SessionRevocationRedisRepository). 셋 다
// @Repository 라 basePackages="com.sapari" 스캔이 전부 집으므로, 빼는 것을 하나 적는다.
// 클래스로 빼는 이유: 그 모듈은 파일이 3개이고 늘어날 자리가 아니다(패키지로 빼면 쓰려는
// 둘까지 같이 빠진다). 이 줄이 없으면 쓰지 않는 refresh 저장소가 빈으로 뜨고, 그때부터
// "이 앱은 발급/갱신을 하지 않는다" 는 위 문장이 빈 목록에서 거짓이 된다.
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
                        classes = {JwtTokenLifecycle.class, RefreshTokenRedisRepository.class}
                ),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.sapari\\.chat\\..*"
                ),
                // @SpringBootApplication 을 분해하면서 잃어버린 것을 되돌려 놓는다 — 그 애너테이션은
                // 이 필터를 기본으로 달고, 그게 테스트 소스의 설정이 앱 스캔에 끌려 들어오는 것을 막는다.
                // chat-core 의 테스트 애플리케이션이 같은 이유로 같은 필터를 되돌려 뒀다.
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
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
