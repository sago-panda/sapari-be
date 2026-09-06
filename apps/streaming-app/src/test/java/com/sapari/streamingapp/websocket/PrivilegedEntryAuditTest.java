package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 관리자가 남의 방에 들어온 사실이 <b>실제로 남는지</b>를 고정한다.
 *
 * <p>로그는 지워도 아무것도 안 깨지는 대표적인 자리다. 여기서 재지 않으면 이 줄은 조용히 사라질 수 있고,
 * 그러면 "누가 언제 이 방을 들여다봤는가"에 답할 수단이 없어진다 — 사라진 것을 알아챌 방법도 없다.
 *
 * <p>내용까지 본다. 관리자가 본 것을 로그가 복제하면 로그 자체가 개인정보 사본이 되므로,
 * <b>이메일·닉네임이 실리지 않는 것</b>도 이 테스트가 지킨다.
 */
@DisplayName("관리자 입장 감사 — 특권을 쓴 흔적이 남는다")
class PrivilegedEntryAuditTest {

    private static final UUID ROOM = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String EMAIL = "admin@example.com";
    private static final String NICKNAME = "운영자닉";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(ChatWebSocketHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    /** 핸들러 인스턴스가 필요 없는 정적 판정이라 리플렉션으로 부른다 — 전체 파이프라인을 세우지 않는다. */
    private void audit(ChatSession session) throws Exception {
        Method method = ChatWebSocketHandler.class
                .getDeclaredMethod("auditPrivilegedEntry", ChatSession.class);
        method.setAccessible(true);
        method.invoke(null, session);
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("⭐ 관리자가 남의 방에 들어오면 남는다 — 없으면 특권 사용에 답할 기록이 0이다")
    void adminEnteringSomeoneElsesRoomIsRecorded() throws Exception {
        // given: 방 주인이 아닌 관리자
        audit(new ChatSession(ROOM, ADMIN, ChatRole.ADMIN, NICKNAME, EMAIL, false));

        // then
        assertThat(messages())
                .as("관리자 입장이 어디에도 남지 않는다")
                .anySatisfy(message -> assertThat(message)
                        .contains("관리자 입장")
                        .contains(ADMIN.toString())
                        .contains(ROOM.toString()));
    }

    @Test
    @DisplayName("⭐ 이메일·닉네임은 남기지 않는다 — 감사는 접근을 남기는 것이지 본 것을 복제하는 게 아니다")
    void doesNotCopyWhatTheAdminSaw() throws Exception {
        // given
        audit(new ChatSession(ROOM, ADMIN, ChatRole.ADMIN, NICKNAME, EMAIL, false));

        // then: 실으면 로그가 그 자체로 개인정보 사본이 된다
        assertThat(messages()).allSatisfy(message ->
                assertThat(message).doesNotContain(EMAIL).doesNotContain(NICKNAME));
    }

    /**
     * 감사 판정의 {@code !isRoomOwner}는 <b>지금 도달할 수 없다</b> — 세션 불변식이 그 조합을 먼저 막는다.
     *
     * <p>그럼에도 가드를 남기는 이유는, 그것이 방어가 아니라 <b>정책</b>이라서다: 자기 방을 보는 것은
     * 특권 사용이 아니다. live가 ADMIN에게 {@code owner=true}를 발급하는 경로가 있고 chat이 그 조합을
     * 거부하는 상태(이월 항목)라, 그쪽이 풀리는 날 이 가드가 곧바로 옳은 동작이 된다. 지우면 그날
     * 자기 방을 보는 관리자가 특권 사용으로 기록된다.
     *
     * <p>그래서 여기서 재는 것은 "그 케이스에서 안 남는다"가 아니라 <b>왜 그 케이스를 만들 수 없는가</b>다.
     * 불변식이 바뀌면 이 테스트가 먼저 깨져서, 그때 감사 판정을 함께 보게 된다.
     */
    @Test
    @DisplayName("관리자는 방 주인이 될 수 없다 — 그래서 자기 방 케이스는 지금 만들어지지 않는다")
    void adminCannotBeARoomOwnerToday() {
        // when & then
        assertThatThrownBy(() -> new ChatSession(ROOM, ADMIN, ChatRole.ADMIN, NICKNAME, EMAIL, true))
                .as("세션 불변식이 바뀌었다 — 감사 판정의 자기 방 분기를 함께 확인할 것")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("판매자·구매자 입장은 남기지 않는다 — 남기면 모든 입장이 감사 로그가 된다")
    void ordinaryEntriesAreNotRecorded() throws Exception {
        // given
        audit(new ChatSession(ROOM, UUID.randomUUID(), ChatRole.SELLER, NICKNAME, EMAIL, false));
        audit(new ChatSession(ROOM, UUID.randomUUID(), ChatRole.BUYER, NICKNAME, EMAIL, false));

        // then
        assertThat(messages()).isEmpty();
    }
}
