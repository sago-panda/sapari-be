package com.sapari.live.view;

/**
 * 판매자가 방 생성 때 고르는 송출 방식. 생성 후에는 바뀌지 않는다.
 *
 * <p>RTMP = OBS 등 인코더(송출 키 필요), WEBRTC = 브라우저·앱 카메라.
 */
public enum LiveStreamKind {
    RTMP, WEBRTC
}
