package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.RecentViewRecorder;
import com.sapari.live.application.port.RoomTokenClaims;
import com.sapari.live.application.port.RoomTokenIssuer;
import com.sapari.live.command.EnterLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveErrorCode;
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
    private final RecentViewRecorder recentViewRecorder;
    private final TimeProvider timeProvider;

    /**
     * ⚠️ <b>{@code @Transactional} 이 없다 — 의도다.</b> 이 메서드가 하는 일은 PK 단건 읽기
     * 하나와 <b>RS256 서명</b>이고, 서명은 실측 685µs(RSA-2048)다. 읽기를 트랜잭션으로 감싸면
     * 그 서명이 <b>풀 커넥션을 점유한 채</b> 돌아간다 — Hikari 기본 최대 풀이 10 이고 이
     * 엔드포인트는 미인증도 받으므로(상한은 {@code EnterRateLimitFilter}) 점유시간이 그대로
     * 처리량 천장이 된다. 고갈되면 피해자는 같은 풀에서 <b>행 잠금 읽기</b>를 하는 상태 전이들이다
     * — {@code StartLiveService}·{@code EndLiveService} 의
     * {@code findByIdAndSellerIdForUpdate}, 그리고 {@code findByIdForUpdate} 를 쓰는
     * {@code GoLiveByRtmpService}·{@code EndStaleLiveService}·{@code ExpireOrphanLiveService}.
     *
     * <p>떼도 안전한 근거: 읽기가 <b>한 문장</b>이라 어댑터의 Spring Data 호출이 자기
     * 읽기전용 트랜잭션을 갖고, {@code LiveRoomEntity} 에 JPA 연관이 <b>0건</b>이라
     * ({@code OneToMany}/{@code ManyToOne}/{@code FetchType}/{@code Embedded}/
     * {@code ElementCollection} 전수 0) 매퍼가 돌려준 레코드가 완성 상태다 — 트랜잭션 밖에서
     * 접근할 지연 프록시가 없다.
     *
     * <p>⚠️ 읽기가 둘 이상이 되거나 엔티티에 연관이 생기면 이 전제가 깨진다. 그때는
     * 트랜잭션을 다시 붙이는 대신 <b>읽기만</b> 감싸고 서명을 밖에 두라 — 감싸는 범위가
     * 넓어지는 것이 이 결함의 모양이었다.
     *
     * <p>최근 본 기록(SPR-172)은 {@link RecentViewRecorder} 에 <b>넘기기만</b> 한다 — 쓰기는 전용 실행기에서 돌아
     * 이 스레드는 기록 때문에 커넥션을 잡지 않고, 기록 실패·지연이 입장 응답에 보이지 않는다.
     */
    @Override
    public EnterLiveView enter(EnterLiveCommand command) {
        LiveRoom room = liveRoomRepository.findById(command.roomId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        // 라이브 진행 중일 때만 입장(=룸 토큰 발급) 가능 — 권위 평가는 live DB 기준.
        if (!room.canEnterLive()) {
            throw new InvalidLiveStateException(LiveErrorCode.LIVE_ENTER_NOT_ALLOWED, command.roomId().toString());
        }

        // 회원은 신원 기반 토큰, 게스트(미인증)는 에페메랄 GUEST 토큰 — 둘 다 채팅 입장은 가능(GUEST는 수신 전용).
        String roomToken;
        if (command.isAuthenticated()) {
            roomToken = issueRoomToken(command, room);
            // 성공한 회원 입장만 — 거부·게스트는 여기 오지 않는다. 발급 <b>뒤</b>에 두므로, 발급자를 감싸는 데코레이터(dev 의
            // 서명 캐시 등)가 붙어도 재접속은 같은 길로 기록된다.
            recentViewRecorder.record(command.userId(), command.roomId(), timeProvider.now());
        } else {
            roomToken = issueGuestToken(command.roomId());
        }

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
