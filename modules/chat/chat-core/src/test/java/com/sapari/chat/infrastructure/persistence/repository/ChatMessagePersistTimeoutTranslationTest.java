package com.sapari.chat.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.mock;

import java.nio.channels.InterruptedByTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.UncategorizedMongoDbException;

import com.mongodb.MongoOperationTimeoutException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.ServerAddress;
import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.PersistStage;
import com.sapari.chat.domain.exception.ChatPersistTimeoutException;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.infrastructure.persistence.mapper.ChatMessageMapper;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 드라이버의 상한 예외가 <b>이 층에서</b> 도메인 타입으로 번역된다.
 *
 * <p>왜 이 칸이 따로 있어야 하는가. 서비스 쪽 칸은 번역이 <b>끝난</b> 타입을 손으로 만들어 넣는다 —
 * 그래서 번역을 지워도 그 칸은 초록이다(실측: 어댑터의 {@code onErrorMap} 을 지우고 돌렸더니
 * {@code SendChatServiceTest} 가 그대로 통과했다). 번역이 끊기면 서비스는 분류를 못 한다.
 *
 * <p><b>갈래별 계수도 여기서 잰다</b>({@code PersistStage}). 쓰기와 중복복구 재조회를 가르는 수가
 * 드라이버 예외라 그 판정은 이 층에만 있다 — 응용 계층은 {@code ArchUnit} 으로 그 타입에 닿을 수
 * 없다. 두 갈래의 <b>확신 수준</b>이 달라 한 수로 세면 안 된다: 쓰기는 <b>결과를 알 수 없고</b>
 * (클라이언트 상한이 서버 쓰기를 취소하지 않는다 — 실측으로 예외와 함께 문서가 남은 경우가 있다),
 * 재조회는 전제가 {@code DuplicateKey} 라 문서가 <b>이미 있다</b>. 그래서 재조회는 번역하지 않고
 * 수만 센다 — 번역하면 "존재 확정" 을 "불명" 과 같은 타입으로 올린다.
 *
 * <p><b>수를 적지 않는다. 그리고 술어가 아니라 주체로 쓴다.</b> 이 자리가 쓸기 지시를 보관하는데,
 * 한때 그 지시가 <b>두 군데 틀려서</b> 같은 결함이 세 번 샜다(SPR-180).
 *
 * <ol>
 *   <li><b>패턴이 술어의 한 표기만 잡았다.</b> {@code 아무것도 남지} 로 쓸었고
 *       {@code PersistStage.WRITE} javadoc 은 {@code 아무 <b>데도</b> 남지} 라 0건으로 나왔다 —
 *       하필 런북이 당직자를 보내는 태그 축 enum 자신이다.</li>
 *   <li><b>필터가 가리는 장치였다.</b> "갈래로 좁혀지지 않은 단정만 센다" 가 그 지시였는데,
 *       정정 뒤에는 <b>갈래로 좁혀져 있다는 것이 바로 거짓인 이유</b>다({@code stage=write} 가
 *       "안 남았다" 를 뜻하지 않으므로). 정정 전에 옳던 필터가 정정 후에 두 면을 숨겼다.</li>
 * </ol>
 *
 * <p>그래서 쓸 때는 <b>주체</b>로 쓴다 — {@code stage=write|PersistStage.WRITE|PERSIST_TIMEOUT} 로
 * 전수 grep 하고 <b>적중 전부를 읽는다</b>(세지 않는다). 수를 적으면 그 수를 바꾼 커밋이 다시 세지
 * 않는 쪽으로 틀리고, 실제로 "넷" 과 "열, 전부 고쳤다" 가 연달아 짧았다.
 * 잃는 것(갈래별 사용자 문구)은
 * {@code ChatMessageRepositoryImpl.findByRoomIdAndSenderIdAndClientMsgId} 에 적어 두었다.
 *
 * <p>체인을 그대로 조립해 넣는 이유도 같다. CSOT 가 자를 때 오는 것은 전용 타입 하나가 아니라
 * 네 겹이고(실측, 드라이버 5.6.5), 판정 지점이 <b>깊이 1</b> 이다 —
 * {@code NestedExceptionUtils.getMostSpecificCause} 는 깊이 3 의
 * {@code InterruptedByTimeoutException} 을 주므로 그걸로는 못 가른다.
 */
@DisplayName("저장 상한 번역 — 드라이버 예외가 도메인 타입으로 올라온다")
class ChatMessagePersistTimeoutTranslationTest {

    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    private final ChatMessageMongoRepository mongoRepository = mock(ChatMessageMongoRepository.class);
    private final ChatMessageMapper mapper = mock(ChatMessageMapper.class);
    private final ChatMetrics metrics = mock(ChatMetrics.class);

    private final ChatMessageRepositoryImpl repository =
            new ChatMessageRepositoryImpl(mongoRepository, mapper, metrics);

    @Test
    @DisplayName("⭐ CSOT 체인이 ChatPersistTimeoutException 으로 번역된다 — 원인 사슬은 보존한다")
    void aDriverTimeoutChainBecomesTheDomainException() {
        RuntimeException driverChain = timeoutChain();
        willReturn(Mono.error(driverChain)).given(mongoRepository).save(any());

        StepVerifier.create(repository.save(message()))
                .expectErrorSatisfies(error -> {
                    assertThat(error)
                            // 수는 번역과 **다른 배선**에 실려 있다(doOnError). 그래서 번역이
                            // 끊겼을 때 잃는 것은 지표가 아니라 **발신자에게 가는 에러코드**다 —
                            // CHAT-012(503) 대신 번역되지 않은 500 이 나간다.
                            .as("번역이 끊기면 발신자가 CHAT-012 대신 번역되지 않은 500 을 받는다")
                            .isInstanceOf(ChatPersistTimeoutException.class);
                    // 원인을 버리면 로그에서 "어느 상한이 걸렸나" 가 사라진다 — 응답 문구는
                    // 에러코드가 들고 있고 진단은 사슬에 있다.
                    assertThat(error.getCause()).isSameAs(driverChain);
                })
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("⭐ 쓰기 상한은 stage=write 로 센다 — 갈래 없이 세면 두 사건이 한 수에 섞인다")
    void aWriteTimeoutIsCountedUnderTheWriteStage() {
        willReturn(Mono.error(timeoutChain())).given(mongoRepository).save(any());

        StepVerifier.create(repository.save(message()))
                .expectError(ChatPersistTimeoutException.class)
                .verify(VERIFY_CEILING);

        then(metrics).should().persistTimedOut(PersistStage.WRITE);
    }

    @Test
    @DisplayName("⭐ 중복복구 재조회 상한은 stage=duplicate_recovery_read 로 세고 번역하지 않는다")
    void aRecoveryReadTimeoutIsCountedWithoutBeingTranslated() {
        // given: 이 갈래의 전제는 DuplicateKey 라 그 문서가 **이미 있다** — 끊긴 것은 되읽는 쪽이다
        RuntimeException driverChain = timeoutChain();
        willReturn(Mono.error(driverChain)).given(mongoRepository)
                .findByRoomIdAndSenderIdAndClientMsgId(any(), any(), any());

        // when & then: 번역하면 "존재 확정" 인 이 갈래가 "결과 불명" 과 같은 타입으로 올라간다.
        // 그래서 예외는 그대로 두고 **수만** 남긴다.
        StepVerifier.create(repository
                        .findByRoomIdAndSenderIdAndClientMsgId(UUID.randomUUID(), UUID.randomUUID(), "c1"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .as("재조회를 번역하면 저장 성공한 메시지를 유실로 보고한다")
                        .isSameAs(driverChain))
                .verify(VERIFY_CEILING);

        then(metrics).should().persistTimedOut(PersistStage.DUPLICATE_RECOVERY_READ);
    }

    @Test
    @DisplayName("⭐ 상한이 아닌 저장 실패는 번역하지 않는다 — 전부 상한으로 보고하면 그 지표가 거짓이 된다")
    void anUnrelatedFailureIsNotTranslated() {
        RuntimeException unrelated = new IllegalStateException("무관한 실패");
        willReturn(Mono.error(unrelated)).given(mongoRepository).save(any());

        StepVerifier.create(repository.save(message()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .as("상한이 아닌 것까지 번역하면 chat.persist.timeout 이 저장 실패 전체를 센다")
                        .isSameAs(unrelated))
                .verify(VERIFY_CEILING);
    }

    /** CSOT 가 자를 때 실제로 오는 네 겹(실측, 드라이버 5.6.5). 판정 지점은 깊이 1 이다. */
    private RuntimeException timeoutChain() {
        return new UncategorizedMongoDbException("Operation exceeded the timeout limit",
                new MongoOperationTimeoutException("Operation exceeded the timeout limit",
                        new MongoSocketReadTimeoutException("Timeout while receiving message",
                                new ServerAddress(), new InterruptedByTimeoutException())));
    }

    private ChatMessage message() {
        return ChatMessage.builder()
                .roomId(UUID.randomUUID())
                .senderId(UUID.randomUUID())
                .senderNickname("닉")
                .senderEmail("e@example.com")
                .senderRole(ChatRole.BUYER)
                .type(new ChatMessageType.Normal())
                .originalMessage("안녕")
                .displayMessage("안녕")
                .clientMsgId("c1")
                .createdAt(Instant.parse("2026-06-18T00:00:00Z"))
                .build();
    }
}
