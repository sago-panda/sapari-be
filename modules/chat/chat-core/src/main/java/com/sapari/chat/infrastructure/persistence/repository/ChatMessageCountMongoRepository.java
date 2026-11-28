package com.sapari.chat.infrastructure.persistence.repository;

import java.util.UUID;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.sapari.chat.domain.repository.ChatMessageCountRepository;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

import lombok.RequiredArgsConstructor;

/**
 * 채팅 수를 세 번의 개수 질의로 센다: (방 전체) − (공지) − (삭제된 일반 메시지).
 *
 * <p><b>왜 질의 하나로 안 세는가.</b> {@code {roomId, type: NORMAL, deletedAt: null}} 은 두 조건이 인덱스 밖이라
 * 방의 문서를 전부 읽는다. 실측(일회용 mongo:7.0, 방 1,000건 · 공지 10 · 삭제 20, {@code count} 명령의 explain):
 * 질의 하나는 docsExamined 1,000. 나눈 셋은 방 전체 0(COUNT_SCAN, 페이징 인덱스) · 공지 0(COUNT_SCAN, 공지 파셜
 * 인덱스) · 삭제된 일반 메시지 20(삭제 파셜 인덱스로 삭제 건만 읽는다)이고, 결과는 같은 970 이다.
 * {@code MongoTemplate.count} 가 실제로 보내는 것은 집계({@code $match} 로 시작)이고, 그 계획은
 * {@code ChatMessageQueryPlanTest} 가 프로파일러로 잰다.
 *
 * <p><b>순서.</b> 빼는 두 수를 먼저 세고 전체를 마지막에 센다. 세는 사이에 메시지가 늘거나 삭제 표시가 붙으면
 * 세 수는 커지기만 하므로, 전체를 마지막에 세면 결과가 0 아래로 내려가지 않는다(대신 그 사이 들어온 공지는
 * 전체에만 잡혀 그만큼 많게 셀 수 있다). 줄어드는 길은 TTL 만료(2년)뿐이다.
 *
 * <p>스테레오타입을 붙이지 않는다 — 블로킹 어댑터는 그 스택을 가진 앱이 명시로 등록한다.
 */
@RequiredArgsConstructor
public class ChatMessageCountMongoRepository implements ChatMessageCountRepository {

    private final MongoTemplate mongoTemplate;

    @Override
    public long countMessages(UUID roomId) {
        long notices = count(Criteria.where("roomId").is(roomId).and("type").is("NOTICE"));
        long deletedNormals = count(Criteria.where("roomId").is(roomId)
                .and(ChatMessageDocument.DELETED_AT).exists(true)
                .and("type").is("NORMAL"));
        long all = count(Criteria.where("roomId").is(roomId));
        return all - notices - deletedNormals;
    }

    private long count(Criteria criteria) {
        return mongoTemplate.count(new Query(criteria), ChatMessageDocument.class);
    }
}
