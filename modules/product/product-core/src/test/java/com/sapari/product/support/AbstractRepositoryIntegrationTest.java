package com.sapari.product.support;

import com.sapari.product.ProductPersistenceTestApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 리포지토리 어댑터 통합 테스트 공통 베이스. 모든 {@code *RepositoryImplTest}가 상속해 사용한다.
 *
 * <h2>효율성 설계 — 컨테이너는 전 테스트가 1개씩만 공유</h2>
 * Postgres·Redis 컨테이너를 {@code static} 필드 + {@code static} 초기화 블록에서 <b>단 한 번</b> 기동한다(싱글턴 패턴). JUnit의 {@code @Container}
 * 생명주기를 쓰지 않으므로 테스트 클래스마다 재기동되지 않고, JVM 종료 시 Ryuk가 정리한다. {@code @SpringBootTest} 컨텍스트도 동일 설정이라 Spring TestContext 캐시로
 * 재사용되어, 수십 개의 리포지토리 테스트가 컨테이너 2개 + 컨텍스트 1개만 공유한다. {@code withReuse(true)}로 JVM 재실행 간 재사용도 노린다.
 *
 * <h2>접속 정보 주입</h2>
 * {@link ServiceConnection} 이 Postgres(datasource)·Redis(spring.data.redis) 접속 정보를 자동 등록한다 — 수동
 * {@code @DynamicPropertySource}는 불필요. 다만 스키마는 실제 마이그레이션으로 구성하므로 flyway.locations만 동적으로 잡아준다(아래
 * {@link #flywayLocation}).
 *
 * <h2>격리</h2>
 * {@code @Transactional}로 각 테스트는 트랜잭션 롤백되어 서로 독립적이다(컨테이너·스키마는 유지). 단 영속성 1차 캐시가 조회를 가릴 수 있으므로, 라운드트립을 검증하는 테스트는
 * {@code EntityManager.flush()/clear()} 후 다시 조회한다.
 */
@SpringBootTest(classes = ProductPersistenceTestApplication.class)
@Transactional
public abstract class AbstractRepositoryIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).withReuse(true);

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
                    .withReuse(true);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * repo 루트의 {@code db/migration/product}를 Flyway 위치로 등록(gradle·IDE 작업 디렉터리 모두에서 상향 탐색).
     */
    @DynamicPropertySource
    static void flywayLocation(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.locations", () -> "filesystem:" + locateMigrationDir());
    }

    private static String locateMigrationDir() {
        Path dir = Paths.get("")
                .toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("db/migration/product"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("db/migration/product 디렉터리를 찾을 수 없습니다. (작업 디렉터리: "
                    + Paths.get("")
                    .toAbsolutePath() + ")");
        }
        return dir.resolve("db/migration/product")
                .toString();
    }
}
