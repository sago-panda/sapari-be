package com.sapari.chat.infrastructure.persistence.repository;

import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.PersistStage;
import com.sapari.chat.domain.exception.ChatPersistTimeoutException;
import com.sapari.chat.infrastructure.persistence.MongoOperationTimeout;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.repository.ChatMessageRepository;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;
import com.sapari.chat.infrastructure.persistence.mapper.ChatMessageMapper;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class ChatMessageRepositoryImpl implements ChatMessageRepository {

    private final ChatMessageMongoRepository mongoRepository;
    private final ChatMessageMapper mapper;

    /**
     * 상한이 끊은 갈래를 <b>이 층에서</b> 센다.
     *
     * <p>왜 서비스가 아닌가. 두 갈래를 가르는 수는 드라이버 예외이고 그것은 이 층에서만 보인다 —
     * 응용 계층은 {@code ArchUnit} 의 {@code application_must_not_depend_on_infrastructure} 로
     * 그 타입에 닿을 수 없다. 서비스에서 세려면 갈래마다 도메인 예외를 하나씩 만들어야 하고, 그러면
     * 와이어의 에러코드가 늘어난다 — 이 티켓은 <b>수만</b> 남기고 응답은 건드리지 않는다.
     *
     * <p>선례: {@code RedisRateLimiter} 도 어댑터에서 {@code ChatMetrics} 를 받는다.
     */
    private final ChatMetrics metrics;

    @Override
    public Mono<ChatMessage> save(ChatMessage message) {
        return mongoRepository.save(mapper.toDocument(message))
                .map(mapper::toDomain)
                // 연산 상한이 끊은 것을 도메인 타입으로 바꿔 올린다. 번역이 이 층인 이유가 둘이다 —
                // 루트 AGENTS.md 가 서비스의 SDK 직접 사용을 금지하고, ArchUnit 의
                // application_must_not_depend_on_infrastructure 가 그 반대 방향도 막는다.
                .doOnError(MongoOperationTimeout::matches, error -> metrics.persistTimedOut(PersistStage.WRITE))
                .onErrorMap(MongoOperationTimeout::matches, ChatPersistTimeoutException::new);
    }

    @Override
    public Mono<ChatMessage> findByRoomIdAndSenderIdAndClientMsgId(UUID roomId, UUID senderId, String clientMsgId) {
        return mongoRepository.findByRoomIdAndSenderIdAndClientMsgId(roomId, senderId, clientMsgId)
                .map(mapper::toDomain)
                .doOnError(MongoOperationTimeout::matches,
                        error -> metrics.persistTimedOut(PersistStage.DUPLICATE_RECOVERY_READ));
        // ⚠️ 이 재조회는 **같은 concatMap 안**이고 **같은 전역 상한**에 걸리는데도
        // ChatPersistTimeoutException 으로 번역하지 않는다. 한 번 번역했다가 되돌렸고 이유는 이렇다.
        //
        // 이 경로에 오는 전제가 DuplicateKey 다 — 그 (roomId, senderId, clientMsgId) 문서가
        // **이미 있다**는 뜻이고, 끊긴 것은 그걸 되읽는 쪽이다. 그런데 그 예외와
        // ChatErrorCode.PERSIST_TIMEOUT 은 "저장 결과를 알 수 없다" 를 뜻한다. 그런데 이 갈래는
        // 전제가 DuplicateKey 라 **존재가 확정**이다 — 확신 수준이 반대쪽이다. 그 둘을 같은 타입으로
        // 올리면 "존재 확정" 을 "불명" 으로 내려 보고하고, 사용자 문구도 이 갈래에서는 과하게
        // 약해진다(이미 있는 메시지를 "확인하지 못했다" 로 안내한다).
        //
        // 그래서 **수는 세고 타입은 그대로 둔다**(위 doOnError). 지표가 갈래를 들고 있으므로
        // chat.persist.timeout{stage=write} 가 "결과 미확인" 으로 남고 이쪽은 "존재 확정" 으로 남는다.
        //
        // 아직 없는 것은 **갈래별 사용자 문구**다. 이 갈래의 예외는 번역되지 않고 올라가 발신자에게는
        // WS ERROR(INTERNAL)로 간다 — 같은 clientMsgId·같은 본문으로 다시 보내면 이 재조회를 다시 타고
        // 복구된다. 갈래를 응답에서 가르려면 에러코드가 하나 더 필요하고, 그것은 와이어 계약 변경이다.
    }
}
