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
        // 절대 하한. 이것이 유일한 구멍이었고 나머지는 전이적으로 막힌다.
        // 0이면 아래 두 검사를 통과하는데 Flux.interval(ZERO, ZERO)은 예외 없이 최대 속도로 틱한다
        // (실측 ≈17M tick/s) — 프로퍼티 한 줄로 연결마다 ping 폭주가 된다. 음수면 구독 시점에
        // 예외가 나므로 기동은 성공하고 <b>모든 WS 핸드셰이크가 죽는다</b> — 기동 실패보다 나쁘다.
        // 상한 셋을 한 값으로 묶은 이유가 이 검증이다.
        if (!pingInterval.isPositive() || !livenessDeadline.isPositive() || !maxLifetime.isPositive()) {
            throw new IllegalArgumentException(
                    "세 상한 모두 양수여야 합니다 — 0이면 ping 이 최대 속도로 나가고 음수면 접속마다 깨집니다: "
                            + "lifetime=" + maxLifetime + " interval=" + pingInterval
                            + " deadline=" + livenessDeadline);
        }
        // 기한이 간격의 <b>두 배 이상</b>이어야 한다.
        //
        // "한 번보다 커야 한다"로는 부족하다 — 생존 시계가 등록 시각으로 초기화되고 판정이
        // {@code now - last >= deadline}이라, {@code deadline = interval + δ}에서는 유실이 없어도
        // 왕복 지연이 δ를 넘기는 것만으로 첫 점검에서 멀쩡한 세션이 끊긴다. 적어 둔 근거
        // ("한 번의 유실을 다음 것이 메운다")에 맞는 제약은 두 배다.
        if (livenessDeadline.compareTo(pingInterval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException(
                    "생존 기한은 ping 간격의 두 배 이상이어야 합니다 — 그보다 짧으면 유실이 없어도 왕복 지연 "
                            + "하나로 멀쩡한 세션이 끊깁니다: interval=" + pingInterval
                            + " deadline=" + livenessDeadline);
        }
        // 수명 상한이 생존 기한보다 작으면 생존 확인이 한 번도 돌지 않는다 — 코드는 남고 효과는 없다.
        if (maxLifetime.compareTo(livenessDeadline) <= 0) {
            throw new IllegalArgumentException(
                    "수명 상한은 생존 기한보다 커야 합니다 — 작으면 생존 확인이 한 번도 돌지 않습니다: "
                            + "lifetime=" + maxLifetime + " deadline=" + livenessDeadline);
        }
    }
}
