package com.sapari.product;

import com.sapari.storage.db.config.JpaAuditConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * product-core 영속 통합 테스트 전용 부트스트랩.
 *
 * <p>product-core는 라이브러리 모듈이라 자체 {@code @SpringBootApplication}이 없어 {@code @SpringBootTest}가
 * 앵커로 삼을 설정이 필요하다. 컴포넌트 스캔은 {@code com.sapari.product.infrastructure}로 한정해
 * {@code @Repository} 어댑터 · MapStruct {@code @Mapper(componentModel=spring)} 빈만 로드한다.
 * 자동 구성으로 DataSource/JPA/Flyway/Redis를 켠다.
 *
 * <p><b>application 계층(@Service)은 일부러 스캔하지 않는다.</b> 영속 통합 테스트는 repository 어댑터만 검증하며,
 * 서비스는 {@code TimeProvider} 등 app-layer 의존(이 컨텍스트에 없음)을 요구해 컨텍스트 로드를 깨뜨린다. 서비스는 Mockito
 * 단위 테스트로 검증한다.
 *
 * <p>{@link JpaConfiguration}(@EnableJpaRepositories/@EntityScan)은 루트 패키지라 좁힌 스캔에 안 잡히므로 명시 import.
 * {@link JpaAuditConfig}(@EnableJpaAuditing)도 {@code com.sapari.storage.db} 패키지라 명시 import — 없으면 BaseEntity의
 * {@code @CreatedDate}/{@code @LastModifiedDate}가 안 채워져 NOT NULL created_at/updated_at INSERT가 실패한다.
 */
@SpringBootApplication(scanBasePackages = "com.sapari.product.infrastructure")
@Import({JpaConfiguration.class, JpaAuditConfig.class})
public class ProductPersistenceTestApplication {
}
