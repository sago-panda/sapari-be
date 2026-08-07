package com.sapari.chat.view;

/**
 * 입장 성공 응답(ROOM_INFO). 연결 시 1회 전달된다.
 *
 * <p>{@code activeCount}(현재 고유 시청자 수)와 함께 <b>방 주인 여부 {@code isRoomOwner}</b>를 내려,
 * 프론트가 공지·강퇴·원문토글 버튼 노출 여부를 결정한다(방 주인일 때만 노출).
 * (role은 클라이언트가 자기 JWT claim으로 이미 알 수 있어 중복이라 싣지 않는다.)
 */
public record ChatSessionView(
        Long activeCount,   // 조회 실패 시 null(=알 수 없음)
        boolean isRoomOwner
) {
}
