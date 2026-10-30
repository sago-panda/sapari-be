package com.sapari.streamingapp.websocket;

import java.time.Duration;

/**
 * 연결 하나에 걸리는 시간 상한 셋.
 *
 * <p>셋을 한 값으로 묶는 이유는 <b>서로 제약하기 때문</b>이다. 따로 받으면 그 제약이 생성자 인자
 * 목록에만 암묵적으로 남는다.
 *
 * @param maxLifetime      연결이 살 수 있는 최대 시간. 재접속이 입장을 다시 타므로 이 값이
 *                         "접속 후 바뀐 권위"가 반영되기까지의 상한이 된다.
 * @param pingInterval     서버가 생존을 묻는 간격. 보는 것만 하는 시청자가 대다수라 발화를 생존
 *                         신호로 쓸 수 없어서 서버가 먼저 묻는다.
 * @param livenessDeadline 아무 프레임도 올라오지 않을 때 끊는 기한
 */
public record SessionTimeouts(Duration maxLifetime, Duration pingInterval, Duration livenessDeadline) {

    public SessionTimeouts {
        if (maxLifetime == null || pingInterval == null || livenessDeadline == null) {
            throw new IllegalArgumentException("세 상한 모두 필수입니다.");
        }
        // 기한이 간격보다 커야 한다. 같거나 작으면 ping 하나를 잃는 것만으로 멀쩡한 세션이 끊긴다 —
        // 기한 안에 ping 이 여러 번 나가야 한 번의 유실을 다음 것이 메운다.
        if (livenessDeadline.compareTo(pingInterval) <= 0) {
            throw new IllegalArgumentException(
                    "생존 기한은 ping 간격보다 커야 합니다 — 같으면 ping 한 번 유실로 멀쩡한 세션이 끊깁니다: "
                            + "interval=" + pingInterval + " deadline=" + livenessDeadline);
        }
        // 수명 상한이 생존 기한보다 작으면 생존 확인이 한 번도 돌지 않는다 — 코드는 남고 효과는 없다.
        if (maxLifetime.compareTo(livenessDeadline) <= 0) {
            throw new IllegalArgumentException(
                    "수명 상한은 생존 기한보다 커야 합니다 — 작으면 생존 확인이 한 번도 돌지 않습니다: "
                            + "lifetime=" + maxLifetime + " deadline=" + livenessDeadline);
        }
    }
}
