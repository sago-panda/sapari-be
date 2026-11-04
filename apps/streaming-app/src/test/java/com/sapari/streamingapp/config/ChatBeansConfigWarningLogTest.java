package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 사전 적재 경고가 <b>실제로 로그에 남는지</b> 고정한다.
 *
 * <p>재는 것은 두 종류다 — 사전 단어와 <b>똑같은</b> 화이트리스트 항목이 있어 그 단어가 어느
 * 자리에서도 안 걸리는 경우, 그리고 어떤 욕설도 품지 않아 아무것도 보호하지 않는 항목.
 * 화이트리스트 항목을 <b>품기만</b> 하는 단어는 경고 대상이 아니고(그쪽은 2A·2B 가 그대로
 * 잡는다) 이 클래스의 테스트 하나가 그 사실을 따로 못 박는다.
 *
 * <p>이 WARN 은 "부팅을 세우지 않는다" 는 결정의 대가다. 전에는 같은 상황에서 기동이 실패했고,
 * 그 전제("영원히 매치되지 않는다")가 거짓이라 거부를 걷었다 — 대신 알려 주기로 했다. 그런데
 * 그 분기에 테스트가 없어서 <b>지워도 아무것도 안 깨졌다.</b> 로그는 지워도 조용한 자리라,
 * 재지 않으면 "적재했고 알려 준다" 중 뒤쪽 절반이 사라진 것을 알아챌 방법이 없다.
 *
 * <p>빈 메서드를 직접 부른다 — 판정이 그 안에 있어서, 호출이 사라지면 이 테스트가 함께 깨진다.
 */
@DisplayName("사전 적재 경고 — 아무 일도 하지 않는 항목을 알려 준다")
class ChatBeansConfigWarningLogTest {

    private static final Logger LOGGER = (Logger) LoggerFactory.getLogger(ChatBeansConfig.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        LOGGER.addAppender(appender);
    }

    @AfterEach
    void detach() {
        LOGGER.detachAppender(appender);
        appender.stop();
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    @DisplayName("⭐ 화이트리스트 항목과 같은 단어는 WARN 에 실린다 — 어느 자리에서도 안 걸린다")
    void aWordIdenticalToAWhitelistEntryIsWarnedAbout() {
        new ChatBeansConfig().profanityFilterSource("시발점", "");

        assertThat(warnings())
                .as("안 걸린다는 사실을 아무 데도 안 남기면 '막았다' 와 구분되지 않는다")
                .hasSize(1);
        assertThat(warnings().get(0).getFormattedMessage()).contains("시발점");
    }

    @Test
    @DisplayName("항목을 품기만 하는 단어는 경고하지 않는다 — span 이 더 넓어 정상 작동한다")
    void aWordMerelyContainingAnEntryIsNotWarnedAbout() {
        new ChatBeansConfig().profanityFilterSource("전염병같은놈", "");

        assertThat(warnings())
                .as("거짓 경고는 진짜 경고를 묻는다 — 이 단어는 실제로 마스킹된다")
                .isEmpty();
    }

    /**
     * 형제 분기도 같이 잰다.
     *
     * <p>직전 라운드에 {@code partiallyShadowed} 쪽 WARN 을 고정하면서 같은 커밋에서 추가한
     * {@code redundantWhitelist} 쪽은 빼먹었다 — 그 블록을 지워도 아무것도 안 깨졌다.
     * 이 테스트 클래스 자신의 javadoc 이 경고하는 모양을 같은 클래스가 다시 만든 것이다.
     */
    @Test
    @DisplayName("⭐ 아무것도 보호하지 않는 화이트리스트 항목도 같은 WARN 에 실린다")
    void anIneffectiveWhitelistEntryIsWarnedAbout() {
        new ChatBeansConfig().profanityFilterSource("", "감사합니다");

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0).getFormattedMessage()).contains("감사합니다");
    }

    @Test
    @DisplayName("가려지는 단어가 없으면 WARN 을 남기지 않는다 — 평상시 경고는 읽히지 않게 된다")
    void theOrdinaryCaseIsQuiet() {
        new ChatBeansConfig().profanityFilterSource("", "");

        assertThat(warnings()).isEmpty();
    }
}
