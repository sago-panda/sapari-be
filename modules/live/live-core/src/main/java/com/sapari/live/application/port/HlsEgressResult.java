package com.sapari.live.application.port;

public record HlsEgressResult(
        String egressId,
        String hlsUrl,
        String hlsArchiveUrl
) {
    public HlsEgressResult {
        // 성공한 egress 시작 응답의 계약이다. egress를 시작하지 않은 방의 StreamInfo는 null을 허용한다.
        if (hlsUrl == null || hlsUrl.isBlank()) {
            throw new IllegalArgumentException("hlsUrl은 필수입니다.");
        }
        if (hlsArchiveUrl == null || hlsArchiveUrl.isBlank()) {
            throw new IllegalArgumentException("hlsArchiveUrl은 필수입니다.");
        }
    }
}
