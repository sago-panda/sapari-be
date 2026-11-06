package com.sapari.chat.application.service;

import com.sapari.chat.command.ClearRoomEndedMarkerCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.LiveNotActiveException;
import com.sapari.chat.domain.model.ChatAuditAction;
import com.sapari.chat.domain.model.ChatAuditEntry;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatRoomEndedWriteRepository;
import com.sapari.chat.port.ClearRoomEndedMarkerUseCase;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 잘못 기록된 종료 마커를 해제한다 — <b>해제 경로가 곧 그 마커에 없던 진위 검증이다.</b>
 *
 * <p>정본 L20: "종료 마커에 진위 검증·해제 경로가 없다. 오발행 1건이 그 방을 TTL(30분)만큼 채팅
 * 불능으로 만든다." 두 결함이 하나로 묶여 있다 — chat 은 받은 Pub/Sub 신호가 진짜인지 확인할 수단이
 * 없고(페이로드에 그런 것이 없다) 지울 길도 없어서, 마커 보관 기간이 그대로 <b>오판의 회복 시간</b>이
 * 된다. 기록 어댑터가 로그에 {@code (해제: DEL ...)} 를 찍어 둔 것이 그 때문이고, 그게 지금까지의
 * 유일한 처방이었다.
 *
 * <p>⚠️ <b>이것이 닫는 것은 외부·수동 오발행뿐이다.</b> 처음에 여기에 "방치된 Live 를 정리하는
 * 배치도 같은 신호를 발행하므로 방 단위 오판이 자동으로 생길 수 있다" 고 적었는데, <b>그 경우가
 * 하필 아래 LIVE 검증에 거부되는 경우</b>다. 생산 사슬을 전수로 재면 그 이유가 드러난다:
 * <pre>
 *   markEnded 호출처          LiveRoomEndedHandler 하나 — live:room:ended 구독으로만 깨어난다
 *   publishRoomEnded 호출처    EndLiveService · EndStaleLiveService 둘
 *   그 둘의 순서              endLive() → save() → afterCommit() → publish
 *   live 판정                 status instanceof LiveStatus.Live  (modules/live: "Ended is terminal")
 * </pre>
 * 즉 <b>마커가 존재하는 모든 순간 그 방은 DB 에서 이미 {@code Ended}</b> 다 — 앱 경로로는
 * "진행 중인데 마커가 있는" 상태가 만들어지지 않는다. 배치 오판도 그 방을 <i>먼저 커밋하고</i>
 * 발행하므로, 그 사고에서 이 엔드포인트는 400 을 돌려주고 TTL 30분은 그대로 남는다.
 *
 * <p>그래서 정본 L20 은 <b>부분적으로만</b> 닫혔다. 실제로 지울 수 있는 것은 앱 밖에서 들어온
 * 발행(운영자의 {@code redis-cli PUBLISH}, 잘못 설정된 도구, 재생된 메시지)이고 그 역경로는
 * 실재한다 — 전에는 그것조차 사람이 Redis 를 직접 만지는 수밖에 없었다.
 *
 * <p><b>배치 오판의 복구는 live 쪽 일이다.</b> chat 의 {@code DEL} 로 대신할 수 없다: 그 사고에서
 * 틀린 것은 마커가 아니라 <b>방 상태</b>이고, {@code Ended} 를 되돌리는 경로가 없으며
 * {@code RoomEnded} 페이로드에 진위·멱등 식별자도 없다(roomId + endedAt 뿐). 방 상태 복원이나
 * 오판을 줄이는 가드가 와야 닫히고, 그건 이 모듈 밖이다.
 *
 * <p><b>그래서 이 서비스의 핵심은 지우는 것이 아니라 묻는 것이다.</b> chat 이 못 하는 진위 확인을
 * live 의 정본이 한다 — 그 방이 <b>실제로</b> 진행 중이면 받은 종료 신호가 거짓이었다는 뜻이고,
 * 끝난 방이면 마커가 맞으므로 거부한다. 이 검증이 있어서 해제 권한을 판매자까지 열 수 있다:
 * 자기 방이 진짜 진행 중일 때만 풀 수 있으므로, 끝난 방을 되살리는 데는 쓸 수 없다.
 *
 * <p><b>권한을 강퇴와 같은 모양으로 둔 이유.</b> 오발행으로 막히는 것은 판매자의 진행 중인 방송이고,
 * 그것을 초 단위로 알아채는 사람도 판매자다("채팅이 죽었다"). 운영자만 풀 수 있게 하면 가장 급한
 * 사람이 가장 느린 경로를 기다린다 — 강퇴 명단 재적재({@code ReconcileKickListService})에서
 * ADMIN 전용을 고른 것과 <b>반대 방향</b>인데, 그쪽은 신호가 운영자만 읽는 로그였고 이쪽은
 * 당사자가 즉시 아는 증상이다. 가청성이 권한을 정한다는 같은 기준의 다른 답이다.
 *
 * <p>⚠️ <b>이 해제는 세션을 되살리지 않는다.</b> 종료 신호를 받은 Pod 들은 이미 그 방 세션을 닫았다.
 * 마커를 지우면 <b>다시 들어올 수 있게</b> 되는 것이고, 재입장은 클라이언트가 한다. 그래서 응답이
 * "복구됐다" 가 아니라 "막혀 있지 않다" 다.
 */
@Slf4j
@RequiredArgsConstructor
public class ClearRoomEndedMarkerService implements ClearRoomEndedMarkerUseCase {

    private final GetLiveRoomUseCase liveRoomReader;
    private final ChatRoomEndedWriteRepository roomEndedWriteRepository;
    private final ChatAuditRecorder auditRecorder;
    private final TimeProvider timeProvider;

    @Override
    public void clear(ClearRoomEndedMarkerCommand command) {
        ChatRole actorRole = ChatRole.fromPlatformRole(command.actorRole())
                .orElseThrow(() -> new ChatPermissionDeniedException(
                        "알 수 없는 역할이다 — role=" + command.actorRole()));

        // 방을 먼저 읽는다 — 권한 판정에 방 주인이 필요하다. 없는 방은 권한 거부로 접는다:
        // "없는 방" 과 "권한 없음" 을 가르면 응답 차이가 방 열거 오라클이 된다(강퇴와 같은 판단).
        LiveRoomView room = liveRoomReader.findRoom(command.roomId())
                .orElseThrow(() -> new ChatPermissionDeniedException(
                        "종료 마커를 해제할 수 없는 방이다 — roomId=" + command.roomId()));

        if (!room.sellerId().equals(command.actorId()) && actorRole != ChatRole.ADMIN) {
            throw new ChatPermissionDeniedException(
                    "종료 마커 해제 권한이 없다 — actor=" + command.actorId() + " room=" + command.roomId());
        }

        // 진위 검증. 이 줄이 이 엔드포인트의 존재 이유이자 안전장치다 — 끝난 방의 마커는 맞는
        // 마커이므로 지우면 안 된다. 이것을 빼면 "종료된 방을 다시 열 수 있는 엔드포인트" 가 된다.
        //
        // ⚠️ 이 검증이 앱 경로의 오발행을 <전부> 거부한다는 사실을 클래스 javadoc 에 적었다 —
        // 그쪽 사슬이 방을 먼저 Ended 로 커밋하고 발행하기 때문이다. 그러니 이 분기는 "드문
        // 거부" 가 아니라 <앱 밖에서 온 발행만 통과시키는 문> 이다. 느슨하게 고치고 싶어지면
        // 그 javadoc 을 먼저 읽을 것: 느슨해지는 순간 이것은 종료된 방을 되살리는 수단이 된다.
        if (!room.live()) {
            throw new LiveNotActiveException(
                    "진행 중인 방이 아니라 종료 마커를 해제할 수 없다 — 그 마커는 맞는 마커다 roomId="
                            + command.roomId());
        }

        roomEndedWriteRepository.clearEnded(command.roomId());

        // ⚠️ 지우고 나서 <다시 묻는다>. 위 검증과 이 삭제 사이에 방이 끝날 수 있다 —
        // GetLiveRoomUseCase 는 readOnly 트랜잭션의 findById 라 행 잠금이 없고 그 트랜잭션은
        // 반환과 함께 닫힌다. 그 사이에 endLive 가 커밋되면 발행 → 어느 pod 가 마커를 다시
        // 쓰는데, 이 삭제가 그 <맞는 마커>를 지운다.
        //
        // 그 잔여 상태가 나쁜 쪽은 종료 신호를 놓친 pod 다. 그 pod 의 유일한 백스톱이 전송
        // 경로의 마커 재검사인데, 마커가 사라지면 그게 <영구히> 없어진다 — 다시 세워 주는
        // 경로가 없다(RoomEnded 는 Ended 가 terminal 이라 다시 오지 않는다).
        //
        // 읽기 하나로 닫는다. 지운 뒤에 끝난 것으로 보이면 마커를 되돌려 놓고 거부한다 —
        // 이 조치는 "진행 중인 방의 거짓 마커를 지운다" 이지 "끝난 방을 열어 준다" 가 아니다.
        // 되돌림이 실패하면 그 예외가 그대로 나간다: 마커 없이 Ended 인 상태를 조용히 두는 것이
        // 이 분기가 막으려는 바로 그 상태다.
        boolean endedWhileClearing = liveRoomReader.findRoom(command.roomId())
                .map(after -> !after.live())
                .orElse(true);
        if (endedWhileClearing) {
            roomEndedWriteRepository.markEndedAgain(command.roomId());
            throw new LiveNotActiveException(
                    "해제 중에 방이 끝났다 — 지운 마커를 되돌렸다. roomId=" + command.roomId());
        }

        // 사람이 누른 조치이고, 기록 쪽이 "이 방이 언제 잠겼는지" 를 로그로만 남기므로 짝이 되게 적는다.
        log.info("방 종료 마커 해제 — 앱 밖에서 들어온 발행이었다. roomId={} actor={} "
                        + "(세션은 되살지 않는다, 재입장 필요)",
                command.roomId(), command.actorId());

        // 감사는 <되돌림 분기 뒤>에 적는다. 위에서 마커를 되세우고 거부한 요청은 해제가
        // 아니었으므로 감사 행도 없어야 한다 — 삭제 직후에 적으면 되돌려진 조치가 "수행됨" 으로 남는다.
        //
        // ⚠️ <로그보다도 뒤>다. 감사 쓰기는 예외를 삼키지 않으므로 그것이 실패하면 요청이 500 이
        // 되는데, 위에 두면 그 분기에서 로그까지 함께 사라져 "조치는 됐고 아무 흔적도 없다" 가 된다.
        // 뒤에 두면 같은 실패에서 로그는 남는다 — 감사 행의 폴백이 그 로그다.
        //
        // detail 이 비어 있다. 이 조치는 한 방의 마커 하나를 지우는 것이고 그 방은 targetId 가
        // 가리키므로, 더 실을 것이 없다(행위마다 detail 이 필요한지는 ChatAuditAction 이 들고 있다).
        auditRecorder.record(new ChatAuditEntry(
                command.actorId(), actorRole, ChatAuditAction.CHAT_CLEAR_ROOM_ENDED_MARKER,
                command.roomId(), null, timeProvider.now()));
    }
}
