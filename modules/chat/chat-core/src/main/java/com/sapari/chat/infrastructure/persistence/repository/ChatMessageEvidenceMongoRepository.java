package com.sapari.chat.infrastructure.persistence.repository;

import java.util.Optional;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.sapari.chat.domain.model.ChatMessageEvidence;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatMessageEvidenceRepository;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

import lombok.RequiredArgsConstructor;

/**
 * 강퇴 증거 메시지를 {@code chat_messages}에서 한 건 읽는 블로킹 어댑터.
 *
 * <p>같은 컬렉션을 리액티브로 읽는 {@link ChatMessageRepositoryImpl}과 나란히 있지만 템플릿이 다르다 —
 * 이쪽 호출자는 MVC라 {@code Mono}를 받으면 결국 {@code block()}을 하게 된다.
 *
 * <p>매퍼를 쓰지 않고 필요한 세 필드만 도메인으로 옮긴다 — 강퇴 판정에 발신자 이메일은 쓰이지 않는다.
 * <b>다만 조회 자체는 문서 전체를 가져온다</b>({@code findById}에 프로젝션이 없다). 이메일은 이 메서드
 * 안에서만 존재하고 로그·응답 어디로도 나가지 않지만, "읽지 않는다"가 아니라 "내보내지 않는다"가
 * 정확한 표현이다. 조회 단계에서까지 걷어내려면 {@code Query}에 필드 프로젝션을 걸면 된다.
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
                        document.getRoomId(), document.getSenderId(),
                        senderRole(document), document.getOriginalMessage()));
    }

    /**
     * 저장된 역할 문자열을 도메인 역할로. 모르는 값은 <b>요청의 잘못이 아니라 저장 데이터의 잘못</b>이다.
     *
     * <p>이 값은 발신 시점에 live가 서명한 룸 토큰에서 와 그대로 박제된 것이라, 여기서 어긋났다면 역할
     * 열거가 바뀌었거나 문서가 손상된 것이다. {@code valueOf}가 그대로 터지면 "No enum constant ..."만
     * 남아 무엇이 깨졌는지 로그에서 읽히지 않으므로, 무엇을 읽다가 어긋났는지를 붙여 다시 던진다.
     * 상태코드는 그대로 500이고 그게 맞다 — 고칠 쪽은 요청자가 아니라 서버다.
     */
    private ChatRole senderRole(ChatMessageDocument document) {
        try {
            return ChatRole.valueOf(document.getSenderRole());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "증거 메시지의 역할 값을 해석할 수 없다 — messageId=" + document.getId(), e);
        }
    }
}
