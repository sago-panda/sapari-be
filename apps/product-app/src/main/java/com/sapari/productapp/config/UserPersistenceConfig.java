package com.sapari.productapp.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 사용자 영속 계층 활성화. JWT 인증의 {@code UserDetailsService}가 사용자 권한을 조회하려면
 * {@code user-core}의 엔티티·리포지토리가 함께 스캔되어야 한다.
 *
 * <p>상품 영속 계층은 {@code product-core}의 {@code JpaConfiguration}이 담당하며, Spring Boot가
 * 두 설정의 {@code @EntityScan} 패키지를 병합한다(상품 + 사용자 모두 매핑된다).
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.sapari.user.infrastructure.persistence")
@EntityScan(basePackages = "com.sapari.user.infrastructure.persistence")
public class UserPersistenceConfig {
}
