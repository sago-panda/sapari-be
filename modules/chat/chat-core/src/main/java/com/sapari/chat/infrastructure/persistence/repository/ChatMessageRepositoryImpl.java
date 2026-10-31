package com.sapari.chat.infrastructure.persistence.repository;

import java.util.UUID;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

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
                .map(mapper::toDomain);
    }

    @Override
    public Mono<ChatMessage> findByRoomIdAndSenderIdAndClientMsgId(UUID roomId, UUID senderId, String clientMsgId) {
        return mongoRepository.findByRoomIdAndSenderIdAndClientMsgId(roomId, senderId, clientMsgId)
                .map(mapper::toDomain);
    }

    /**
     * 커서 역방향 조회.
     *
     * <p>⚠️ <b>이 결과를 {@code CursorPage.of(rows, size, …)} 에 그대로 올리지 말 것.</b> 여기는
     * {@code limit(size)} 로 <b>정확히</b> {@code size} 개를 읽으므로 "하나 더 읽어 다음 페이지 유무를
     * 안다" 가 성립하지 않는다 — 그대로 올리면 {@code hasNext} 가 false 에 고정된다. 커서도 공용
     * {@code CursorCodec} 이 아니라 {@code ObjectId.isValid} 로 직접 검사하므로, 잘못된 커서가
     * 공용 경로의 400 이 아니라 여기서 {@code IllegalArgumentException} 으로 나가 전역 핸들러의
     * 마지막 그물(500)에 걸린다.
     *
     * <p>지금은 위반이 아니다 — 이 메서드에 운영 호출자가 없고 이력 조회가 미구현이다. VOD 이력이
     * 붙을 때 {@code size + 1} 을 읽고 커서를 공용 코덱으로 옮기는 것이 그 자리의 일이다
     * (근거: {@code common/AGENTS.md} 의 커서 페이징 규약).
     */
    @Override
    public Flux<ChatMessage> findByRoomIdBefore(UUID roomId, String beforeId, int size) {
        // Mongo limit(0) = 무제한 — size<=0이 들어오면 방 전체를 이벤트루프로 긁어오므로 loud failure로 차단
        if (size <= 0) {
            return Flux.error(new IllegalArgumentException("size는 1 이상이어야 한다: " + size));
        }
        Criteria criteria = Criteria.where("roomId").is(roomId);
        if (beforeId != null) {
            if (!ObjectId.isValid(beforeId)) {
                return Flux.error(new IllegalArgumentException("잘못된 ObjectId 형식: " + beforeId));
            }
            criteria = criteria.and("_id").lt(new ObjectId(beforeId));
        }
        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "_id"))
                .limit(size);
        return mongoTemplate.find(query, ChatMessageDocument.class)
                .map(mapper::toDomain);
    }
}
