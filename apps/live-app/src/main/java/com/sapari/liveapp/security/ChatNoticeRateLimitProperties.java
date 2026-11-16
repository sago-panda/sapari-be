package com.sapari.liveapp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공개 현재 공지 조회 상한. 규율과 게스트 / 회원 가르기(SPR-203)는 형제
 * {@link ChatHistoryRateLimitProperties} 와 같다 — <b>미설정은 기본값, 잘못된 값은 부팅 차단.</b>
 *
 * <p>⚠️ <b>이력 버킷과 따로 둔다.</b> 공지는 라이브 입장 때마다 한 번 불리고(프론트 가이드),
 * 이력은 입장 때 부르지 않는다. 호출 빈도가 입장과 같은 경로를 이력 버킷에 섞으면 입장
 * 몰림이 다시보기 이력을 막는다.
 */
@ConfigurationProperties("live.chat.notice.rate-limit")
public record ChatNoticeRateLimitProperties(
        Integer guestPermitsPerSecond,
        Integer guestBurst,
        Integer memberPermitsPerSecond,
        Integer memberBurst,
        Integer memberShards
) {
    /**
     * 기본값 — 게스트·회원 모두 입장 상한({@code live.enter.rate-limit})과 같은 500/s · 버스트 1,000,
     * 샤드 64 다.
     *
     * <p>⚠️ <b>잰 값이 아니다.</b> "공지 조회는 입장 1회당 1회" 라는 가정에서 나온 수다. 요청마다
     * Postgres 2(방 조회 + 강퇴 목록) + Mongo 1(파셜 인덱스, docsExamined ≤ 1)을 쓴다.
     *
     * <p>⚠️ 가르기 전에는 전체가 500/s 였고, 지금은 게스트 500 + 회원 500 이라 저장소에 걸리는 최악값이
     * 두 배다. 게스트가 주 경로라 게스트 몫을 줄이지 않았다.
     */
    private static final ChatReadLimits DEFAULTS = new ChatReadLimits(500, 1_000, 500, 1_000, 64);

    /** 미설정을 기본값으로 채우고 잘못된 값은 던진다(부팅 차단). */
    public ChatReadLimits limits() {
        return ChatReadLimits.resolve("live.chat.notice.rate-limit", DEFAULTS,
                guestPermitsPerSecond, guestBurst, memberPermitsPerSecond, memberBurst, memberShards);
    }
}
