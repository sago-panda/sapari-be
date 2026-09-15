package com.sapari.liveapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.sapari.common.securityjwt.jwt.JwtTokenLifecycle;

@SpringBootApplication
// live-app은 토큰을 검증만 하고 발급/갱신하지 않는다. 발급 담당인 JwtTokenLifecycle은
// RefreshTokenStore 등 stateful store(common/auth)를 요구하므로 스캔에서 제외한다.
// 토큰 검증용 JwtTokenProvider는 그대로 스캔된다.
@ComponentScan(
        basePackages = "com.sapari",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtTokenLifecycle.class
        )
)
@EnableJpaRepositories("com.sapari.live.infrastructure.persistence")
@EntityScan("com.sapari.live.infrastructure.persistence")
@ConfigurationPropertiesScan
public class LiveAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiveAppApplication.class, args);
    }

}
