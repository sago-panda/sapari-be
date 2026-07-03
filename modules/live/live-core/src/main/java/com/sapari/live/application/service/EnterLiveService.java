package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.live.application.port.RoomTokenClaims;
import com.sapari.live.application.port.RoomTokenIssuer;
import com.sapari.live.command.EnterLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
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

        String roomToken = command.isAuthenticated() ? issueRoomToken(command, room) : null;

        log.info("live 입장. roomId={}, authenticated={}", command.roomId(), command.isAuthenticated());
        return new EnterLiveView(room.hlsUrl(), roomToken);
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
     * api-app role(USER/SELLER) → chat ChatRole 매핑. ADMIN은 출처가 별도라 여기서 다루지 않는다.
     * 게스트(GUEST)는 미인증 경로에서 처리하므로 이 메서드에 도달하지 않는다.
     */
    private String toChatRole(String apiRole) {
        return switch (apiRole) {
            case "USER" -> "BUYER";
            case "SELLER" -> "SELLER";
            default -> throw new InvalidLiveStateException("지원하지 않는 role: " + apiRole);
        };
    }
}
