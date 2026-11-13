package com.sapari.liveapp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공개 채팅 이력 조회 상한. 형제 {@link EnterRateLimitProperties} 와 같은 규율이다 —
 * <b>미설정은 허용하고(기본값) 잘못된 값은 부팅에서 막는다.</b>
 *
 * <p><b>왜 숫자를 맞힐 필요가 없는가.</b> 첫 값이 최적일 필요가 없다. 미설정이면 기본값으로 뜨고,
 * 운영에서 숫자를 올리는 것이 복구 수단이다. 잘못된 값만 부팅에서 막아 "조용히 상한 없이 도는 파드"
 * 를 없앤다. {@code chat.session.max-per-room}(SPR-176)이 같은 결론에 이미 도달했다.
 *
 * <p>⚠️ <b>버킷이 전역 하나다 — 주체별로 가르지 않는다.</b> 이 엔드포인트는 {@code permitAll}
 * 이라 익명 호출자에게 <b>신원이 아예 없고</b>, 샤드 키로 쓸 것이 없다. 그래서 형제처럼
 * guest/member 를 가르지 않는다. 대가는 분명하다: <b>한 호출자가 전역 예산을 다 먹으면 나머지가
 * 같이 429 를 받는다.</b> 그래도 상한을 두는 이유는, 없을 때 막히는 것이 이 엔드포인트가 아니라
 * 요청마다 Postgres 1 + Mongo 1 을 쓰는 <b>저장소</b>이고 그러면 같은 파드의 채팅·WS 까지 함께
 * 죽기 때문이다. 호출자별로 가르려면 IP 가 필요하고 그것은 신뢰 프록시(XFF) 결정을 요구한다 —
 * 이 티켓 밖이다.
 *
 * <p>⚠️ 상한은 <b>파드 로컬</b>이라 실효치가 상한 × 레플리카다. 형제가 같은 성질을 적는다.
 *
 * <p>⚠️ 버킷은 생성자에서 조립되고 이 레코드는 {@code @RefreshScope} 가 아니다(이 저장소에 그
 * 어노테이션 0건) — <b>한도 변경은 롤링 재시작이다.</b>
 */
@ConfigurationProperties("live.chat.history.rate-limit")
public class ChatHistoryRateLimitProperties {

    /**
     * 기본 초당 허용량.
     *
     * <p>근거는 "맞힌 수" 가 아니라 <b>모양</b>이다 — 사람이 이력을 다시 읽는 빈도는 초당 한 자리
     * 수준이고, 이 수를 넘기는 트래픽은 사람이 아니라 반복 호출이다. 운영에서 올리는 것이 복구
     * 수단이라 첫 값이 최적일 필요가 없다(위 javadoc).
     */
    private static final int DEFAULT_PERMITS_PER_SECOND = 20;

    /** 기본 버스트 — 화면 진입 직후의 묶음 호출을 받아 주되 지속 허용량은 위 값으로 수렴한다. */
    private static final int DEFAULT_BURST = 40;

    private Integer permitsPerSecond;
    private Integer burst;

    public void setPermitsPerSecond(Integer permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
    }

    public void setBurst(Integer burst) {
        this.burst = burst;
    }

    /**
     * 미설정을 기본값으로 채우고 잘못된 값은 던진다. 형제와 같은 모양이다.
     *
     * <p>{@code IllegalArgumentException} 을 던지면 빈 생성이 실패해 부팅이 막힌다 — 그것이
     * 의도다. 조용히 기본값으로 떨어지면 "설정했다고 믿는데 안 걸린 파드" 가 된다.
     */
    void normalizeAndValidate() {
        if (permitsPerSecond == null) {
            permitsPerSecond = DEFAULT_PERMITS_PER_SECOND;
        }
        if (burst == null) {
            burst = DEFAULT_BURST;
        }
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "live.chat.history.rate-limit.permits-per-second 는 양수여야 합니다: " + permitsPerSecond);
        }
        if (burst < permitsPerSecond) {
            // 버킷이 초당 보충량보다 작으면 채워지는 즉시 넘쳐 지속 허용량이 설정값에 못 미친다.
            throw new IllegalArgumentException(
                    "live.chat.history.rate-limit.burst 는 permits-per-second 이상이어야 합니다: "
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
