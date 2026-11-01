package com.sapari.chat.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.domain.exception.ChatErrorCode;
import com.sapari.chat.domain.exception.ChatKickEvidenceMismatchException;

/**
 * 강퇴 기록이 <b>증거와 맞을 때만</b> 만들어지는지 고정한다.
 *
 * <p>강퇴 요청은 방·대상·메시지 id 셋을 들고 오는데 그 셋이 서로 맞는지는 아무도 보장하지 않는다 —
 * 전부 요청자가 정한 값이다. 셋을 맞춰 보는 곳이 {@code from} 하나뿐이라, 여기가 뚫리면 남의 방
 * 메시지 id 하나로 아무나 강퇴된다.
 */
@DisplayName("ChatKickLog.from — 증거와 맞지 않으면 만들어지지 않는다")
class ChatKickLogTest {

    private final UUID roomId = UUID.randomUUID();
    private final UUID targetUserId = UUID.randomUUID();
    private final UUID kickerId = UUID.randomUUID();
    private final Instant kickedAt = Instant.parse("2026-09-02T00:00:00Z");

    private ChatMessageEvidence evidence(UUID room, UUID sender) {
        return new ChatMessageEvidence(room, sender, ChatRole.BUYER, "문제된 원문");
    }

    private ChatKickLog from(ChatMessageEvidence evidence) {
        return ChatKickLog.from(evidence, roomId, targetUserId, kickerId, ChatRole.SELLER, kickedAt);
    }

    @Test
    @DisplayName("방·작성자가 모두 맞으면 원문이 그대로 증거로 박힌다")
    void buildsLogFromMatchingEvidence() {
        // when
        ChatKickLog log = from(evidence(roomId, targetUserId));

        // then: 마스킹본이 아니라 원문이어야 한다 — 무엇 때문에 끊었는지가 증거의 전부다
        assertThat(log.triggeringMessage()).isEqualTo("문제된 원문");
        assertThat(log.roomId()).isEqualTo(roomId);
        assertThat(log.targetUserId()).isEqualTo(targetUserId);
        assertThat(log.kickedAt()).isEqualTo(kickedAt);
    }

    @Test
    @DisplayName("다른 방 메시지를 증거로 내밀면 거부 — 남의 방 id 하나로 강퇴되지 않는다")
    void rejectsEvidenceFromAnotherRoom() {
        // given: 형식은 멀쩡하고 작성자도 대상과 같지만 방이 다르다
        ChatMessageEvidence otherRoom = evidence(UUID.randomUUID(), targetUserId);

        // when & then
        assertThatThrownBy(() -> from(otherRoom))
                .isInstanceOf(ChatKickEvidenceMismatchException.class)
                .hasMessageContaining("다른 방의 것이다");
    }

    @Test
    @DisplayName("다른 사람이 쓴 메시지를 증거로 내밀면 거부 — 엉뚱한 사람이 끊기지 않는다")
    void rejectsEvidenceWrittenBySomeoneElse() {
        // given: 방은 맞지만 그 메시지를 쓴 건 강퇴 대상이 아니다
        ChatMessageEvidence otherSender = evidence(roomId, UUID.randomUUID());

        // when & then
        assertThatThrownBy(() -> from(otherSender))
                .isInstanceOf(ChatKickEvidenceMismatchException.class)
                .hasMessageContaining("작성자가 강퇴 대상이 아니다");
    }

    @Test
    @DisplayName("증거가 아예 없으면 거부 — 빈 값으로 채워 진행하지 않는다")
    void rejectsMissingEvidence() {
        // when & then: placeholder로 채우면 그 행이 누적 강퇴를 올려 밴까지 밀어 올린다
        assertThatThrownBy(() -> from(null))
                .isInstanceOf(ChatKickEvidenceMismatchException.class)
                .hasMessageContaining("증거 메시지가 없다");
    }

    @Test
    @DisplayName("거부는 4xx다 — 요청자가 정한 값이 어긋난 것이지 서버 오류가 아니다")
    void mismatchIsARejectionNotAServerError() {
        // when & then: 5xx로 두면 전역 핸들러가 요청마다 풀 스택을 ERROR로 쌓는다
        assertThatThrownBy(() -> from(evidence(UUID.randomUUID(), targetUserId)))
                .isInstanceOf(ChatKickEvidenceMismatchException.class)
                .extracting(e -> ((ChatKickEvidenceMismatchException) e).getErrorCode().getStatus())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("세 갈래 모두 응답 문구가 같다 — 어디까지 맞았는지 알려주면 id 탐색 통로가 된다")
    void allThreeRejectionsLookIdenticalToTheClient() {
        // given: 없음 / 다른 방 / 다른 작성자
        List<ChatMessageEvidence> mismatches = new ArrayList<>();
        mismatches.add(null);
        mismatches.add(evidence(UUID.randomUUID(), targetUserId));
        mismatches.add(evidence(roomId, UUID.randomUUID()));

        // when: 클라이언트가 보는 문구만 모은다(로그용 debugMessage가 아니라 에러코드의 문구)
        Set<String> clientFacing = new HashSet<>();
        for (ChatMessageEvidence e : mismatches) {
            try {
                from(e);
            } catch (ChatKickEvidenceMismatchException ex) {
                clientFacing.add(ex.getErrorCode().getMessage());
            }
        }

        // then
        assertThat(clientFacing).containsExactly(ChatErrorCode.KICK_EVIDENCE_MISMATCH.getMessage());
    }

    @Test
    @DisplayName("강퇴 주체가 SELLER·ADMIN이 아니면 거부 — DB CHECK와 같은 선을 도메인이 먼저 긋는다")
    void rejectsKickerWhoCannotKick() {
        // when & then
        assertThatThrownBy(() -> ChatKickLog.from(evidence(roomId, targetUserId), roomId, targetUserId,
                kickerId, ChatRole.BUYER, kickedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SELLER·ADMIN");
    }
}
