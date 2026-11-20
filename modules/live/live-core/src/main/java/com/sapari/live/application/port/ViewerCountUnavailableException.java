package com.sapari.live.application.port;

/** {@link LiveViewerCounter} 가 시청자 수를 읽지 못했다. 재조정이 받아 "이번 회차는 시청자 수 갱신 없음" 으로 접는다. */
public class ViewerCountUnavailableException extends RuntimeException {

    public ViewerCountUnavailableException(Throwable cause) {
        super("시청자 수를 읽지 못했다", cause);
    }
}
