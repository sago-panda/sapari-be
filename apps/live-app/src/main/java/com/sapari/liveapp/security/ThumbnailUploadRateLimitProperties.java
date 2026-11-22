package com.sapari.liveapp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 썸네일 업로드 허가 발급 상한. 형제 {@link EnterRateLimitProperties} 와 같은 규율이다 —
 * <b>미설정은 허용하고(기본값) 잘못된 값은 부팅에서 막는다.</b>
 *
 * <p>상한이 없으면 막히는 것은 서명 CPU 가 아니라 <b>S3 업로드 용량</b>이다. 허가 하나가
 * {@code uploads/thumbnails/} 에 객체 하나를 올릴 권리이고, 발급은 요청당 한 번이라 발급을
 * 세지 않으면 판매자 한 명이 버킷에 올릴 수 있는 양에 천장이 없다 — 검증 Lambda 가 들어오면
 * 그 Lambda 호출 수까지 같이 는다.
 *
 * <p>⚠️ 상한은 <b>파드 로컬</b>이라 실효치가 상한 × 레플리카다. 버킷은 생성자에서 조립되므로
 * <b>한도 변경은 롤링 재시작이다.</b>
 */
@ConfigurationProperties("live.thumbnail.rate-limit")
public record ThumbnailUploadRateLimitProperties(
        Integer sellerPermitsPerSecond,
        Integer sellerBurst,
        Integer sellerShards
) {
    /**
     * 기본값 — <b>잰 값이 아니다.</b>
     *
     * <p>썸네일 지정은 방 생성·수정에 한 번 따라붙는 사람 속도의 동작이라 지속 요구가 초당 한 자리
     * 아래라고 보고 16/s 를 두었다. 버스트 48 은 이미지를 바꿔 가며 몇 번 다시 올리는 모양을 받는
     * 몫이다. 샤드 8 이면 판매자 한 명의 지속 몫이 2/s(= ⌈16/8⌉), 버스트가 12(= ⌈48/8⌉ × 헤드룸 2)다.
     * 운영에서 고치는 것이 복구 수단이다.
     */
    private static final CredentialIssueLimits DEFAULTS = new CredentialIssueLimits(16, 48, 8);

    /** 미설정을 기본값으로 채우고 잘못된 값은 던진다(부팅 차단). */
    public CredentialIssueLimits limits() {
        return CredentialIssueLimits.resolve("live.thumbnail.rate-limit", DEFAULTS,
                sellerPermitsPerSecond, sellerBurst, sellerShards);
    }
}
