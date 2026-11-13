package com.sapari.liveapp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공개 현재 공지 조회 상한. 규율과 대가(전역 버킷 하나 · 파드 로컬 · 변경은 롤링 재시작)는 형제
 * {@link ChatHistoryRateLimitProperties} 와 같다 — <b>미설정은 기본값, 잘못된 값은 부팅 차단.</b>
 *
 * <p>⚠️ <b>이력 버킷과 따로 둔다.</b> 공지는 라이브 입장 때마다 한 번 불리고(프론트 가이드),
 * 이력은 입장 때 부르지 않는다. 호출 빈도가 입장과 같은 경로를 이력의 20/s 에 섞으면 입장
 * 몰림이 다시보기 이력을 막는다.
 */
@ConfigurationProperties("live.chat.notice.rate-limit")
public class ChatNoticeRateLimitProperties {

    /**
     * 기본 초당 허용량 — 입장 상한({@code live.enter.rate-limit} 회원 500/s)과 같은 자릿수다.
     *
     * <p>⚠️ <b>잰 값이 아니다.</b> "공지 조회는 입장 1회당 1회" 라는 가정에서 나온 수다. 요청마다
     * Postgres 2(방 조회 + 강퇴 목록) + Mongo 1(파셜 인덱스, docsExamined ≤ 1)을 쓴다. 운영에서 고치는 것이
     * 복구 수단이다(형제 javadoc).
     */
    private static final int DEFAULT_PERMITS_PER_SECOND = 500;

    /** 기본 버스트 — 방송 시작 직후의 입장 몰림을 받는다. 입장 상한의 회원 버스트와 같다. */
    private static final int DEFAULT_BURST = 1_000;

    private Integer permitsPerSecond;
    private Integer burst;

    public void setPermitsPerSecond(Integer permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
    }

    public void setBurst(Integer burst) {
        this.burst = burst;
    }

    /** 미설정을 기본값으로 채우고 잘못된 값은 던진다(부팅 차단). 형제와 같은 모양이다. */
    void normalizeAndValidate() {
        if (permitsPerSecond == null) {
            permitsPerSecond = DEFAULT_PERMITS_PER_SECOND;
        }
        if (burst == null) {
            burst = DEFAULT_BURST;
        }
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "live.chat.notice.rate-limit.permits-per-second 는 양수여야 합니다: " + permitsPerSecond);
        }
        if (burst < permitsPerSecond) {
            throw new IllegalArgumentException(
                    "live.chat.notice.rate-limit.burst 는 permits-per-second 이상이어야 합니다: "
                            + burst + " < " + permitsPerSecond);
        }
    }

    int permitsPerSecond() {
        return permitsPerSecond;
    }

    int burst() {
        return burst;
    }
}
