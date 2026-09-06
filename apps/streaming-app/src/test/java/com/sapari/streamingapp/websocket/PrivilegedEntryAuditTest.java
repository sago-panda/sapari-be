package com.sapari.streamingapp.websocket;

import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import reactor.core.publisher.Mono;

/**
 * 관리자가 남의 방에 들어온 사실이 <b>실제로 남는지</b>를 고정한다.
 *
 * <p>이 로그는 SPR-135에서 들어왔고 <b>테스트가 없었다</b>. 로그는 지워도 아무것도 안 깨지는 대표적인
 * 자리라, 재지 않으면 조용히 사라질 수 있고 사라진 것을 알아챌 방법도 없다. 관리자는 방 소유와 무관하게
 * 어느 방에서든 마스킹 전 원문과 발신자 이메일을 받으므로, 이 줄이 그 특권을 쓴 유일한 흔적이다.
 *
 * <p>내용까지 본다. 관리자가 <b>본 것</b>을 로그가 복제하면 로그 자체가 개인정보 사본이 되므로,
 * 이메일·닉네임이 실리지 않는 것도 여기서 지킨다.
 *
 * <p>등록 경로로 직접 부른다 — 판정이 {@code register} 안에 있어서, 그 호출이 사라지면 이 테스트가 함께
 * 깨진다. 판정만 따로 부르면 "판정은 맞는데 아무도 안 부른다"가 초록으로 지나간다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("관리자 입장 감사 — 특권을 쓴 흔적이 남는다")
class PrivilegedEntryAuditTest {

    private static final UUID ROOM = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String EMAIL = "admin@example.com";
    private static final String NICKNAME = "운영자닉";

    @Mock
    private ChatSessionRepository sessionRepository;

    private ChatSessionRegistry registry;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        registry = new ChatSessionRegistry(sessionRepository, new ChatPermissionPolicy());
        // lenient — 불변식 테스트는 등록까지 가지 않는다(세션을 만드는 데서 이미 거부된다).
        org.mockito.Mockito.lenient().when(sessionRepository.add(ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Mono.empty());
        logger = (Logger) LoggerFactory.getLogger(ChatSessionRegistry.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("⭐ 관리자 입장이 남는다 — 없으면 특권 사용에 답할 기록이 0이다")
    void adminEntryIsRecorded() {
        // when
        registry.register("s1", new ChatSession(ROOM, ADMIN, ChatRole.ADMIN, NICKNAME, EMAIL, false)).block();

        // then: sessionId까지 남아야 이 파일의 종료·드롭 로그와 이어 붙는다
        assertThat(messages())
                .as("관리자 입장이 어디에도 남지 않는다")
                .anySatisfy(message -> assertThat(message)
                        .contains("특권 뷰 입장")
                        .contains("s1")
                        .contains(ADMIN.toString())
                        .contains(ROOM.toString()));
    }

    @Test
    @DisplayName("⭐ 이메일·닉네임은 남기지 않는다 — 감사는 접근을 남기는 것이지 본 것을 복제하는 게 아니다")
    void doesNotCopyWhatTheAdminSaw() {
        // when
        registry.register("s1", new ChatSession(ROOM, ADMIN, ChatRole.ADMIN, NICKNAME, EMAIL, false)).block();

        // then: 실으면 로그가 그 자체로 개인정보 사본이 된다.
        // 빈 리스트에 allSatisfy는 항상 참이라, 줄이 남았다는 전제를 먼저 세운다 — 안 그러면 로그를
        // 통째로 지워도 이 테스트가 통과한다.
        assertThat(messages()).isNotEmpty();
        assertThat(messages()).allSatisfy(message ->
                assertThat(message).doesNotContain(EMAIL).doesNotContain(NICKNAME));
    }

    @Test
    @DisplayName("판매자·구매자 입장은 남기지 않는다 — 남기면 모든 입장이 감사 로그가 된다")
    void ordinaryEntriesAreNotRecorded() {
        // when
        registry.register("s1", new ChatSession(ROOM, UUID.randomUUID(), ChatRole.SELLER, NICKNAME, EMAIL, false)).block();
        registry.register("s2", new ChatSession(ROOM, UUID.randomUUID(), ChatRole.BUYER, NICKNAME, EMAIL, false)).block();

        // then
        assertThat(messages()).noneMatch(message -> message.contains("특권 뷰 입장"));
    }

    /**
     * 판정이 {@code role == ADMIN} 하나인 것은 <b>자기 방 관리자가 존재할 수 없기 때문</b>이다.
     *
     * <p>{@code ChatSession}이 {@code ADMIN + isRoomOwner} 조합을 거부하므로 "남의 방"이라는 조건이
     * 역할 조건에 이미 포함돼 있다. live는 그 조합의 토큰을 발급할 수 있고 막는 것은 chat 쪽이라,
     * 그 불변식이 풀리는 날 자기 방 관리자가 특권 사용으로 기록되기 시작한다.
     *
     * <p>여기서 재는 것은 "그 케이스에서 안 남는다"가 아니라 <b>왜 그 케이스를 만들 수 없는가</b>다.
     * 불변식이 바뀌면 이 테스트가 먼저 깨져서, 그때 감사 판정을 함께 보게 된다.
     */
    @Test
    @DisplayName("관리자는 방 주인이 될 수 없다 — 그래서 판정에 소유 조건이 따로 없다")
    void adminCannotBeARoomOwnerToday() {
        // when & then
        assertThatThrownBy(() -> new ChatSession(ROOM, ADMIN, ChatRole.ADMIN, NICKNAME, EMAIL, true))
                .as("세션 불변식이 바뀌었다 — 감사 판정에 소유 조건을 더할지 함께 확인할 것")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
