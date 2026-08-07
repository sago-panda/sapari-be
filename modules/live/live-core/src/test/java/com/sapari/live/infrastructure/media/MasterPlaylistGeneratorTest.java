package com.sapari.live.infrastructure.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MasterPlaylistGeneratorTest {

    @Test
    @DisplayName("master.m3u8: 헤더와 세 화질 variant가 모두 포함된다")
    void generatesMasterPlaylistWithAllRenditions() {
        String master = MasterPlaylistGenerator.generate();

        // HLS master 헤더
        assertThat(master).startsWith("#EXTM3U");
        assertThat(master).contains("#EXT-X-VERSION:3");

        // 세 화질의 RESOLUTION/BANDWIDTH 메타 + 상대 경로
        assertThat(master).contains("RESOLUTION=1920x1080", "1080p/index.m3u8");
        assertThat(master).contains("RESOLUTION=1280x720", "720p/index.m3u8");
        assertThat(master).contains("RESOLUTION=640x360", "360p/index.m3u8");
        assertThat(master).contains("BANDWIDTH=4628000", "BANDWIDTH=3128000", "BANDWIDTH=928000");

        // variant 스트림이 정확히 3개
        assertThat(countOccurrences(master, "#EXT-X-STREAM-INF")).isEqualTo(3);
    }

    @Test
    @DisplayName("master.m3u8: 각 STREAM-INF 바로 다음 줄에 해당 화질의 상대 경로가 온다")
    void eachStreamInfFollowedByVariantUri() {
        String master = MasterPlaylistGenerator.generate();

        for (HlsRendition rendition : HlsRendition.values()) {
            String expected =
                    "RESOLUTION=" + rendition.getWidth() + "x" + rendition.getHeight() + "\n"
                            + rendition.variantPlaylistPath();
            assertThat(master).contains(expected);
        }
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) != -1) {
            count++;
            index += token.length();
        }
        return count;
    }
}
