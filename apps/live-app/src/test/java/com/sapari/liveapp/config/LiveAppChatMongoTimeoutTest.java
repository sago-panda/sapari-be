package com.sapari.liveapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;

import com.mongodb.MongoClientSettings;

/**
 * live-app 의 Mongo 연산 상한.
 *
 * <p><b>왜 이 칸이 있는가.</b> streaming-app 의 {@code chat.mongo.operation-timeout} 이 이 앱에
 * 적용되지 않는다 — 그쪽 설정을 들이지 않는다. 그래서 상한이 <b>이 앱 안에서</b> 걸리는지는 별
 * 축이고, 드라이버 기본값이 전체 연산 상한 <b>미설정</b> · socket read timeout <b>0</b> 이라
 * 빠뜨리면 멈춘 Mongo 아래에서 HTTP 워커가 무한정 점유된다.
 *
 * <p>컨텍스트를 띄우지 않고 빈 메서드를 직접 부른다 — 재는 것이 "커스터마이저가 상한을 거는가"
 * 이고 그건 컨텍스트 없이 관측된다. 컨텍스트로 띄우면 Mongo 연결까지 끌고 와서 재는 축이 흐려진다.
 */
@DisplayName("live-app Mongo 연산 상한")
class LiveAppChatMongoTimeoutTest {

    private final ChatModerationBeansConfig config = new ChatModerationBeansConfig();

    @Test
    @DisplayName("⭐ 상한이 드라이버 설정에 실제로 걸린다 — 빠뜨리면 기본값이 '무제한' 이다")
    void theOperationTimeoutReachesTheDriverSettings() {
        // given
        MongoClientSettingsBuilderCustomizer customizer =
                config.liveAppChatMongoOperationTimeoutCustomizer(Duration.ofSeconds(3));

        // when
        MongoClientSettings.Builder builder = MongoClientSettings.builder();
        customizer.customize(builder);
        MongoClientSettings settings = builder.build();

        // then: 값이 그대로 실려야 한다. 커스터마이저를 안 걸면 null(= 상한 없음)이다.
        assertThat(settings.getTimeout(TimeUnit.MILLISECONDS))
                .as("연산 상한이 드라이버 설정에 없다 — 멈춘 Mongo 아래에서 워커가 무한정 점유된다")
                .isEqualTo(3_000L);
    }

    @Test
    @DisplayName("⭐ 커스터마이저가 없으면 상한이 없다 — 이 칸이 위 칸의 분모다")
    void withoutTheCustomizerThereIsNoBound() {
        // 위 칸이 "걸린다" 를 재는데, 걸지 않았을 때 정말 비어 있는지를 같이 봐야 그 수가 뜻을 갖는다.
        assertThat(MongoClientSettings.builder().build().getTimeout(TimeUnit.MILLISECONDS))
                .as("드라이버 기본값에 이미 상한이 있다면 이 티켓의 전제가 바뀐다")
                .isNull();
    }

    @Test
    @DisplayName("⭐ 1ms 미만은 부팅을 막는다 — 조용히 통과하면 '설정했는데 안 걸린 파드' 가 된다")
    void aSubMillisecondBoundFailsBoot() {
        assertThatThrownBy(() -> config.liveAppChatMongoOperationTimeoutCustomizer(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("live.chat.mongo.operation-timeout");
    }
}
