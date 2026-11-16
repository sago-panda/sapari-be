package com.sapari.liveapp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공개 채팅 이력 조회 상한. 형제 {@link EnterRateLimitProperties} 와 같은 규율이다 —
 * <b>미설정은 허용하고(기본값) 잘못된 값은 부팅에서 막는다.</b>
 *
 * <p><b>게스트 / 회원으로 가른다(SPR-203).</b> 전에는 "익명에게 샤드 키로 쓸 신원이 없다" 는 이유로
 * 전역 버킷 하나였고, 그 대가로 익명 호출자 하나가 로그인 회원의 다시보기 채팅까지 막았다. 회원은
 * 신원이 있으므로 입장 상한과 같은 방식으로 나눈다({@link ChatReadRateLimiter}). 게스트는 여전히
 * 전역 하나다 — 요청마다 바꿀 수 있는 값(IP·헤더)을 키로 쓰지 않는 이유는 형제 javadoc 과 같다.
 *
 * <p>상한이 없으면 막히는 것은 이 엔드포인트가 아니라 요청마다 Postgres 2 + Mongo 1 을 쓰는
 * <b>저장소</b>이고, 그러면 같은 파드의 채팅·WS 까지 함께 느려진다.
 *
 * <p>⚠️ 상한은 <b>파드 로컬</b>이라 실효치가 상한 × 레플리카다. 버킷은 생성자에서 조립되므로
 * <b>한도 변경은 롤링 재시작이다.</b>
 */
@ConfigurationProperties("live.chat.history.rate-limit")
public record ChatHistoryRateLimitProperties(
        Integer guestPermitsPerSecond,
        Integer guestBurst,
        Integer memberPermitsPerSecond,
        Integer memberBurst,
        Integer memberShards
) {
    /**
     * 기본값.
     *
     * <p>게스트 20/s · 버스트 40 은 가르기 전의 전역값 그대로다 — 사람이 이력을 다시 읽는 빈도는
     * 초당 한 자리 수준이라는 모양에서 나온 수다.
     *
     * <p>회원 200/s · 버스트 400 · 샤드 32 는 <b>잰 값이 아니다.</b> 재접속 조회(SPR-202
     * {@code afterId})가 같은 경로를 쓰므로 streaming-app 파드가 재시작되면 회원이 거의 동시에 한 번씩
     * 부른다 — 그 몰림을 버스트로 받고, 넘친 몫은 {@code Retry-After} 뒤 리필(200/s)로 받는다는
     * 가정이다. 샤드 몫은 지속 7/s(= ⌈200/32⌉) · 버스트 26(= ⌈400/32⌉ × 헤드룸 2)이다.
     * 운영에서 고치는 것이 복구 수단이다.
     *
     * <p>⚠️ 가르기 전에는 전체가 20/s 였고, 지금은 게스트 20 + 회원 200 이라 저장소에 걸리는 파드당
     * 최악값이 220/s(11배)다. 계정 여럿으로 회원 샤드를 덮는 악용이 보이면 회원 한도를 낮춘다.
     *
     * <p>⚠️ 게스트 시청자의 재접속도 이 경로를 쓰고, 그쪽은 게스트 버킷(버스트 40)에 걸린다. 게스트를
     * 올리면 익명 호출자가 쓸 수 있는 저장소 몫도 같이 오르므로 이 티켓에서는 그대로 둔다.
     */
    private static final ChatReadLimits DEFAULTS = new ChatReadLimits(20, 40, 200, 400, 32);

    /** 미설정을 기본값으로 채우고 잘못된 값은 던진다(부팅 차단). */
    public ChatReadLimits limits() {
        return ChatReadLimits.resolve("live.chat.history.rate-limit", DEFAULTS,
                guestPermitsPerSecond, guestBurst, memberPermitsPerSecond, memberBurst, memberShards);
    }
}
