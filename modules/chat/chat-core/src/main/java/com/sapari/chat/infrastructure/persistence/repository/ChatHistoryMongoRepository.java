package com.sapari.chat.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.sapari.chat.domain.exception.ChatHistoryQueryInvalidException;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.repository.ChatHistoryRepository;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;
import com.sapari.chat.infrastructure.persistence.mapper.ChatMessageMapper;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatHistoryRepository} 블로킹 구현 — {@code chat_messages} 를 방 안에서 {@code _id} 순으로 읽는다(과거 방향 · 재접속 · VOD 창).
 *
 * <p>스테레오타입을 붙이지 않는다. chat-core 는 리액티브 앱과 블로킹 앱이 함께 의존하는데 스캔되는
 * 순간 양쪽에 빈이 만들어지려 하고, 동기 Mongo 템플릿이 없는 streaming-app 에서는 그 시도가 곧 부팅
 * 실패다. 블로킹 어댑터는 그 스택을 실제로 가진 앱이 {@code @Bean} 으로 등록한다(증거 조회와 동일).
 *
 * <p><b>정렬 키가 {@code _id} 다.</b> {@code createdAt} 이 아닌 이유는 커서가 {@code ObjectId} 이기
 * 때문이고, 같은 밀리초에 들어온 메시지들 사이에서도 전순서가 보장돼야 커서 페이징이 건너뛰거나
 * 겹치지 않는다. VOD 윈도우는 시각으로 <b>고르고</b> {@code _id} 로 <b>정렬한다</b> — 과거 방향 커서와
 * VOD 창은 같은 순서(최신부터)를 쓰므로 프론트가 그 둘 사이를 오가도 순서 규칙이 바뀌지 않는다.
 * ⚠️ 재접속 조회({@link #findAfter})만 <b>오래된 것부터</b>다 — 이유는 포트 javadoc.
 */
@RequiredArgsConstructor
public class ChatHistoryMongoRepository implements ChatHistoryRepository {

    /** 역순 페이징 인덱스가 {@code (roomId, _id)} 라 그 순서를 그대로 쓴다. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "_id");

    /** 재접속 조회는 같은 인덱스를 정방향으로 읽는다. */
    private static final Sort OLDEST_FIRST = Sort.by(Sort.Direction.ASC, "_id");

    /** 문서의 {@code type} 값. 매퍼가 쓰는 문자열과 같다({@code ChatMessageMapper}). */
    private static final String NOTICE_TYPE = "NOTICE";

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
            criteria = criteria.and("_id").lt(cursor(beforeId));
        }
        return read(Query.query(criteria).with(NEWEST_FIRST).limit(size));
    }

    /**
     * 페이징 인덱스 {@code (roomId, _id)} 를 정방향으로 읽어 정렬 단계가 없다. 실측(일회용 mongo:7.0,
     * 12만건 중 한 방 1만건, size 50): docsExamined 50. 강퇴자가 섞이면 걸러지는 만큼 더 읽는다 — 그 방
     * 메시지의 1/5 이 강퇴자일 때 62(= 50 ÷ 4/5).
     */
    @Override
    public List<ChatMessage> findAfter(UUID roomId, String afterId, int size,
            Collection<UUID> excludedSenderIds) {

        if (size <= 0) {
            throw new IllegalArgumentException("size는 1 이상이어야 한다: " + size);
        }
        if (afterId == null) {
            // 커서가 없으면 "처음부터" 가 되어 방 전체를 오래된 것부터 긁는다 — 재접속 조회의 뜻이 아니다.
            throw new IllegalArgumentException("afterId는 필수다");
        }
        Criteria criteria = roomScope(roomId, excludedSenderIds).and("_id").gt(cursor(afterId));
        return read(Query.query(criteria).with(OLDEST_FIRST).limit(size));
    }

    /**
     * 커서 문자열 → {@code ObjectId}. 두 방향 조회가 같은 판정을 쓴다.
     *
     * <p>형식 판정이 이 어댑터에 있는 것이 요점이다 — 커서가 ObjectId 라는 사실은 저장소의 지식이고,
     * 응용 서비스가 그걸 알면 저장소를 바꿀 때 서비스가 따라 바뀐다.
     *
     * <p>도메인 예외로 올리는 이유는 번역이 아니라 <b>경계</b>다 — 여기서 잡을 인프라 예외는 없고
     * ({@code ObjectId.isValid} 는 DB 를 치지 않는 순수 24자 16진수 검사다) 이건 입력 검증이다. 생
     * {@code IllegalArgumentException} 으로 올리면 전역 핸들러에 그 타입이 없어 500 이 된다.
     * ⚠️ 이 파일의 size·limit·from>to·afterId 필수 검사는 아직 생 {@code IllegalArgumentException} 이다 —
     * 지금은 명령 record 와 서비스의 분기가 앞에서 막아 도달하지 않지만, 일관성은 어긋나 있다.
     */
    private static ObjectId cursor(String id) {
        if (!ObjectId.isValid(id)) {
            throw new ChatHistoryQueryInvalidException("커서 형식이 올바르지 않습니다.");
        }
        return new ObjectId(id);
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
     * {@code type} 등치가 질의에 있어야 공지 전용 파셜 인덱스를 쓴다 — 파셜 인덱스는 질의가 그 필터를
     * 포함할 때만 후보가 된다. 없으면 페이징 인덱스로 방의 메시지를 최신부터 훑고, 공지가 없는 방이면
     * 방 전체를 읽는다.
     */
    @Override
    public Optional<ChatMessage> findLatestNotice(UUID roomId, Collection<UUID> excludedSenderIds) {
        Criteria criteria = roomScope(roomId, excludedSenderIds).and("type").is(NOTICE_TYPE);
        return read(Query.query(criteria).with(NEWEST_FIRST).limit(1)).stream().findFirst();
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
