package com.sapari.chat.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.domain.exception.ChatHistoryQueryInvalidException;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatHistoryRepository;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;
import com.sapari.chat.infrastructure.persistence.mapper.ChatMessageMapper;

/**
 * 이력 읽기 어댑터 — 실제 Mongo 로 커서 페이징·VOD 윈도우·강퇴자 제외를 본다.
 *
 * <p>가장 중요한 단언은 <b>제외가 질의 안에서 일어난다</b>는 것이다. 읽어 온 뒤 걸러내도 눈에 보이는
 * 결과는 같아 보이지만, 이력 응답에 {@code hasNext} 가 없어 프론트가
 * {@code messages.length === size} 로 다음 페이지를 판단한다(프론트 가이드 계약) — 뒤에서 걸러내면
 * 아직 남았는데도 길이가 모자라 <b>스크롤이 조용히 끝난다</b>. 그 실패는 "이력이 짧다" 와 구분되지 않는다.
 */
@SpringBootTest(properties =
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@Testcontainers
@DisplayName("ChatHistoryMongoRepository — 커서·윈도우·강퇴자 제외")
class ChatHistoryMongoRepositoryTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    /** {@code RedisChatBroadcaster}가 생성자에서 구독을 열어 컨테이너 없이는 컨텍스트가 못 뜬다. */
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    /** 블로킹 어댑터는 스테레오타입이 없다 — 쓰는 앱이 등록한다. 테스트도 같은 모양으로 등록한다. */
    @TestConfiguration
    static class BlockingAdapterConfig {

        @Bean
        ChatHistoryRepository chatHistoryRepository(MongoTemplate mongoTemplate, ChatMessageMapper mapper) {
            return new ChatHistoryMongoRepository(mongoTemplate, mapper);
        }
    }

    @Autowired
    private ChatHistoryRepository repository;

    @Autowired
    private ReactiveMongoTemplate reactiveMongoTemplate;

    /** 운영 쓰기 경로 — 읽기만 맞고 실제로는 못 읽는 상태를 막는다. */
    @Autowired
    private com.sapari.chat.domain.repository.ChatMessageRepository writeRepository;

    private final UUID roomId = UUID.randomUUID();
    private final UUID otherRoomId = UUID.randomUUID();
    private final UUID cleanSender = UUID.randomUUID();
    private final UUID kickedSender = UUID.randomUUID();

    private static final Instant BASE = Instant.parse("2026-06-18T00:00:00Z");

    @BeforeEach
    void setUp() {
        reactiveMongoTemplate.dropCollection(ChatMessageDocument.class).block(VERIFY_CEILING);
    }

    /**
     * 한 건씩 <b>운영 쓰기 경로로</b> 저장한다.
     *
     * <p>문서를 직접 만들지 않는 이유: 이 테스트가 재는 것이 "저장된 것을 읽을 수 있는가" 이고,
     * 문서를 손수 지으면 쓰기 쪽 매핑이 바뀌어도 이 테스트는 계속 통과한다 — 읽기만 맞고 실제로는
     * 못 읽는 상태가 된다.
     *
     * <p>한 건씩 순차로 저장한다 — {@code ObjectId} 가 생성 순으로 커지므로 저장 순서가 곧 정렬 순서다.
     */
    private String save(UUID room, UUID sender, String body, Instant createdAt) {
        return save(room, sender, body, createdAt, new ChatMessageType.Normal());
    }

    private String saveNotice(UUID room, UUID sender, String body) {
        return save(room, sender, body, BASE, new ChatMessageType.Notice());
    }

    private String save(UUID room, UUID sender, String body, Instant createdAt, ChatMessageType type) {
        ChatMessage saved = writeRepository.save(ChatMessage.builder()
                .roomId(room)
                .senderId(sender)
                .senderNickname("닉")
                .senderEmail("e@example.com")
                .senderRole(ChatRole.BUYER)
                .type(type)
                .originalMessage(body)
                .displayMessage(body)
                .clientMsgId(UUID.randomUUID().toString())
                .createdAt(createdAt)
                .build()).block(VERIFY_CEILING);
        return saved.id();
    }

    @Test
    @DisplayName("커서 없이 조회하면 최신순 size 건을 준다")
    void theNewestPageComesFirst() {
        // given
        for (int i = 0; i < 5; i++) {
            save(roomId, cleanSender, "m" + i, BASE.plusSeconds(i));
        }

        // when
        List<ChatMessage> page = repository.findBefore(roomId, null, 3, Set.of());

        // then
        assertThat(page).hasSize(3);
        assertThat(page).extracting(ChatMessage::displayMessage).containsExactly("m4", "m3", "m2");
    }

    @Test
    @DisplayName("⭐ 정확히 size 건을 읽는다 — 프론트가 길이로 다음 페이지를 판단한다")
    void exactlySizeRowsAreRead() {
        // given: 남은 것이 더 있다
        for (int i = 0; i < 10; i++) {
            save(roomId, cleanSender, "m" + i, BASE.plusSeconds(i));
        }

        // when & then: size + 1 을 읽으면 이 단언이 깨진다
        assertThat(repository.findBefore(roomId, null, 4, Set.of())).hasSize(4);
    }

    @Test
    @DisplayName("커서 이전만 준다 — 커서 자신은 포함되지 않는다")
    void theCursorIsExclusive() {
        // given
        save(roomId, cleanSender, "m0", BASE);
        String cursor = save(roomId, cleanSender, "m1", BASE.plusSeconds(1));
        save(roomId, cleanSender, "m2", BASE.plusSeconds(2));

        // when
        List<ChatMessage> page = repository.findBefore(roomId, cursor, 10, Set.of());

        // then
        assertThat(page).extracting(ChatMessage::displayMessage).containsExactly("m0");
    }

    @Test
    @DisplayName("⭐ 강퇴자 제외는 질의 안에서 — 뒤에서 걸러내면 스크롤이 조용히 끝난다")
    void theExclusionHappensInsideTheQuery() {
        // given: 강퇴자와 정상 발신자가 번갈아 쓴다. 뒤에서 걸러내면 3건만 남아 프론트가 "끝" 으로 읽는다.
        for (int i = 0; i < 6; i++) {
            save(roomId, i % 2 == 0 ? kickedSender : cleanSender, "m" + i, BASE.plusSeconds(i));
        }

        // when
        List<ChatMessage> page = repository.findBefore(roomId, null, 3, Set.of(kickedSender));

        // then: 질의가 제외했으므로 요청한 3건이 그대로 온다
        assertThat(page).as("뒤에서 걸러낸 것이다 — 아직 남았는데 길이가 모자라 스크롤이 끝난다")
                .hasSize(3);
        assertThat(page).extracting(ChatMessage::senderId).containsOnly(cleanSender);
    }

    @Test
    @DisplayName("다른 방 메시지는 섞이지 않는다")
    void otherRoomsAreNotMixedIn() {
        // given
        save(roomId, cleanSender, "mine", BASE);
        save(otherRoomId, cleanSender, "theirs", BASE.plusSeconds(1));

        // when & then
        assertThat(repository.findBefore(roomId, null, 10, Set.of()))
                .extracting(ChatMessage::displayMessage).containsExactly("mine");
    }

    @Test
    @DisplayName("⭐ 잘못된 커서는 400 이다 — 빈 페이지로 답하면 스크롤이 사유 없이 조용히 끝난다")
    void aMalformedCursorIsRejected() {
        // given
        save(roomId, cleanSender, "m0", BASE);

        // when & then: 응답에 hasNext 가 없어 프론트가 길이로 다음 페이지를 판단하므로, 빈 페이지는
        // "이력이 더 없다" 와 구분되지 않는다 — 커서를 잘못 만든 버그가 그 침묵에 묻힌다.
        assertThatThrownBy(() -> repository.findBefore(roomId, "not-an-object-id", 10, Set.of()))
                .isInstanceOf(ChatHistoryQueryInvalidException.class);
    }

    @Test
    @DisplayName("형식이 맞지만 없는 커서는 빈 페이지다 — 그건 400 이 아니다")
    void aWellFormedButAbsentCursorYieldsAnEmptyPage() {
        // given
        save(roomId, cleanSender, "m0", BASE);

        // when & then: 가장 작은 ObjectId 보다 앞은 아무것도 없다. 형식 오류와 갈려야 한다.
        assertThat(repository.findBefore(roomId, "000000000000000000000000", 10, Set.of())).isEmpty();
    }

    @Test
    @DisplayName("⭐ size 0 은 막는다 — Mongo 의 limit(0) 은 무제한이라 방 전체를 긁어온다")
    void aZeroSizeIsRejected() {
        assertThatThrownBy(() -> repository.findBefore(roomId, null, 0, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("⭐ 재접속 조회는 커서 뒤를 오래된 것부터 준다 — 커서 자신은 빠진다")
    void theAfterCursorReturnsLaterMessagesOldestFirst() {
        // given
        save(roomId, cleanSender, "m0", BASE);
        String cursor = save(roomId, cleanSender, "m1", BASE.plusSeconds(1));
        save(roomId, cleanSender, "m2", BASE.plusSeconds(2));
        save(roomId, cleanSender, "m3", BASE.plusSeconds(3));

        // when
        List<ChatMessage> page = repository.findAfter(roomId, cursor, 10, Set.of());

        // then
        assertThat(page).extracting(ChatMessage::displayMessage).containsExactly("m2", "m3");
    }

    @Test
    @DisplayName("⭐ 틈이 size 보다 크면 앞쪽부터 size 건 — 마지막 id 로 이어 받으면 빠짐도 겹침도 없다")
    void aGapLargerThanSizeIsWalkedForwardWithoutLossOrOverlap() {
        // given: 커서 뒤에 7건. size 3 이면 3 + 3 + 1 로 이어 받는다.
        String cursor = save(roomId, cleanSender, "start", BASE);
        for (int i = 0; i < 7; i++) {
            save(roomId, cleanSender, "m" + i, BASE.plusSeconds(i + 1));
        }

        // when
        List<String> received = new java.util.ArrayList<>();
        List<ChatMessage> page;
        do {
            page = repository.findAfter(roomId, cursor, 3, Set.of());
            page.forEach(message -> received.add(message.displayMessage()));
            if (!page.isEmpty()) {
                cursor = page.get(page.size() - 1).id();
            }
        } while (page.size() == 3);

        // then: 최신부터 주면 첫 페이지가 m4~m6 을 가져가고 m0~m3 은 이어 받을 커서 없이 빠진다
        assertThat(received).containsExactly("m0", "m1", "m2", "m3", "m4", "m5", "m6");
    }

    @Test
    @DisplayName("⭐ 재접속 조회도 강퇴자를 질의에서 제외하고 다른 방을 섞지 않는다")
    void theAfterCursorExcludesKickedSendersAndOtherRoomsInsideTheQuery() {
        // given
        String cursor = save(roomId, cleanSender, "start", BASE);
        for (int i = 0; i < 6; i++) {
            save(roomId, i % 2 == 0 ? kickedSender : cleanSender, "m" + i, BASE.plusSeconds(i + 1));
        }
        save(otherRoomId, cleanSender, "theirs", BASE.plusSeconds(10));

        // when
        List<ChatMessage> page = repository.findAfter(roomId, cursor, 3, Set.of(kickedSender));

        // then: 질의가 제외했으므로 요청한 3건이 그대로 온다
        assertThat(page).extracting(ChatMessage::displayMessage).containsExactly("m1", "m3", "m5");
    }

    @Test
    @DisplayName("⭐ 재접속 조회의 잘못된 커서도 400 이다 — 과거 방향과 같은 판정")
    void aMalformedAfterCursorIsRejected() {
        assertThatThrownBy(() -> repository.findAfter(roomId, "not-an-object-id", 10, Set.of()))
                .isInstanceOf(ChatHistoryQueryInvalidException.class);
    }

    @Test
    @DisplayName("재접속 조회는 커서와 size 1 이상이 필수다 — 없으면 방 전체를 긁는다")
    void theAfterCursorRequiresACursorAndAPositiveSize() {
        assertThatThrownBy(() -> repository.findAfter(roomId, null, 10, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findAfter(roomId, "68000000000000000000000a", 0, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("VOD 윈도우는 구간 안만 최신순으로 준다")
    void theWindowReturnsOnlyWhatIsInside() {
        // given
        save(roomId, cleanSender, "before", BASE);
        save(roomId, cleanSender, "inside1", BASE.plusSeconds(100));
        save(roomId, cleanSender, "inside2", BASE.plusSeconds(200));
        save(roomId, cleanSender, "after", BASE.plusSeconds(400));

        // when
        List<ChatMessage> window = repository.findWindow(
                roomId, BASE.plusSeconds(50), BASE.plusSeconds(300), 200, Set.of());

        // then
        assertThat(window).extracting(ChatMessage::displayMessage).containsExactly("inside2", "inside1");
    }

    @Test
    @DisplayName("윈도우도 강퇴자를 질의에서 제외한다")
    void theWindowExcludesKickedSenders() {
        // given
        save(roomId, kickedSender, "kicked", BASE.plusSeconds(10));
        save(roomId, cleanSender, "clean", BASE.plusSeconds(20));

        // when & then
        assertThat(repository.findWindow(roomId, BASE, BASE.plusSeconds(60), 200, Set.of(kickedSender)))
                .extracting(ChatMessage::displayMessage).containsExactly("clean");
    }

    @Test
    @DisplayName("⭐ 뒤집힌 구간은 막는다 — Mongo 는 조용히 빈 결과를 주고 그건 빈 이력과 구분되지 않는다")
    void anInvertedWindowIsRejected() {
        assertThatThrownBy(() -> repository.findWindow(
                roomId, BASE.plusSeconds(10), BASE, 200, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from이 to보다 늦다");
    }

    @Test
    @DisplayName("⭐ 공지 조회는 그 방의 가장 최근 공지를 준다 — 뒤에 쌓인 일반 메시지·다른 방 공지가 아니다")
    void theLatestNoticeOfTheRoomComesBack() {
        // given
        saveNotice(roomId, cleanSender, "옛 공지");
        saveNotice(roomId, cleanSender, "최근 공지");
        save(roomId, cleanSender, "일반", BASE.plusSeconds(10));
        saveNotice(otherRoomId, cleanSender, "다른 방 공지");

        // when & then
        assertThat(repository.findLatestNotice(roomId, Set.of()))
                .map(ChatMessage::displayMessage).contains("최근 공지");
    }

    @Test
    @DisplayName("⭐ 강퇴자의 공지는 건너뛰고 그 앞의 공지를 준다 — 읽은 뒤 거르면 빈 값이 된다")
    void aKickedSendersNoticeIsSkipped() {
        // given
        saveNotice(roomId, cleanSender, "남는 공지");
        saveNotice(roomId, kickedSender, "강퇴자 공지");

        // when & then
        assertThat(repository.findLatestNotice(roomId, Set.of(kickedSender)))
                .map(ChatMessage::displayMessage).contains("남는 공지");
    }

    @Test
    @DisplayName("공지가 없는 방은 빈 값이다")
    void aRoomWithoutNoticesIsEmpty() {
        save(roomId, cleanSender, "일반", BASE);

        assertThat(repository.findLatestNotice(roomId, Set.of())).isEmpty();
    }
}
