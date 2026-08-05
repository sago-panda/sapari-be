package com.sapari.live.infrastructure.media;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * HLS master(멀티 화질) 플레이리스트 텍스트 생성.
 *
 * <p>{@link HlsRendition} 목록을 {@code #EXT-X-STREAM-INF} 엔트리로 펼쳐, 플레이어가 한 장으로
 * 모든 화질을 인지하고 ABR/수동 선택을 할 수 있게 한다. 변형 URI는 master.m3u8 위치 기준
 * <b>상대 경로</b>라 roomId에 무관하다(어느 방이든 내용 동일).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class MasterPlaylistGenerator {

    static String generate() {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        for (HlsRendition rendition : HlsRendition.values()) {
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(rendition.getBandwidth())
                    .append(",RESOLUTION=").append(rendition.getWidth()).append('x').append(rendition.getHeight())
                    .append('\n');
            sb.append(rendition.variantPlaylistPath()).append('\n');
        }
        return sb.toString();
    }
}
