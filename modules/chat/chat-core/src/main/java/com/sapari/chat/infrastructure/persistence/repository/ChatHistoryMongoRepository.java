package com.sapari.chat.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.repository.ChatHistoryRepository;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;
import com.sapari.chat.infrastructure.persistence.mapper.ChatMessageMapper;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatHistoryRepository} 블로킹 구현 — {@code chat_messages} 역순 읽기.
 *
 * <p>스테레오타입을 붙이지 않는다. chat-core 는 리액티브 앱과 블로킹 앱이 함께 의존하는데 스캔되는
 * 순간 양쪽에 빈이 만들어지려 하고, 동기 Mongo 템플릿이 없는 streaming-app 에서는 그 시도가 곧 부팅
 * 실패다. 블로킹 어댑터는 그 스택을 실제로 가진 앱이 {@code @Bean} 으로 등록한다(증거 조회와 동일).
 *
 * <p><b>정렬 키가 {@code _id} 다.</b> {@code createdAt} 이 아닌 이유는 커서가 {@code ObjectId} 이기
 * 때문이고, 같은 밀리초에 들어온 메시지들 사이에서도 전순서가 보장돼야 커서 페이징이 건너뛰거나
 * 겹치지 않는다. VOD 윈도우는 시각으로 <b>고르고</b> {@code _id} 로 <b>정렬한다</b> — 두 모드가 같은
 * 순서를 쓰므로 프론트가 모드를 바꿔도 목록의 순서 규칙이 바뀌지 않는다.
 */
@RequiredArgsConstructor
public class ChatHistoryMongoRepository implements ChatHistoryRepository {

    /** 역순 페이징 인덱스가 {@code (roomId, _id)} 라 그 순서를 그대로 쓴다. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "_id");

    private final MongoTemplate mongoTemplate;

    /**
     * 문서 → 도메인 변환.
     *
     * <p>리액티브 쪽과 <b>같은 매퍼를 주입받는다</b>. 여기서 손수 필드를 옮기면 같은 문서에 변환이
     * 둘이 되고, 도메인 record 에 필드가 하나 늘 때 한쪽만 따라간다 — 그 매퍼는
     * {@code unmappedTargetPolicy = ERROR} 라 빌드가 막아 주는데, 손수 복사는 아무도 묻지 않는다.
     */
    private final ChatMessageMapper mapper;

    @Override
    public List<ChatMessage> findBefore(UUID roomId, String beforeId, int size,
            Collection<UUID> excludedSenderIds) {

        if (size <= 0) {
            // Mongo 의 limit(0) 은 "무제한" 이다 — 0 이 흘러들면 방 전체를 긁어온다. 계약을 어긴
            // 입력이므로 조용히 고치지 않고 시끄럽게 막는다.
            throw new IllegalArgumentException("size는 1 이상이어야 한다: " + size);
        }
        Criteria criteria = roomScope(roomId, excludedSenderIds);
        if (beforeId != null) {
            if (!ObjectId.isValid(beforeId)) {
                // 여기까지 오면 호출자가 게이트를 거치지 않은 것이다 — 정상 경로에서는
                // GetChatHistoryService 가 형식을 먼저 보고 400 으로 거부한다. 이 어댑터는 그 뒤의
                // 방어라서, 던지는 대신 빈 페이지로 답해 호출자별 처리를 강요하지 않는다.
                return List.of();
            }
            criteria = criteria.and("_id").lt(new ObjectId(beforeId));
        }
        return read(Query.query(criteria).with(NEWEST_FIRST).limit(size));
    }

    @Override
    public List<ChatMessage> findWindow(UUID roomId, Instant from, Instant to, int limit,
            Collection<UUID> excludedSenderIds) {

        if (limit <= 0) {
            throw new IllegalArgumentException("limit은 1 이상이어야 한다: " + limit);
        }
        if (from.isAfter(to)) {
            // 호출자가 구간을 뒤집어 넘기면 Mongo 는 조용히 빈 결과를 준다 — 빈 이력과 구분되지 않는다.
            throw new IllegalArgumentException("from이 to보다 늦다: from=" + from + " to=" + to);
        }
        Criteria criteria = roomScope(roomId, excludedSenderIds)
                .and("createdAt").gte(from).lte(to);
        return read(Query.query(criteria).with(NEWEST_FIRST).limit(limit));
    }

    /**
     * 방 조건 + 강퇴자 제외.
     *
     * <p><b>제외가 질의 안에 있는 것이 계약이다.</b> 읽어 온 뒤 걸러내면 이력 응답에
     * {@code hasNext} 가 없어 프론트가 {@code messages.length === size} 로 다음 페이지를 판단하는
     * 계약이 깨진다 — 아직 남았는데도 길이가 모자라 <b>스크롤이 조용히 끝난다</b>.
     *
     * <p>빈 집합에 {@code $nin} 을 걸지 않는다. 동작은 같지만 질의에 의미 없는 절이 붙어
     * 계획이 달라질 수 있고, 강퇴가 없는 방이 대다수다.
     */
    private static Criteria roomScope(UUID roomId, Collection<UUID> excludedSenderIds) {
        Criteria criteria = Criteria.where("roomId").is(roomId);
        if (excludedSenderIds != null && !excludedSenderIds.isEmpty()) {
            criteria = criteria.and("senderId").nin(excludedSenderIds);
        }
        return criteria;
    }

    private List<ChatMessage> read(Query query) {
        return mongoTemplate.find(query, ChatMessageDocument.class).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
