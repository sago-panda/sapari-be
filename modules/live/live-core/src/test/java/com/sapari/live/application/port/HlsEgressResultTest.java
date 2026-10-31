package com.sapari.live.application.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HlsEgressResultTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void successfulStartRequiresBothPlaybackUrls(String absent) {
        assertThatThrownBy(() -> new HlsEgressResult("egress", absent, "https://cdn/playlist.m3u8"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HlsEgressResult("egress", "https://cdn/index.m3u8", absent))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesDistinctPlaybackUrls() {
        HlsEgressResult result = new HlsEgressResult("egress", "https://cdn/index.m3u8?v=1",
                "https://cdn/playlist.m3u8?v=1");
        assertThat(result.hlsUrl()).isEqualTo("https://cdn/index.m3u8?v=1");
        assertThat(result.hlsArchiveUrl()).isEqualTo("https://cdn/playlist.m3u8?v=1");
    }
}
