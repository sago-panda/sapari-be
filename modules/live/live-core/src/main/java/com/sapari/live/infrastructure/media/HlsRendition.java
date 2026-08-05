package com.sapari.live.infrastructure.media;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * HLS 송출 화질 정의 — egress 시작과 master.m3u8 생성이 공유하는 단일 출처.
 *
 * <p>{@code width}/{@code height}는 master의 {@code RESOLUTION}, {@code pathSegment}는 화질별 S3 경로이자
 * master에서의 상대 참조 경로가 된다. 화질을 추가/변경하려면 이 enum만 고치면 egress와 master가 함께
 * 따라온다(불일치 방지).
 *
 * <p>인코딩 파라미터(framerate/video·audio 비트레이트) 사용처는 두 갈래다:
 * <ul>
 *   <li><b>360p</b> — LiveKit 프리셋이 없어 이 필드들로 커스텀 {@code EncodingOptions}를 직접 구성한다.
 *   <li><b>1080p/720p</b> — 실제 인코딩은 LiveKit 프리셋(H264_1080P_30/H264_720P_30)이 담당하므로 이 비트레이트
 *       필드는 egress 설정에 쓰이지 않는다. 다만 master의 {@code BANDWIDTH} 광고를 위해 <b>프리셋의 문서화된
 *       값과 동일하게</b> 유지해야 한다(출처: livekit/protocol {@code livekit_egress.proto} 주석 — 4500k/3000k).
 * </ul>
 *
 * <p>{@link #getBandwidth()}(master의 피크 총 비트레이트)는 {@code (video+audio)kbps}에서 파생된다 —
 * 별도 literal을 두지 않아 비트레이트 변경 시 광고값이 자동으로 따라온다.
 */
@Getter
@RequiredArgsConstructor
enum HlsRendition {
    P1080("1080p", 1920, 1080, 30, 4500, 128),
    P720("720p", 1280, 720, 30, 3000, 128),
    P360("360p", 640, 360, 30, 800, 128);

    private final String pathSegment;
    private final int width;
    private final int height;
    private final int framerate;
    private final int videoBitrateKbps;
    private final int audioBitrateKbps;

    /** master.m3u8 {@code BANDWIDTH}(피크 총 비트레이트, bps) = (video + audio)kbps. */
    long getBandwidth() {
        return (long) (videoBitrateKbps + audioBitrateKbps) * 1000;
    }

    /** master.m3u8 위치({roomId}/master.m3u8) 기준 변형 플레이리스트 상대 경로. */
    String variantPlaylistPath() {
        return pathSegment + "/index.m3u8";
    }
}
