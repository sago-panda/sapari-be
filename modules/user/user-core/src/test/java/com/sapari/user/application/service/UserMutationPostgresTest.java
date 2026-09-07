package com.sapari.user.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import com.sapari.user.application.support.WithdrawnUserRetentionMasker;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.domain.repository.WithdrawnUserRetentionRepository;
import com.sapari.user.infrastructure.persistence.mapper.UserMapperImpl;
import com.sapari.user.infrastructure.persistence.repository.UserJpaRepository;
import com.sapari.user.infrastructure.persistence.repository.UserRepositoryImpl;
import com.sapari.user.model.UserStatus;

/** 전용 빈 PostgreSQL DB에서 실제 repository와 트랜잭션으로 변경 유실을 검증한다. */
@SpringJUnitConfig(UserMutationPostgresTest.Config.class)
@EnabledIfEnvironmentVariable(named = "SAPARI_TEST_POSTGRES_URL", matches = ".+")
class UserMutationPostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Autowired private UserAccountService accounts;
    @Autowired private ProfileImageMutationProcessor images;
    @Autowired private UserRepository users;
    @Autowired private UserJpaRepository jpaUsers;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    /** 두 번째 이미지 요청이 첫 요청이 커밋한 key를 삭제 대상으로 돌려주는지 확인한다. */
    @Test
    void imageReplacementReadsLatestKey() throws Exception {
        UUID id = seedUser();
        raceImageUpdate(id, userId -> {
            var result = images.replaceProfileImageKey(userId, "image-c");
            assertThat(result.oldProfileImageKey()).isEqualTo("image-b");
        });
        assertThat(users.findById(id).orElseThrow().profileImageKey()).isEqualTo("image-c");
    }

    /** 닉네임 변경이 동시에 저장된 이미지 key를 과거 값으로 되돌리지 않아야 한다. */
    @Test
    void nicknamePreservesConcurrentImage() throws Exception {
        UUID id = seedUser();
        raceImageUpdate(id, userId -> accounts.changeNickname(userId,
                "new" + userId.toString().substring(0, 6), java.time.Duration.ofDays(30)));
        var user = users.findById(id).orElseThrow();
        assertThat(user.profileImageKey()).isEqualTo("image-b");
        assertThat(user.nickname()).startsWith("new");
    }

    /** 탈퇴 변경도 이미지 변경 결과를 보존하며 사용자 상태를 전환해야 한다. */
    @Test
    void withdrawalPreservesConcurrentImage() throws Exception {
        UUID id = seedUser();
        raceImageUpdate(id, accounts::requestWithdrawal);
        var user = users.findById(id).orElseThrow();
        assertThat(user.profileImageKey()).isEqualTo("image-b");
        assertThat(user.status()).isEqualTo(UserStatus.WITHDRAWING);
    }

    /** 다른 사용자의 변경은 잠긴 사용자 트랜잭션 종료를 기다리지 않는다. */
    @Test
    void differentUsersDoNotBlock() throws Exception {
        UUID first = seedUser();
        UUID second = seedUser();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                images.replaceProfileImageKey(first, "image-b");
                Future<?> other = executor.submit(() -> images.replaceProfileImageKey(second, "image-c"));
                awaitResult(other);
            });
        }
        assertThat(users.findById(second).orElseThrow().profileImageKey()).isEqualTo("image-c");
    }

    /** 잠긴 행의 요청은 무한 대기하지 않고 실제 PostgreSQL lock_timeout으로 실패한다. */
    @Test
    void lockWaitHasDatabaseTimeout() {
        UUID id = seedUser();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                images.replaceProfileImageKey(id, "image-b");
                Future<?> other = executor.submit(() -> images.replaceProfileImageKey(id, "image-c"));
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> other.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class)
                        .hasCauseInstanceOf(com.sapari.user.domain.exception.UserException.class)
                        .satisfies(error -> assertThat(((com.sapari.user.domain.exception.UserException) error.getCause())
                                .getErrorCode()).isEqualTo(com.sapari.user.domain.exception.UserErrorCode.USER_MUTATION_BUSY));
            });
        }
        assertThat(users.findById(id).orElseThrow().profileImageKey()).isEqualTo("image-b");
    }

    /** OSIV 등으로 이미 읽은 entity가 있어도 잠금 이후 DB 최신 상태를 사용해야 한다. */
    @Test
    void refreshesEntityReadBeforeLock() {
        UUID id = seedUser();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(users.findById(id).orElseThrow().profileImageKey()).isEqualTo("image-a");
                awaitResult(executor.submit(() -> images.replaceProfileImageKey(id, "image-b")));
                var result = images.replaceProfileImageKey(id, "image-c");
                assertThat(result.oldProfileImageKey()).isEqualTo("image-b");
            });
        }
    }

    /** 트랜잭션 없는 잠금 조회로 잠금이 조기에 해제되는 잘못된 호출을 차단한다. */
    @Test
    void requiresMutationTransaction() {
        UUID id = seedUser();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> users.findByIdForUpdate(id))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 첫 변경의 커밋을 지연하고 DB에서 두 번째 요청의 실제 잠금 대기를 확인한다. */
    private void raceImageUpdate(UUID id, Consumer<UUID> secondMutation) throws Exception {
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        images.replaceProfileImageKey(id, "image-b");
                        // UPDATE를 DB에 전송해 잠금 없는 구현에서도 동일한 경합을 재현한다.
                        jpaUsers.flush();
                        saved.countDown();
                        awaitLatch(commit);
                    }));
            try {
                // 첫 JPA 호출의 초기화 비용은 DB 잠금 대기 시간과 구분한다.
                if (!saved.await(20, TimeUnit.SECONDS)) {
                    awaitResult(first);
                    throw new AssertionError("첫 변경이 준비되지 않았습니다.");
                }
                Future<?> second = executor.submit(() -> secondMutation.accept(id));
                try {
                    awaitDatabaseLock();
                } finally {
                    commit.countDown();
                }
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
            } finally {
                commit.countDown();
            }
        }
    }

    /** 고정 sleep 대신 PostgreSQL이 보고하는 잠금 대기를 제한 시간 안에 확인한다. */
    private void awaitDatabaseLock() throws InterruptedException {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject("select count(*) from pg_stat_activity "
                    + "where datname = current_database() and cardinality(pg_blocking_pids(pid)) > 0", Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("두 번째 요청이 DB 잠금 대기에 진입하지 않았습니다.");
    }

    /** 테스트 전용 사용자만 넣으며 기존 애플리케이션 데이터에는 연결하지 않는다. */
    private UUID seedUser() {
        UUID id = UUID.randomUUID();
        String suffix = id.toString().replace("-", "");
        new JdbcTemplate(dataSource).update("insert into user_schema.users "
                + "(id, created_at, updated_at, role, status, nickname, nickname_changed_at, "
                + "phone_number, email, grade, point_balance, marketing_agreed, profile_image_key) "
                + "values (?, now(), now(), 'USER', 'ACTIVE', ?, '2026-01-01', ?, ?, 'BRONZE', 0, false, 'image-a')",
                id, suffix.substring(0, 10), suffix.substring(0, 11), suffix + "@test.invalid");
        return id;
    }

    /** worker의 실패와 timeout을 테스트 실패로 전파한다. */
    private static void awaitResult(Future<?> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** 동시 실행 테스트가 실패해도 worker를 무한히 대기시키지 않는다. */
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("커밋 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableJpaRepositories(basePackageClasses = UserJpaRepository.class)
    static class Config {
        /** 명시적으로 지정한 빈 테스트 DB만 허용하고 기존 users 테이블이 있으면 중단한다. */
        @Bean
        DataSource dataSource() {
            String url = System.getenv("SAPARI_TEST_POSTGRES_URL");
            if (!url.endsWith("/lock_test")) {
                throw new IllegalArgumentException("전용 lock_test DB만 사용할 수 있습니다.");
            }
            var source = new DriverManagerDataSource(url, "lock_test", "local-test-only");
            JdbcTemplate jdbc = new JdbcTemplate(source);
            if (jdbc.queryForObject("select to_regclass('user_schema.users') is not null", Boolean.class)) {
                throw new IllegalStateException("기존 users 테이블이 있는 DB에서는 실행하지 않습니다.");
            }
            jdbc.execute("create schema if not exists user_schema");
            return source;
        }

        /** 테스트 전용 DB에 실제 entity 매핑을 생성하고 컨텍스트 종료 시 정리한다. */
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.sapari.user.infrastructure.persistence.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }

        /** 서비스 프록시에 실제 PostgreSQL 트랜잭션을 제공한다. */
        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        /** 실제 JPA 조회·매핑·저장 경로를 연결한다. */
        @Bean
        UserRepository users(UserJpaRepository repository) {
            return new UserRepositoryImpl(repository, new UserMapperImpl());
        }

        /** 이미지 key mutation에 실제 트랜잭션 프록시를 적용한다. */
        @Bean
        ProfileImageMutationProcessor images(UserRepository users) {
            return new ProfileImageMutationProcessor(users);
        }

        /** 사용자 변경을 실행하며 이 테스트와 무관한 보존정보 저장만 대체한다. */
        @Bean
        UserAccountService accounts(UserRepository users, ProfileImageMutationProcessor images) {
            return new UserAccountService(users, mock(WithdrawnUserRetentionRepository.class), null, null,
                    new WithdrawnUserRetentionMasker(), null, null, images, null, key -> key,
                    new com.sapari.global.time.TimeProvider(java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)));
        }
    }
}
