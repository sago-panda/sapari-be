package com.sapari.chat.domain.rule;

import java.util.UUID;

import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;

/**
 * 채팅 전송·강퇴 권한을 판정하는 순수 정책. 상태·외부 I/O가 없어 단위 테스트만으로 전부 검증된다.
 *
 * <p>권한은 <b>계정 role</b>과 <b>방 소유(isRoomOwner)</b> 두 축으로 갈린다 — SELLER 계정도 남의
 * 라이브에 시청자로 들어올 수 있어, role만으로 게이팅하면 타 방 공지 작성·구매자 이메일 누출(PII)을 허용한다.
 *
 * <p>가드(boolean)만 반환하고 예외는 던지지 않는다. 거부({@code false}) 시
 * {@code ChatPermissionDeniedException}을 던지는 책임은 호출 서비스에 있다(가드와 전이 분리).
 */
public final class ChatPermissionPolicy {

    /**
     * 전송 가능 여부.
     * <ul>
     *   <li>NORMAL: 회원(BUYER·SELLER·ADMIN) 허용, 소유 무관. GUEST 불가.
     *   <li>NOTICE: ADMIN 또는 자기 방 SELLER(isRoomOwner)만. 비-SELLER는 isRoomOwner가 참이어도 불가(방어).
     *   <li>SYSTEM: 사용자 전송 불가 — 서버 내부 생성 신호이므로 전 역할 거부(버그 방어).
     * </ul>
     */
    public boolean canSend(ChatRole role, boolean isRoomOwner, ChatMessageType type) {
        return switch (type) {
            case ChatMessageType.Normal n -> isMember(role);
            case ChatMessageType.Notice n -> role == ChatRole.ADMIN
                    || (role == ChatRole.SELLER && isRoomOwner);
            case ChatMessageType.System s -> false;
        };
    }

    /**
     * 강퇴 가능 여부.
     * <ul>
     *   <li>자기 자신은 강퇴 불가(역할·소유 무관) — 권한 규칙이 바뀌어도 self-kick은 막히도록 선차단.
     *   <li>ADMIN: 모든 방에서 ADMIN을 제외한 모든 역할 강퇴.
     *   <li>SELLER: 자기 방(kickerId == roomOwnerId)에서 ADMIN을 제외한 모든 참가자 강퇴 — 방문 SELLER도 내 방에선 시청자(isRoomOwner=false)이므로 강퇴 대상. ADMIN만 보호(플랫폼 운영자 ≥ 방주인).
     *   <li>그 외(BUYER·GUEST): 권한 없음.
     * </ul>
     * 대상이 비회원(GUEST)·탈퇴·없는 UUID인 경우는 호출 서비스가 조회 실패로 먼저 거부하므로
     * 이 정책에는 회원 targetRole만 도달한다.
     */
    public boolean canKick(ChatRole kickerRole, UUID kickerId, UUID roomOwnerId,
                           ChatRole targetRole, UUID targetUserId) {
        if (kickerId.equals(targetUserId)) {
            return false;
        }
        return switch (kickerRole) {
            case ADMIN -> targetRole != ChatRole.ADMIN;
            case SELLER -> kickerId.equals(roomOwnerId) && targetRole != ChatRole.ADMIN;
            default -> false;
        };
    }

    private boolean isMember(ChatRole role) {
        return role == ChatRole.BUYER || role == ChatRole.SELLER || role == ChatRole.ADMIN;
    }
}
