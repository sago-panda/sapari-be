package com.sapari.streamingapp.websocket;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 로그 솎아내기 — 간격당 1건, 첫 발생은 반드시 남는다. <b>사유마다 인스턴스 하나</b>다.
 *
 * <p>드롭이 몰리는 상황이 곧 CPU 포화라 건당 로그가 상황을 악화시킨다. <b>건수가 아니라 경과시간</b>으로
 * 센다: 건수 기준은 카운터가 프로세스 생애 누적이라 두 번째 이후의 짧은 버스트가 통째로 침묵한다 —
 * 드롭은 실제 메시지 유실이라 "조용한 유실이 되지 않게" 라는 목표와 정면으로 어긋난다.
 *
 * <p>⚠️ <b>사유끼리 인스턴스를 나눠 쓰지 않는다.</b> 한때 래치 하나를 세 실패 모드가 공유했고(경합 드롭 ·
 * 재등록 실패 · 시청자 수 갱신 실패), 그러면 먼저 찍은 쪽이 다른 사유의 <b>첫 발생</b>을 통째로 덮는다.
 * 재등록 실패와 시청자 수 갱신 실패는 같은 타이머 회차에서 연달아 나므로 10초 창이 아니라 <b>한 회차</b>
 * 안에서 서로를 가렸다. 이 저장소가 같은 결함을 이미 두 번 이름 붙여 고쳤다
 * ({@code EntryGate} · {@code SendChatService}).
 */
final class LogThrottle {

    static final long INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    /** 마지막으로 통과시킨 시각. 첫 발생이 반드시 남도록 간격만큼 과거로 초기화한다. */
    private final AtomicLong lastNanos = new AtomicLong(System.nanoTime() - INTERVAL_NANOS);

    /** 마지막 통과로부터 간격이 지났으면 true. 경쟁 시 CAS 로 한 스레드만 통과한다. */
    boolean tryAcquire() {
        long now = System.nanoTime();
        long last = lastNanos.get();
        return now - last >= INTERVAL_NANOS && lastNanos.compareAndSet(last, now);
    }
}
