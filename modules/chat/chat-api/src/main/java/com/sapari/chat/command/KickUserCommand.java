package com.sapari.chat.command;

import java.util.UUID;

/**
 * 강퇴 명령 (live-app REST).
 *
 * <p><b>요청 본문에서 오는 값은 {@code targetUserId}와 {@code messageId}뿐이다.</b> 나머지 둘은 서버가
 * 채운다 — {@code roomId}는 경로에서, {@code kickerId}와 {@code kickerRole}은 <b>인증된 주체</b>에서 온다.
 * 그래서 클라이언트에게는 역할을 적어 보낼 자리가 아예 없다. 요청 DTO에 그 필드를 두는 순간 구매자가
 * 자기를 ADMIN이라 적어 아무나 강퇴할 수 있으므로, 그 자리를 만들지 않는 것이 이 계약의 핵심이다.
 *
 * <p>방 주인 여부는 담지 않는다 — 서비스가 방을 로드해 {@code sellerId}와 대조한다.
 *
 * <p><b>본문이 아니라 {@code messageId}를 받는다.</b> 강퇴 증거로 남길 원문은 서버가 그 id로 직접 읽어
 * 박는다 — 클라이언트가 본문을 실어 보내면 무엇 때문에 끊었는지를 강퇴한 쪽이 지어낼 수 있고, 그러면
 * 증거가 증거가 아니게 된다. 설계 문서는 커맨드에 {@code triggeringMessage}(원문)를 두라고 적었지만,
 * 그 목적이 "분쟁 대비 self-contained 증거"라 조작 가능한 경로로는 목적을 이루지 못한다.
 *
 * @param kickerRole 인증 주체의 역할(플랫폼 역할 이름). <b>요청 본문에서 받지 말 것.</b>
 * @param messageId  강퇴 사유가 된 메시지의 서버 id(Mongo ObjectId). 형식 검증은 조회 어댑터의 몫이라
 *                  여기서는 비어 있지 않은지만 본다 — 계약(chat-api)이 저장소 타입을 알면 안 된다.
 */
public record KickUserCommand(
        UUID roomId,
        UUID kickerId,
        String kickerRole,
        UUID targetUserId,
        String messageId
) {
    public KickUserCommand {
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        if (kickerId == null) {
            throw new IllegalArgumentException("kickerId는 필수입니다.");
        }
        if (kickerRole == null || kickerRole.isBlank()) {
            throw new IllegalArgumentException("kickerRole은 필수입니다 — 인증된 주체에서 채웁니다.");
        }
        if (targetUserId == null) {
            throw new IllegalArgumentException("targetUserId는 필수입니다.");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId는 필수입니다 — 증거 없는 강퇴는 받지 않는다.");
        }
    }
}
