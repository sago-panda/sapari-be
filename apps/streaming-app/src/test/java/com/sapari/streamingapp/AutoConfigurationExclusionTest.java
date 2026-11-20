package com.sapari.streamingapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * {@code excludeName}에 적힌 자동설정 이름이 <b>실재하는 클래스</b>인지 확인한다.
 *
 * <p>이 앱은 관계형 DB를 쓰지 않는데 chat-core를 통해 JPA 스타터가 런타임에만 올라온다. 그래서 제외를
 * 클래스가 아니라 문자열로 적어야 하고, 문자열은 컴파일러가 봐 주지 않는다 — 한 글자만 틀려도 제외는
 * 조용히 아무 일도 하지 않고, 그 사실은 <b>운영에서 부팅이 실패할 때</b> 처음 드러난다.
 *
 * <p>부팅 테스트가 이 자리를 대신하지 못한다: 이 앱의 컨텍스트를 세우려면 Redis·Mongo가 필요해
 * 그 테스트는 인프라가 없으면 다른 이유로 먼저 실패한다. 여기서는 컨텍스트를 세우지 않고 이름만 확인하므로
 * 언제 돌려도 같은 답이 나온다.
 */
@DisplayName("자동설정 제외 — 이름이 실재하는 클래스여야 한다")
class AutoConfigurationExclusionTest {

    @Test
    @DisplayName("excludeName의 모든 이름이 로드된다 — 오타는 제외를 조용히 무력화한다")
    void everyExcludedNameResolvesToARealClass() {
        // given
        SpringBootApplication annotation =
                StreamingAppApplication.class.getAnnotation(SpringBootApplication.class);

        // when & then
        assertThat(annotation.excludeName()).isNotEmpty();
        for (String name : annotation.excludeName()) {
            assertThatCode(() -> Class.forName(name))
                    .as("제외 대상 자동설정이 클래스패스에 없다 — 이름이 틀렸거나 의존이 사라졌다: %s", name)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("DataSource 자동설정이 제외 목록에 있다 — 빠지면 이 앱은 없는 DB 접속 정보를 요구한다")
    void dataSourceAutoConfigurationIsExcluded() {
        // given
        SpringBootApplication annotation =
                StreamingAppApplication.class.getAnnotation(SpringBootApplication.class);

        // when & then
        assertThat(annotation.excludeName())
                .contains("org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration");
    }
}
