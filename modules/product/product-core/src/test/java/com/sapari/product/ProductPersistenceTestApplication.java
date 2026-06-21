package com.sapari.product;

import com.sapari.storage.db.config.JpaAuditConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * product-core 영속 통합 테스트 전용 부트스트랩.
 *
 * <p>product-core는 라이브러리 모듈이라 자체 {@code @SpringBootApplication}이 없어 {@code @DataJpaTest}/
 * {@code @SpringBootTest}가 앵커로 삼을 설정이 필요하다. {@code com.sapari.product} 패키지를 컴포넌트 스캔하여
 * {@code JpaConfiguration}(@EnableJpaRepositories/@EntityScan) · 모든 {@code @Repository} 어댑터 · MapStruct
 * {@code @Mapper(componentModel=spring)} 빈을 로드하고, 자동 구성으로 DataSource/JPA/Flyway/Redis를 켠다.
 *
 * <p>{@link JpaAuditConfig}(@EnableJpaAuditing)는 {@code com.sapari.storage.db} 패키지라 스캔 대상이 아니므로
 * 명시적으로 import 한다 — 이게 없으면 BaseEntity의 {@code @CreatedDate}/{@code @LastModifiedDate}가 채워지지 않아 NOT NULL 인
 * created_at/updated_at INSERT가 실패한다.
 */
@SpringBootApplication
@Import(JpaAuditConfig.class)
public class ProductPersistenceTestApplication {
}
