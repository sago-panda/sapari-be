package com.sapari.live.port;

/**
 * 방치된 Live 방 정리 — 오래된 Live 방 중 <b>LiveKit 에 활성 egress 가 없는</b> 방만 종료한다.
 *
 * <p>DB 의 경과 시간만으로는 판단할 수 없다. 정상적으로 오래 진행 중인 방송도 똑같이 오래됐기 때문이다.
 * 판정은 반드시 "송출이 살아 있는가"여야 하며, <b>시청자 수로 판단해서는 안 된다</b> —
 * HLS 시청자는 SFU 참가자가 아니라 인기 방송도 0 으로 보이고, 시청자 0 인 방송은 정상이다.
 *
 * <p>종료 자체는 방마다 {@link EndStaleLiveUseCase} 로 위임한다(방별 트랜잭션·행 잠금).
 */
public interface ReconcileStaleLiveUseCase {
    void reconcile();
}
