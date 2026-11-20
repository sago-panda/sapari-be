package com.sapari.chat.infrastructure.persistence.repository;

import java.util.UUID;

import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

import reactor.core.publisher.Mono;

public interface ChatMessageMongoRepository extends ReactiveMongoRepository<ChatMessageDocument, String> {

    /**
     * DuplicateKey 시 기존 메시지 재조회용 — {@code (roomId, senderId, clientMsgId)} unique partial 인덱스와 동일 키.
     *
     * <p><b>왜 파생 메서드가 아니라 {@code @Query} 인가.</b> 키가 같다는 것만으로는 그 인덱스가
     * 쓰이지 않는다. 그 인덱스는 {@code partialFilterExpression {clientMsgId: {$type: "string"}}} 이고,
     * MongoDB 는 파셜 인덱스를 쓰려면 질의가 그 필터식(또는 그 <b>부분집합</b>)을 조건으로 담기를
     * 요구한다. {@code $type} 은 매뉴얼이 열거한 부분집합 매칭 사례에 없고, 플래너는 문자열 동등성에서
     * 그것을 추론하지 않는다 — 그래서 파생 메서드는 페이징 인덱스로 떨어지고 <b>그 방을 전부 훑는다</b>.
     *
     * <p>실측(일회용 {@code mongo:7.0}, 24,000 docs / 방당 2,000): 파생 메서드는
     * {@code IXSCAN{roomId,_id} · docsExamined 2000 · keysExamined 2000}, 아래 모양은
     * {@code IXSCAN{roomId,senderId,clientMsgId} · 1 · 1}. 비용이 방 길이에 비례하는 자리가
     * WS 전송 경로의 {@code concatMap} 안이고 전역 2초 상한 아래라, 가장 뜨거운 방에서 가장 비싸진다.
     *
     * <p>{@code $eq} 를 따로 적는 이유는 {@code $type} 과 한 문서에 공존시켜야 하기 때문이다 —
     * {@code {clientMsgId: ?2, clientMsgId: {$type:'string'}}} 은 같은 키가 두 번이라 뒤가 앞을 덮는다.
     */
    @Query("{ 'roomId': ?0, 'senderId': ?1, 'clientMsgId': { $eq: ?2, $type: 'string' } }")
    Mono<ChatMessageDocument> findByRoomIdAndSenderIdAndClientMsgId(UUID roomId, UUID senderId, String clientMsgId);
}
