package com.sapari.live.application.service;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.IngressResult;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.ReissueIngressCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.domain.exception.LiveMediaException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.ReissueIngressUseCase;
import com.sapari.live.view.IngressCredentialView;

/**
 * 송출 키 재발급. 키를 받은 Scheduled RTMP 방만 허용한다.
 *
 * <p>순서: 스냅샷 가드 → {@code createIngress} → 이전 ingressId 를 조건으로 거는 조건부 UPDATE(CAS) →
 * 이기면 이전 ingress 삭제, 지면 방금 만든 ingress 삭제. {@link PrepareIngressService} 와 같은 이유로
 * {@code @Transactional} 이 없다 — 외부 I/O 동안 커넥션·행 잠금을 잡지 않으려고 가드를 스냅샷으로 두고,
 * 배타성은 CAS 한 문장({@link RtmpIngressAssigner#reassignIfCurrent})에서 얻는다.
 *
 * <p>Ready 이후는 거부한다: Ready 는 이전 키로 막 붙은 OBS 연결과 경합하고, Live 에서 바꾸면 송출이 끊긴다.
 * 방송 중 OBS 재연결은 같은 키로 되므로 재발급이 필요 없다.
 *
 * <p>이전 ingress 를 지우는 id 는 DB 컬럼 값이지만, CAS 가 "이 방이 방금까지 인정하던 ingress" 임을 확인했고
 * 그 컬럼은 이 방에 대해 우리 {@code createIngress} 결과만 써 왔다({@code LiveMediaManager#deleteIngress} 경고 참고).
 * 삭제는 포트가 실패를 삼키므로(cleanup) {@link #revokeOldIngress} 가 방 ingress 목록으로 소멸을 확인하고 한 번 더 지운다.
 * 고아 미디어 잡에만 맡기지 않는 이유: CAS 가 방의 {@code updated_at} 을 갱신해 잡의 유예가 다시 걸리므로, 유출돼서
 * 재발급한 이전 키가 그 유예 동안 살아 있다. 그래도 남으면 error 로그만 남기고 성공을 돌려준다 — DB 는 이미 새 키를
 * 가리키고 streamKey 는 이 응답으로 한 번만 나가므로, 여기서 실패를 던지면 판매자가 유효한 새 키를 잃는다.
 * 받아들인 한계: {@code listRoomIngress} 는 본문 없는 200 을 빈 목록으로 읽으므로 그 응답은 "소멸"로 오판된다
 * — 그때는 고아 미디어 잡의 회수에 기댄다. 끝내 남은 경우는 지표 없이 error 로그로만 알린다(희귀한 이중 실패라
 * 카운터를 위해 154 와 겹치는 {@code LiveMetrics} 포트·테스트 더블을 바꿀 값어치가 없다고 봤다).
 *
 * <p>행 잠금 경로(시작·수정·취소의 {@code findByIdAndSellerIdForUpdate})는 CAS 이후 값을 읽으므로 안전하다.
 * 잠금 없이 Scheduled 방을 읽고 {@code save} 하는 경로가 생기면 그 save 가 매퍼를 통해 ingress_id 를 이전
 * 값으로 되돌릴 수 있다 — 그런 경로를 추가할 때 이 CAS 를 함께 봐야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReissueIngressService implements ReissueIngressUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveMediaManager liveMediaManager;
    private final RtmpIngressAssigner rtmpIngressAssigner;
    private final TimeProvider timeProvider;
    private final SellerLivePermissionGuard sellerLivePermissionGuard;

    @Override
    public IngressCredentialView reissue(ReissueIngressCommand command) {
        sellerLivePermissionGuard.verifyLiveEnabled(command.sellerId());
        // 소유권: 남의 방과 없는 방은 같은 404
        LiveRoom room = liveRoomRepository.findByIdAndSellerId(command.roomId(), command.sellerId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        if (!(room.status() instanceof LiveStatus.Scheduled) || !room.hasIngressKey()) {
            throw new InvalidLiveStateException(LiveErrorCode.INGRESS_REISSUE_NOT_ALLOWED, room.id().toString());
        }
        String currentIngressId = ((LiveStreamType.Rtmp) room.streamType()).ingressId();

        IngressResult result = liveMediaManager.createIngress(command.roomId(), command.sellerId());

        boolean reassigned = rtmpIngressAssigner.reassignIfCurrent(command.roomId(), command.sellerId(),
                currentIngressId, result.ingressId(), result.rtmpUrl(), timeProvider.now());
        if (!reassigned) {
            // 진 쪽은 자기가 만든 ingress 만 단건 회수한다(방 단위 삭제는 이긴 쪽 것까지 지운다).
            liveMediaManager.deleteIngress(command.roomId(), result.ingressId());
            log.info("RTMP ingress 재발급 실패(경합 패배 또는 상태 변경) — 생성한 ingress 회수. roomId={}, ingressId={}",
                    command.roomId(), result.ingressId());
            throw new InvalidLiveStateException(LiveErrorCode.INGRESS_REISSUE_NOT_ALLOWED, command.roomId().toString());
        }

        revokeOldIngress(command.roomId(), currentIngressId);
        log.info("RTMP ingress 재발급 완료. roomId={}, oldIngressId={}, newIngressId={}",
                command.roomId(), currentIngressId, result.ingressId());

        return new IngressCredentialView(result.ingressId(), result.rtmpUrl(), result.streamKey());
    }

    private static final int REVOKE_ATTEMPTS = 2;

    private void revokeOldIngress(UUID roomId, String oldIngressId) {
        for (int attempt = 1; attempt <= REVOKE_ATTEMPTS; attempt++) {
            liveMediaManager.deleteIngress(roomId, oldIngressId);
            try {
                boolean alive = liveMediaManager.listRoomIngress(roomId).stream()
                        .anyMatch(ingress -> oldIngressId.equals(ingress.ingressId()));
                if (!alive) {
                    return;
                }
            } catch (LiveMediaException e) {
                log.error("재발급 후 이전 ingress 소멸 확인 실패 — 고아 미디어 잡 회수 전까지 이전 키가 유효할 수 있음."
                        + " roomId={}, oldIngressId={}", roomId, oldIngressId, e);
                return;
            }
        }
        log.error("재발급 후 이전 ingress 삭제 실패 — 고아 미디어 잡 회수 전까지 이전 키가 유효함. roomId={}, oldIngressId={}",
                roomId, oldIngressId);
    }
}
