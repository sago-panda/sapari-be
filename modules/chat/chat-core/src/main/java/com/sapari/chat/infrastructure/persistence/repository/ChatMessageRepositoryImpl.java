package com.sapari.chat.infrastructure.persistence.repository;

import java.util.UUID;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.exception.ChatPersistTimeoutException;
import com.sapari.chat.infrastructure.persistence.MongoOperationTimeout;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.repository.ChatMessageRepository;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;
import com.sapari.chat.infrastructure.persistence.mapper.ChatMessageMapper;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class ChatMessageRepositoryImpl implements ChatMessageRepository {

    private final ChatMessageMongoRepository mongoRepository;
    private final ReactiveMongoTemplate mongoTemplate;
    private final ChatMessageMapper mapper;

    @Override
    public Mono<ChatMessage> save(ChatMessage message) {
        return mongoRepository.save(mapper.toDocument(message))
                .map(mapper::toDomain)
                // 연산 상한이 끊은 것을 도메인 타입으로 바꿔 올린다. 번역이 이 층인 이유가 둘이다 —
                // 루트 AGENTS.md 가 서비스의 SDK 직접 사용을 금지하고, ArchUnit 의
                // application_must_not_depend_on_infrastructure 가 그 반대 방향도 막는다.
                .onErrorMap(MongoOperationTimeout::matches, ChatPersistTimeoutException::new);
    }

    @Override
    public Mono<ChatMessage> findByRoomIdAndSenderIdAndClientMsgId(UUID roomId, UUID senderId, String clientMsgId) {
        return mongoRepository.findByRoomIdAndSenderIdAndClientMsgId(roomId, senderId, clientMsgId)
                .map(mapper::toDomain);
        // ⚠️ 이 재조회는 **같은 concatMap 안**이고 **같은 전역 상한**에 걸리는데도
        // ChatPersistTimeoutException 으로 번역하지 않는다. 한 번 번역했다가 되돌렸고 이유는 이렇다.
        //
        // 이 경로에 오는 전제가 DuplicateKey 다 — 그 (roomId, senderId, clientMsgId) 문서가
        // **이미 있다**는 뜻이고, 끊긴 것은 그걸 되읽는 쪽이다. 그런데 그 예외와
        // chat.persist.timeout 은 "아무것도 남지 않았다 · 영속되지 않았다 · 저장하지 못했습니다" 를
        // 열한 자리에서 단정한다(전수). 그 둘을 한 수로 세면 **이력에 있는 메시지를 유실로 보고**하고,
        // 사용자 문구도 그 갈래에서 거짓이 된다 — modules/chat/AGENTS.md 의 ⚠️ 가
        // delivery.dropped 합산을 금지하는 이유("이력에 없는 것을 있다고 말한다")의 정확히 뒤집힌 꼴이다.
        //
        // 그래서 잃는 것을 적어 둔다: **이 갈래에서 상한이 발동하면 신호가 없다.** 발신자는
        // UncategorizedMongoDbException(500)을 받고 그 수는 어디에도 안 남는다. 세려면 축이 하나
        // 필요하다 — 이 저장소가 delivery.dropped{stage}·session.cut{reason} 으로 쓰는 그 모양으로
        // persistTimedOut 에 갈래(쓰기 / 중복복구 재조회)를 주면 쓰기 쪽 "아무것도 남지 않았다" 가
        // 참으로 남고 이쪽은 자기 문장을 갖는다. 사용자 문구도 갈래별로 달라야 하므로 에러코드가
        // 하나 더 필요하고, 그만큼은 이 티켓 밖이다.
    }
}
