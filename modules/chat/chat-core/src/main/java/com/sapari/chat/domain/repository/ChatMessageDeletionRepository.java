package com.sapari.chat.domain.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.sapari.chat.domain.model.ChatMessageDeletionTarget;
import com.sapari.chat.port.BlockingChat;

/**
 * 메시지 한 건에 삭제 표시를 다는 포트. <b>블로킹</b> — 호출자가 live-app(MVC)이다.
 *
 * <p>원문은 지우지 않는다. 표시만 달고, 이력 조회가 그 표시를 보고 질의에서 뺀다
 * ({@code ChatHistoryMongoRepository}).
 *
 * <p><b>두 메서드 모두 방으로 좁힌다.</b> 다른 방의 메시지는 없는 메시지와 같은 답(빈 값 · {@code false})이다 —
 * 방 주인 권한이 그 방 밖의 메시지에 닿지 않게 하는 것이 이 범위다. 형식이 어긋난 id 도 같은 답이다.
 */
@BlockingChat
public interface ChatMessageDeletionRepository {

    Optional<ChatMessageDeletionTarget> findInRoom(UUID roomId, String messageId);

    /**
     * 아직 표시가 없을 때만 단다. 이 호출이 표시를 달았으면 {@code true}, 이미 있었거나 그 방에 없으면 {@code false}.
     */
    boolean markDeleted(UUID roomId, String messageId, UUID deletedBy, Instant deletedAt);
}
