package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.live.application.port.RoomTokenClaims;
import com.sapari.live.application.port.RoomTokenIssuer;
import com.sapari.live.command.EnterLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.exception.UnsupportedRoleException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.EnterLiveUseCase;
import com.sapari.live.view.EnterLiveView;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnterLiveService implements EnterLiveUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final RoomTokenIssuer roomTokenIssuer;

    @Override
    @Transactional(readOnly = true)
    public EnterLiveView enter(EnterLiveCommand command) {
        LiveRoom room = liveRoomRepository.findById(command.roomId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        // 라이브 진행 중일 때만 입장(=룸 토큰 발급) 가능 — 권위 평가는 live DB 기준.
        if (!room.canEnterLive()) {
            throw new InvalidLiveStateException(command.roomId().toString());
        }

        // 회원은 신원 기반 토큰, 게스트(미인증)는 에페메랄 GUEST 토큰 — 둘 다 채팅 입장은 가능(GUEST는 수신 전용).
        String roomToken = command.isAuthenticated()
                ? issueRoomToken(command, room)
                : issueGuestToken(command.roomId());

        log.info("live 입장. roomId={}, authenticated={}", command.roomId(), command.isAuthenticated());
        return new EnterLiveView(room.hlsUrl(), roomToken);
    }

    /**
     * 비로그인 게스트용 에페메랄 룸 토큰. 매 입장마다 새 임의 userId를 부여하고 role=GUEST, owner=false로 둔다
     * (nickname/email 없음). chat은 GUEST를 수신 전용으로 게이팅한다.
     */
    private String issueGuestToken(UUID roomId) {
        UUID guestId = UUID.randomUUID();
        RoomTokenClaims claims = new RoomTokenClaims(guestId, roomId, "GUEST", false, null, null);
        return roomTokenIssuer.issue(claims);
    }

    private String issueRoomToken(EnterLiveCommand command, LiveRoom room) {
        // 방주인(=방을 만든 판매자 본인)인지 권위 평가. chat의 PII·공지 게이트 근거가 된다.
        boolean owner = command.userId().equals(room.sellerId());

        RoomTokenClaims claims = new RoomTokenClaims(
                command.userId(),
                command.roomId(),
                toChatRole(command.role()),
                owner,
                command.nickname(),
                command.email()
        );
        return roomTokenIssuer.issue(claims);
    }

    /**
     * access token role(USER/SELLER/ADMIN) → chat ChatRole 매핑.
     * 인증된 입장 요청에 전달된 ADMIN role은 chat의 ADMIN role로 유지한다.
     * 게스트(GUEST)는 미인증 경로에서 처리하므로 이 메서드에 도달하지 않는다.
     * role이 null이거나 지원하지 않는 값이면 원본을 노출하지 않고 UnsupportedRoleException으로 실패시킨다(fail-closed).
     */
    private String toChatRole(String apiRole) {
        if (apiRole == null) {
            throw new UnsupportedRoleException();
        }
        return switch (apiRole) {
            case "USER" -> "BUYER";
            case "SELLER" -> "SELLER";
            case "ADMIN" -> "ADMIN";
            default -> throw new UnsupportedRoleException();
        };
    }
}
