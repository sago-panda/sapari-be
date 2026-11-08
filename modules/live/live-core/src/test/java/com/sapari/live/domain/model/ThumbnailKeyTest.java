package com.sapari.live.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ThumbnailKey — 업로드 경로와 검증 완료 경로는 영역 접두사만 다르다")
class ThumbnailKeyTest {

    private static final UUID SELLER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OBJECT = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    void keys() {
        ThumbnailKey key = new ThumbnailKey(SELLER, OBJECT);

        assertThat(key.uploadKey()).isEqualTo("uploads/thumbnails/" + SELLER + "/" + OBJECT);
        assertThat(key.publicKey()).isEqualTo("thumbnails/" + SELLER + "/" + OBJECT);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new ThumbnailKey(null, OBJECT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ThumbnailKey(SELLER, null)).isInstanceOf(NullPointerException.class);
    }
}
