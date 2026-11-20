package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;

import com.mongodb.MongoClientSettings;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

/**
 * Mongo 연산 상한이 <b>클라이언트 설정까지</b> 도달한다.
 *
 * <p>왜 클라이언트 쪽인가(실측으로 정한 것이다). 멈춘 insert 를 두 방식으로 끊어 보고 <b>끊은 뒤
 * 커넥션이 돌아오는지</b>를 같이 쟀다 — failpoint 로 insert 를 3,000ms 막고 풀을 1 로 묶어
 * 두 번째 연산의 지연을 본다:
 * <pre>
 *                              첫 연산                        두 번째 연산
 * 상한 없음                    3,090ms 완료                   5ms      ← 대조군
 * 전역 CSOT 500ms              547ms 끊김                     22ms     ← 커넥션 반납됨
 * 호출부 .timeout(500ms)       553ms 끊김                     2,521ms  ← 반납 안 됨
 * </pre>
 * 호출부 상한은 {@code Mono} 는 끊지만 커넥션이 서버 응답까지 묶여, 그 세션의 <b>다음</b> 메시지가
 * 남은 차단 시간을 그대로 기다린다. 풀을 기본값으로 두고 재면 두 번째가 14ms 로 나와 그 문제가
 * <b>숨는다</b> — 그래서 풀을 1 로 묶어 쟀다.
 *
 * <p>프로퍼티로 받는 이유. Boot 4.0.6 에는 이 값을 받는 프로퍼티가 <b>없다</b>(실측:
 * {@code spring.mongodb.*} 12개 전수 · {@code MongoProperties} 필드 전수에 timeout 없음). 연결
 * 문자열 옵션({@code ?timeoutMS=})도 있지만 이 저장소는 {@code uri: ${MONGODB_URI:…}} 로 받으므로
 * 그 값은 env 를 넣는 사람 손에 있고 <b>기본값이 없다</b> — 지금 결함이 "기본값이 무한" 인데
 * 기본값 없는 창구로 고치면 같은 자리로 돌아온다.
 */
@DisplayName("Mongo 연산 상한 — 설정이 클라이언트까지 간다")
class ChatMongoTimeoutConfigTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                // ⚠️ Duration 변환기를 깔아 준다 — 맨 러너에는 없고 운영에서는 Boot 가 깐다. 없으면
                // "String → Duration 변환기 없음" 으로 빈 생성이 실패해, 상한이 안 들어간 것과
                // 하네스가 모자란 것이 같은 적색으로 보인다(실측으로 그 적색을 한 번 받았다).
                .withInitializer(ctx -> ctx.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(ChatMongoConfig.class);
    }

    /** 컨텍스트의 커스터마이저 전부를 빌더에 적용한 결과 — 운영에서 Boot 가 하는 것과 같은 순서다. */
    private MongoClientSettings applyCustomizers(
            org.springframework.context.ApplicationContext context) {
        MongoClientSettings.Builder builder = MongoClientSettings.builder();
        context.getBeansOfType(MongoClientSettingsBuilderCustomizer.class).values()
                .forEach(c -> c.customize(builder));
        return builder.build();
    }

    @Test
    @DisplayName("⭐ 상한이 1ms 미만이면 기동을 실패시킨다 — 0 은 CSOT 에서 '상한 없음' 이다")
    void aTimeoutBelowOneMillisecondFailsStartup() {
        // 0 과 음수를 따로 본다: `< 1` 대신 `== 0` 으로 적는 회귀가 한 값만 보는 칸으로는 안 드러난다.
        // 0 이 특히 위험한 이유는 CSOT 에서 그 값이 "상한 없음" 이라, 조용히 이 티켓 전 상태로
        // 돌아가면서 설정은 들어간 것처럼 보이기 때문이다.
        for (String bad : new String[] {"chat.mongo.operation-timeout=0ms", "chat.mongo.operation-timeout=-1ms"}) {
            runner().withPropertyValues(bad).run(context -> assertThat(context)
                    .as("%s 로도 떴다 — 상한 없이 도는 파드가 된다", bad)
                    .hasFailed());
        }
    }

    @Test
    @DisplayName("⭐ 거부 사유가 프로퍼티 이름을 말한다 — 기동 로그만 보고 어느 키인지 알 수 있게")
    void theStartupFailureNamesTheProperty() {
        // 최상위는 Spring 의 빈 생성 래퍼라 스택 전체를 본다(SPR-176 에서 같은 자리를 쟀다).
        runner().withPropertyValues("chat.mongo.operation-timeout=0ms").run(context -> assertThat(context)
                .getFailure().hasStackTraceContaining("chat.mongo.operation-timeout"));
    }

    @Test
    @DisplayName("1ms 는 받는다 — 하한을 한 칸 안쪽으로 잡는 회귀를 막는다(항상 거부가 아니다)")
    void aTimeoutOfOneMillisecondIsAccepted() {
        runner().withPropertyValues("chat.mongo.operation-timeout=1ms").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(applyCustomizers(context).getTimeout(TimeUnit.MILLISECONDS)).isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("⭐ 부팅 인덱스 경로는 전역 상한을 되올려 받는다 — 전역이 그걸 자르면 회귀다")
    void theIndexPathOverridesTheGlobalTimeout() {
        // given: 전역 CSOT 는 **createIndexes 까지 자른다**(실측: 전역 500ms 에서 3,000ms 차단된
        // createIndexes 가 563ms 에 InterruptedByTimeoutException, 전역 없으면 3,104ms 완료).
        // 그런데 ReactiveIndexOperations·ReactiveMongoTemplate 어디에도 되올릴 핸들이 없다(전수 확인).
        // 그래서 팩토리를 감싸 그 템플릿의 모든 연산에 인덱스 상한을 싣는다 — 인덱스 코드는 그대로다.
        MongoDatabase raw = mock(MongoDatabase.class);
        MongoDatabase bounded = mock(MongoDatabase.class);
        given(raw.withTimeout(anyLong(), any())).willReturn(bounded);
        ReactiveMongoDatabaseFactory delegate = mock(ReactiveMongoDatabaseFactory.class);
        given(delegate.getMongoDatabase()).willReturn(Mono.just(raw));

        // when
        ReactiveMongoDatabaseFactory bounded_factory =
                ChatMongoConfig.indexTimeoutFactory(delegate, Duration.ofMinutes(10));

        // then: 되올린 핸들이 나와야 한다. 그리고 **얼마로** 되올렸는지도 본다 — 호출만 보면
        // 0 으로 되올리는 구현도 통과한다.
        assertThat(bounded_factory.getMongoDatabase().block(Duration.ofSeconds(5))).isSameAs(bounded);
        then(raw).should().withTimeout(Duration.ofMinutes(10).toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("⭐ 설정한 상한이 클라이언트 설정에 들어간다 — 기본과 다른 값으로 '우연히 같음'을 지운다")
    void theConfiguredTimeoutReachesTheClientSettings() {
        runner().withPropertyValues("chat.mongo.operation-timeout=750ms").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(applyCustomizers(context).getTimeout(TimeUnit.MILLISECONDS))
                    .as("상한이 클라이언트에 안 들어가면 드라이버 기본값(무한)이 그대로다")
                    .isEqualTo(750L);
        });
    }
}
