package com.sapari.live.port;

/**
 * 고아 미디어 정리 유스케이스 — LiveKit 에만 남은 ingress/egress 를 DB 기준으로 회수한다.
 *
 * <p>DB 는 판정 기준일 뿐 <b>읽기 전용</b>이다. 방 상태를 전이시키지 않으므로 갇힌 방을 푸는 건
 * 이 유스케이스의 몫이 아니다(그건 {@link ExpireOrphanLiveUseCase}).
 *
 * <p>인자가 없는 건 유예 시간이 배치 정책이라 서비스가 설정에서 읽기 때문이다. 호출자(스케줄러)는
 * 트리거만 한다.
 */
public interface ReconcileOrphanMediaUseCase {
    void reconcile();
}
