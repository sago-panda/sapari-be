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
 * 없다. 두 갈래가 <b>정반대</b>를 뜻하므로 한 수로 세면 안 된다: 쓰기는 "아무것도 남지 않았다",
 * 재조회는 전제가 {@code DuplicateKey} 라 문서가 <b>이미 있다</b>. 그래서 재조회는 번역하지 않고
 * 수만 센다 — 번역하면 이력에 있는 메시지를 유실로 보고한다.
 *
 * <p><b>수를 적지 않는다, 어휘를 적는다.</b> 한때 "열한 자리에서 단정한다" 가 적혀 있었는데 그 11 은
 * 갈래를 가르기 <b>전</b>에 센 값이고, 갈래를 준 커밋이 그 집합의 멤버를 고치면서 수를 다시 세지
 * 않았다. 다시 세려면
 * {@code 아무것도 남지 않았|영속되지 않았|저장이 안 됐|저장하지 못했} 로 전수 grep 하고, 그중
 * <b>갈래로 좁혀지지 않은</b> 단정만 센다 — 지표 표 둘과 이 어댑터의 주석은 {@code stage} 로
 * 좁혀져 빠진다. 수를 박아 두면 그 수를 바꾼 커밋이 그걸 다시 세지 않는 쪽으로 틀린다. 잃는 것(갈래별 사용자 문구)은
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

        // when & then: 번역하면 "아무것도 남지 않았다" 를 열한 자리에서 단정하게 되고, 이력에 있는
        // 메시지를 유실로 보고한다. 그래서 예외는 그대로 두고 **수만** 남긴다.
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
