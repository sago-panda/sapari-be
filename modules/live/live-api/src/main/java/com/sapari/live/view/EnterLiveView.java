package com.sapari.live.view;

/**
 * 라이브 입장 응답.
 *
 * <p>{@code hlsUrl}: 시청용 HLS 재생 URL. {@code roomToken}: 채팅(streaming-app) 입장용 RS256 룸 토큰.
 * 미인증(게스트)이거나 발급 대상이 아니면 {@code roomToken}은 null일 수 있다.
 *
 * <p>{@code roomToken}에는 email(PII)이 담기므로 로그에 남기지 않는다.
 */
public record EnterLiveView(
        String hlsUrl,
        String roomToken
) {
}
