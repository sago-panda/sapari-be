package com.sapari.chat.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.mock;

import java.nio.channels.InterruptedByTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;

import com.mongodb.MongoOperationTimeoutException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.ServerAddress;
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
 * {@code SendChatServiceTest} 가 그대로 통과했다). 번역이 끊기면 서비스는 분류를 못 하고
 * {@code chat.persist.timeout} 이 영원히 0 이 되는데, 그 상태가 "상한이 안 걸린다" 와 구분되지 않는다.
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
    private final ReactiveMongoTemplate mongoTemplate = mock(ReactiveMongoTemplate.class);
    private final ChatMessageMapper mapper = mock(ChatMessageMapper.class);

    private final ChatMessageRepositoryImpl repository =
            new ChatMessageRepositoryImpl(mongoRepository, mongoTemplate, mapper);

    @Test
    @DisplayName("⭐ CSOT 체인이 ChatPersistTimeoutException 으로 번역된다 — 원인 사슬은 보존한다")
    void aDriverTimeoutChainBecomesTheDomainException() {
        RuntimeException driverChain = new UncategorizedMongoDbException(
                "Operation exceeded the timeout limit",
                new MongoOperationTimeoutException("Operation exceeded the timeout limit",
                        new MongoSocketReadTimeoutException("Timeout while receiving message",
                                new ServerAddress(), new InterruptedByTimeoutException())));
        willReturn(Mono.error(driverChain)).given(mongoRepository).save(any());

        StepVerifier.create(repository.save(message()))
                .expectErrorSatisfies(error -> {
                    assertThat(error)
                            .as("번역이 끊기면 서비스가 분류를 못 하고 chat.persist.timeout 이 영원히 0 이다")
                            .isInstanceOf(ChatPersistTimeoutException.class);
                    // 원인을 버리면 로그에서 "어느 상한이 걸렸나" 가 사라진다 — 응답 문구는
                    // 에러코드가 들고 있고 진단은 사슬에 있다.
                    assertThat(error.getCause()).isSameAs(driverChain);
                })
                .verify(VERIFY_CEILING);
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
