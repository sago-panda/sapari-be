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

    /**
     * 수명 상한을 흩는 폭(%) — 아래로만.
     *
     * <p>핸들러가 이 값으로 지터를 만드는데 <b>여기</b>에 두는 이유는 아래 가드가 이 폭을 알아야
     * 하기 때문이다. 폭을 핸들러에만 두면 가드가 "지터 전" 수명으로 판단해, 가드를 통과하는 설정에서
     * 생존 확인이 사실상 안 도는 조합이 생긴다 — {@code (91s, 30s, 90s)} 가 그런 예였다.
     */
    public static final long LIFETIME_JITTER_PERCENT = 15;

    public SessionTimeouts {
        if (maxLifetime == null || pingInterval == null || livenessDeadline == null) {
            throw new IllegalArgumentException("세 상한 모두 필수입니다.");
        }
        // 절대 하한. 이것이 유일한 구멍이었고 나머지는 전이적으로 막힌다.
        //
        // 0이면 아래 두 검사를 통과하는데 Flux.interval(ZERO, ZERO)은 예외 없이 최대 속도로 틱한다
        // (실측 ≈17M tick/s) — 프로퍼티 한 줄로 연결마다 ping 폭주가 된다.
        //
        // 음수의 실패 모양은 <b>값마다 다르다</b>(실측). 한때 여기에 "음수면 구독 시점에 예외가 나므로
        // 모든 WS 핸드셰이크가 죽는다"고 한 줄로 적었는데 그건 pingInterval 에만 맞는다:
        //   pingInterval      interval(-1s, -1s) 이 IllegalArgumentException("period >= 0 required")
        //                     → 기동은 성공하고 모든 핸드셰이크가 죽는다(기동 실패보다 나쁘다)
        //   maxLifetime       Mono.delay(-1s) 는 예외 없이 즉시 발화한다(실측 0ms)
        //                     → 모든 세션이 등록 직후 수명 종료로 끊긴다
        //   livenessDeadline  interval(delay=-1s, period=30s) 는 예외 없이 첫 틱을 즉시 낸다. 그리고
        //                     isUnresponsive 가 now - last >= deadline 이라 음수 기한은 항상 참이다
        //                     → 모든 세션이 즉시 수거된다
        // 셋 다 막아야 하는 것은 같고 근거만 값별로 다르다. 상한 셋을 한 값으로 묶은 이유가 이 검증이다.
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
        // 수명이 짧으면 생존 확인이 한 번도 돌지 않는다 — 코드는 남고 효과는 없다.
        //
        // 비교 대상이 생존 기한 자체가 아니라 <b>첫 점검 시각</b>이고, 비교하는 쪽은 지터를 뺀
        // <b>최소 수명</b>이다. 둘 다 "실제로 한 번은 도는가"를 재려면 필요하다:
        //   첫 점검은 기한이 아니라 기한 + 간격이다 — 핸들러가 그 시점부터 점검한다(생존 시계가
        //   등록 시각으로 초기화되므로, 기한에 바로 점검하면 첫 창에서만 유실 내성이 한 칸 짧아진다)
        //   수명은 지터로 아래로 흩어지므로 가장 짧은 경우를 봐야 한다 — 지터 폭을 모르는 가드는
        //   통과시키는데 대부분의 세션에서 생존 확인이 안 도는 조합을 만든다
        Duration shortestLifetime = maxLifetime.multipliedBy(100 - LIFETIME_JITTER_PERCENT).dividedBy(100);
        Duration firstLivenessCheck = livenessDeadline.plus(pingInterval);
        if (shortestLifetime.compareTo(firstLivenessCheck) <= 0) {
            throw new IllegalArgumentException(
                    "수명 상한이 짧아 생존 확인이 한 번도 돌지 않습니다 — 지터를 뺀 최소 수명이 첫 점검"
                            + "(기한 + 간격)보다 커야 합니다: lifetime=" + maxLifetime
                            + " (지터 " + LIFETIME_JITTER_PERCENT + "% 적용 시 최소 " + shortestLifetime + ")"
                            + " 첫 점검=" + firstLivenessCheck
                            + " (deadline=" + livenessDeadline + " + interval=" + pingInterval + ")");
        }
    }
}
