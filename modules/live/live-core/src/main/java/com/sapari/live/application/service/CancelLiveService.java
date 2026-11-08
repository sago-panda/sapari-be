package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.command.CancelLiveCommand;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.CancelLiveUseCase;

/**
 * 판매자가 예약 방송을 취소한다.
 *
 * <p>트랜잭션 안에서는 DB 만 만진다 — 생성 때 만든 LiveKit 방(과 발급된 ingress)의 정리는 종료 계열과 같은
 * {@link PostCommitMediaCleanup} 으로 커밋 뒤에 한다. 정리 포트는 실패를 삼키고(이미 취소는 커밋됐다),
 * 남은 자원은 고아 미디어 재조정이 종단 상태({@code LiveStatus.isTerminal})로 보고 회수한다.
 *
 * <p>RoomEnded 는 발행하지 않는다 — 방송된 적이 없어 채팅 세션이 없다({@code expire()} 와 같은 이유).
 */
@Service
@RequiredArgsConstructor
public class CancelLiveService implements CancelLiveUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveMediaManager liveMediaManager;
    private final TimeProvider timeProvider;
    private final LiveMetrics liveMetrics;

    @Override
    @Transactional
    public void cancel(CancelLiveCommand command) {
        // 잠금 조회: 시작·수정과 같은 방을 두고 경합하므로 전이 전에 직렬화한다. 소유권은 쿼리에 둔다.
        LiveRoom room = liveRoomRepository.findByIdAndSellerIdForUpdate(command.roomId(), command.sellerId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        LiveRoom cancelled = room.cancel(timeProvider.now());

        liveRoomRepository.save(cancelled);
        liveMetrics.roomTransitioned(room.status(), cancelled.status());

        // save 뒤에 등록한다(EndLiveService 와 같은 순서) — save 가 실패하면 멀쩡한 방의 미디어만 지워진다.
        PostCommitMediaCleanup.register(liveMediaManager, room);
    }
}
