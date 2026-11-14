package com.sapari.chat.infrastructure.persistence.document;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.mongodb.core.mapping.Document;

import com.sapari.storage.mongo.entity.BaseDocument;

import lombok.Getter;

/**
 * chat_messages 컬렉션 매핑. SYSTEM 메시지는 미영속이라 type은 NORMAL|NOTICE만 들어온다.
 * 인덱스는 전부 {@code ChatMongoConfig}에서 프로그램으로 생성한다
 * (clientMsgId unique partial($type:string)은 애너테이션으로 표현 불가 — 인덱스 정의를 한곳에 일원화).
 *
 * <p>발신자 이메일은 필드가 없다 — 저장하지 않는다. 메시지는 TTL 2년 동안 남고 회원 탈퇴가 그 수명을
 * 줄이지 않으므로, 저장하면 탈퇴한 회원의 이메일이 2년간 남는다. 이 필드를 없애기 전에 쓰인 문서의
 * {@code senderEmail} 키는 읽을 때 무시되고, 지우는 명령은 {@code infra/AGENTS.md} 에 있다.
 */
@Getter
@Document("chat_messages")
public class ChatMessageDocument extends BaseDocument {

    private final UUID roomId;
    private final UUID senderId;
    private final String senderNickname;
    private final String senderRole;      // BUYER | SELLER | ADMIN
    private final String type;            // NORMAL | NOTICE
    private final String originalMessage; // 마스킹 전 원문 (방 주인 토글·증거)
    private final String displayMessage;  // 마스킹 적용본
    private final String clientMsgId;     // nullable — 존재 시 (roomId, senderId, clientMsgId) unique

    @PersistenceCreator
    public ChatMessageDocument(String id, UUID roomId, UUID senderId, String senderNickname,
                               String senderRole, String type,
                               String originalMessage, String displayMessage, String clientMsgId,
                               Instant createdAt) {
        super(id, createdAt);
        this.roomId = roomId;
        this.senderId = senderId;
        this.senderNickname = senderNickname;
        this.senderRole = senderRole;
        this.type = type;
        this.originalMessage = originalMessage;
        this.displayMessage = displayMessage;
        this.clientMsgId = clientMsgId;
    }
}
