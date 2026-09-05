package com.sapari.liveapp.controller.chat.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 강퇴 요청 본문 — <b>담기는 것이 둘뿐인 게 요점이다.</b>
 *
 * <p>강퇴자가 누구고 어떤 역할인지는 여기 없다. 인증된 주체에서 서버가 채운다. 이 record에 역할 필드를
 * 하나 두는 순간 구매자가 자기를 관리자라 적어 아무나 끊을 수 있으므로, 그 자리를 만들지 않는 것으로
 * 그 공격을 없앤다. 방 역시 경로에서 온다.
 *
 * <p>사유도 받지 않는다. 강퇴는 항상 특정 메시지에서 일어나고, 그 메시지가 곧 사유다 — 서버가
 * {@code messageId}로 원문을 읽어 증거로 박는다.
 */
public record KickUserRequest(
        @NotNull(message = "targetUserId는 필수입니다.")
        UUID targetUserId,

        @NotBlank(message = "messageId는 필수입니다.")
        String messageId
) {
}
