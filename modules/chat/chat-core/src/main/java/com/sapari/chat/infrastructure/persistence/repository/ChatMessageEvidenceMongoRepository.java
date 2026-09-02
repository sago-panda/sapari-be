package com.sapari.chat.infrastructure.persistence.repository;

import java.util.Optional;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.sapari.chat.domain.model.ChatMessageEvidence;
import com.sapari.chat.domain.repository.ChatMessageEvidenceRepository;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

import lombok.RequiredArgsConstructor;

/**
 * 강퇴 증거 메시지를 {@code chat_messages}에서 한 건 읽는 블로킹 어댑터.
 *
 * <p>같은 컬렉션을 리액티브로 읽는 {@link ChatMessageRepositoryImpl}과 나란히 있지만 템플릿이 다르다 —
 * 이쪽 호출자는 MVC라 {@code Mono}를 받으면 결국 {@code block()}을 하게 된다.
 *
 * <p>매퍼를 쓰지 않고 필요한 세 필드만 옮긴다. {@code ChatMessage} 전체로 올리면 발신자 이메일까지
 * 딸려 오는데, 강퇴 판정에는 쓰이지 않는 값이다. 읽지 않으면 흘릴 일도 없다.
 *
 * <p>스테레오타입을 붙이지 않는다 — 블로킹 어댑터는 그 스택을 가진 앱이 명시로 등록한다.
 */
@RequiredArgsConstructor
public class ChatMessageEvidenceMongoRepository implements ChatMessageEvidenceRepository {

    private final MongoTemplate mongoTemplate;

    @Override
    public Optional<ChatMessageEvidence> findEvidence(String messageId) {
        // 형식이 어긋난 id는 조회하지 않고 빈 값으로 답한다. 던져서 갈라 보여 주면 "형식은 맞다"는 사실이
        // 응답으로 새어, id를 더듬는 쪽에 탐색 신호를 준다. 호출자에게는 어느 쪽이든 결론이 같다.
        if (!ObjectId.isValid(messageId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongoTemplate.findById(messageId, ChatMessageDocument.class))
                .map(document -> new ChatMessageEvidence(
                        document.getRoomId(), document.getSenderId(), document.getOriginalMessage()));
    }
}
