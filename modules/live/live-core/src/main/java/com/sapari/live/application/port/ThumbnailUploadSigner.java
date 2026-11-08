package com.sapari.live.application.port;

import java.time.Instant;

/**
 * 썸네일 업로드 허가 서명 — 저장소(S3)를 이 포트 뒤에 둔다. 네트워크 호출이 아니라 로컬 계산이다.
 *
 * <p>허가는 {@code key} 한 개, {@code contentType} 한 개, 크기 상한, 짧은 만료로 묶인다 — 저장소가 업로드 시점에
 * 강제한다. 실제 내용 검사는 업로드 후 인프라 검증(별도 티켓)이 한다.
 */
public interface ThumbnailUploadSigner {

    PresignedPost sign(String key, String contentType, Instant now);
}
