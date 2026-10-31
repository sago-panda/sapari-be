package com.sapari.chat.command;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이력 조회 입력 계약.
 *
 * <p>두 가지가 이 record 에 걸려 있다 — <b>조회 모드 배타성</b>과 <b>요청자 신원</b>.
 *
 * <p>요청자가 없을 수 있다는 것이 설계 확정 사항이다(비회원도 다시보기 채팅을 본다). 그래서
 * {@code requesterId} 는 필수가 아니다 — 비면 특권 뷰를 받지 못할 뿐이고, 그 판정은 서비스가 한다.
 * 반대로 필수로 두면 비회원 경로에서 컨트롤러가 채울 값이 없어 <b>스펙이 요구하는 공개 조회가
 * 불가능해진다</b>.
 *
 * <p>{@code requesterRole} 은 그 특권 판정에 쓴다. 역할 없이 소유만 보면 관리자가 이력에서만
 * 빠진다 — 실시간 팬아웃은 방 주인과 관리자에게 같은 것을 주기로 <b>제품 결정</b>이 돼 있어서,
 * 이력이 좁으면 같은 질문에 두 답이 생긴다.
 */
@DisplayName("GetChatHistoryCommand")
class GetChatHistoryCommandTest {

    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final UUID REQUESTER_ID = UUID.randomUUID();

    private GetChatHistoryCommand cursor(UUID requesterId, String requesterRole, int size) {
        return new GetChatHistoryCommand(ROOM_ID, requesterId, requesterRole, "68000000000000000000000a",
                null, size);
    }

    @Test
    @DisplayName("roomId 는 필수다")
    void roomIdIsRequired() {
        assertThatThrownBy(() -> new GetChatHistoryCommand(null, REQUESTER_ID, "BUYER", null, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roomId");
    }

    @Test
    @DisplayName("⭐ 요청자가 없어도 된다 — 비회원도 다시보기 채팅을 본다(설계 확정)")
    void anAnonymousRequesterIsAllowed() {
        assertThatCode(() -> cursor(null, null, 20)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⭐ 요청자 id 가 있으면 역할도 있어야 한다 — 한쪽만 있으면 특권 판정이 반쪽이 된다")
    void anIdentifiedRequesterNeedsARole() {
        assertThatThrownBy(() -> cursor(REQUESTER_ID, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requesterRole");
        assertThatThrownBy(() -> cursor(null, "ADMIN", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requesterId");
    }

    @Test
    @DisplayName("두 조회 모드는 배타다 — 한쪽을 조용히 무시하지 않고 즉시 거부한다")
    void theTwoModesAreExclusive() {
        assertThatThrownBy(() -> new GetChatHistoryCommand(ROOM_ID, REQUESTER_ID, "BUYER",
                "68000000000000000000000a", 1_000L, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("size 는 1~100 이다 — 커서 모드 페이징 크기")
    void sizeIsBounded() {
        assertThatThrownBy(() -> cursor(REQUESTER_ID, "BUYER", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cursor(REQUESTER_ID, "BUYER", 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> cursor(REQUESTER_ID, "BUYER", 1)).doesNotThrowAnyException();
        assertThatCode(() -> cursor(REQUESTER_ID, "BUYER", 100)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⭐ VOD 위치는 음수일 수 없다 — 음수면 창이 재생 시작 앞으로 가 조용히 빈 결과가 된다")
    void aNegativePlaybackPositionIsRejected() {
        assertThatThrownBy(() -> new GetChatHistoryCommand(ROOM_ID, REQUESTER_ID, "BUYER",
                null, -1L, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("playbackPositionMs");
    }
}
