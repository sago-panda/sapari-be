package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.command.RemoveFilterWordCommand;
import com.sapari.chat.domain.exception.FilterWordRejectedException;
import com.sapari.chat.domain.model.ChatConstants;
import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatAuditLogRepository;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.infrastructure.config.DictionaryProfanityFilterFactory;
import com.sapari.chat.infrastructure.persistence.repository.ChatAuditLogJpaRepository;
import com.sapari.chat.infrastructure.persistence.repository.ChatAuditLogRepositoryImpl;
import com.sapari.chat.infrastructure.persistence.repository.ChatFilterWordJpaRepository;
import com.sapari.chat.infrastructure.persistence.repository.ChatFilterWordRepositoryImpl;
import com.sapari.chat.support.LiveSchema;
import com.sapari.global.time.TimeProvider;

/**
 * 사전 쓰기 두 건이 겹쳐도 검사가 합친 결과를 본다 — 실제 Postgres 에서.
 *
 * <p>목으로는 재현되지 않는다. 결함이 READ COMMITTED 에서 나온다 — 각 트랜잭션이 상대의 미커밋
 * 삭제를 못 보므로 둘 다 "하나가 남는다" 로 통과한다. 그래서 {@code NOT_SUPPORTED} 로 테스트
 * 트랜잭션을 걷어내고 스레드마다 제 트랜잭션을 쓰게 한다({@code ChatKickRecorderConcurrencyTest}
 * 와 같은 이유).
 *
 * <p><b>창을 여는 장치는 깨진 코드에서만 랑데부를 만든다.</b> 두 번째 읽기 뒤에서 상대를 상한까지
 * 기다린다. 잠금이 없으면 둘이 읽기를 마치고 만나 둘 다 지운다. 잠금이 있으면 뒤쪽은 읽기에 닿기
 * 전에 잠금에서 멈추므로 앞쪽은 상한만큼 헛되게 기다리고 커밋한다 — 그 뒤 뒤쪽이 남은 하나를 읽어
 * 거부된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(ManageFilterWordsConcurrencyTest.Wiring.class)
@DisplayName("사전 쓰기 — 동시 제거가 사전을 비우지 못한다")
class ManageFilterWordsConcurrencyTest {

    /** 상대를 기다리는 상한. 잠금이 있으면 앞쪽이 매번 이만큼 헛되게 기다린다. */
    private static final Duration RENDEZVOUS_TIMEOUT = Duration.ofMillis(200);

    private static final UUID ADMIN = UUID.randomUUID();

    /** 두 번째 읽기(화이트리스트) 뒤에 창을 연다 — 검사 직전이다. */
    static class LatchedFilterWordRepository implements ChatFilterWordRepository {

        private final ChatFilterWordRepository delegate;
        private volatile CountDownLatch bothRead;
        private final AtomicInteger waitedInVain = new AtomicInteger();

        LatchedFilterWordRepository(ChatFilterWordRepository delegate) {
            this.delegate = delegate;
        }

        void arm(CountDownLatch latch) {
            waitedInVain.set(0);
            bothRead = latch;
        }

        void disarm() {
            bothRead = null;
        }

        /** 상한까지 기다렸는데 상대가 오지 않은 횟수 — 1 이면 직렬화됐다는 직접 증거다. */
        int waitedInVain() {
            return waitedInVain.get();
        }

        @Override
        public void lockForWrite() {
            delegate.lockForWrite();
        }

        @Override
        public Set<String> findWords(FilterWordKind kind) {
            Set<String> words = delegate.findWords(kind);
            CountDownLatch latch = bothRead;
            if (latch != null && kind == FilterWordKind.WHITELIST) {
                latch.countDown();
                try {
                    if (!latch.await(RENDEZVOUS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        waitedInVain.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return words;
        }

        @Override
        public boolean add(FilterWordKind kind, String word, UUID createdBy) {
            return delegate.add(kind, word, createdBy);
        }

        @Override
        public boolean remove(FilterWordKind kind, String word) {
            return delegate.remove(kind, word);
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        LatchedFilterWordRepository chatFilterWordRepository(ChatFilterWordJpaRepository jpa) {
            return new LatchedFilterWordRepository(new ChatFilterWordRepositoryImpl(jpa));
        }

        @Bean
        ChatAuditLogRepository chatAuditLogRepository(ChatAuditLogJpaRepository jpa) {
            return new ChatAuditLogRepositoryImpl(jpa);
        }

        @Bean
        ChatFilterWordWriteRecorder chatFilterWordWriteRecorder(LatchedFilterWordRepository words,
                                                                ChatAuditLogRepository audits) {
            return new ChatFilterWordWriteRecorder(words, audits);
        }

        @Bean
        ManageFilterWordsService manageFilterWordsService(LatchedFilterWordRepository words,
                                                          ChatFilterWordWriteRecorder recorder) {
            ProfanityDictionaryPublishService publish = mock(ProfanityDictionaryPublishService.class);
            given(publish.publishFromSource()).willReturn(1L);
            return new ManageFilterWordsService(new ChatPermissionPolicy(), words, recorder, publish,
                    new DictionaryProfanityFilterFactory(),
                    new TimeProvider(Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC)));
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    @DynamicPropertySource
    static void schemaValidation(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeAll
    static void applyRealSchema() throws Exception {
        LiveSchema.applyTo(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Autowired
    private ManageFilterWordsService service;
    @Autowired
    private LatchedFilterWordRepository latched;
    @Autowired
    private ChatFilterWordJpaRepository jpa;
    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⭐ 남은 욕설 둘을 동시에 지우면 하나만 지워진다 — 둘 다 지워지면 사전이 비어 API 로 못 빠져나온다")
    void concurrentRemovalsCannotEmptyTheDictionary() throws Exception {
        // given: 욕설이 둘 남았다
        jpa.deleteAll();
        ChatFilterWordRepository plain = new ChatFilterWordRepositoryImpl(jpa);
        // 네이티브 쓰기라 트랜잭션이 필요하다 — 이 테스트는 NOT_SUPPORTED 다
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            plain.add(FilterWordKind.PROFANITY, "시발", ChatConstants.SYSTEM_SENDER_ID);
            plain.add(FilterWordKind.PROFANITY, "지랄", ChatConstants.SYSTEM_SENDER_ID);
        });

        CountDownLatch bothRead = new CountDownLatch(2);
        latched.arm(bothRead);
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        // when: 둘이 겹친다
        try {
            Thread a = remover("시발", failures);
            Thread b = remover("지랄", failures);
            a.start();
            b.start();
            assertThat(a.join(Duration.ofSeconds(30))).as("제거 A가 30초 안에 끝나지 않았다").isTrue();
            assertThat(b.join(Duration.ofSeconds(30))).as("제거 B가 30초 안에 끝나지 않았다").isTrue();
        } finally {
            latched.disarm();
        }

        // then: 뒤쪽은 앞쪽의 커밋을 읽고 "마지막 욕설" 로 거부된다
        assertThat(plain.findWords(FilterWordKind.PROFANITY))
                .as("둘 다 지워졌다 — 각자 '하나가 남는다' 로 통과했다")
                .hasSize(1);
        assertThat(failures).singleElement().isInstanceOf(FilterWordRejectedException.class);
        assertThat(latched.waitedInVain())
                .as("앞쪽이 상대를 헛되게 기다려야 한다 — 0 이면 둘이 읽기를 마치고 만났다(직렬화 안 됨)")
                .isEqualTo(1);
    }

    private Thread remover(String word, List<Throwable> failures) {
        return new Thread(() -> {
            try {
                service.remove(new RemoveFilterWordCommand("PROFANITY", word, ADMIN, "ADMIN"));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
    }
}
