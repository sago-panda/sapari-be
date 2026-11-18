package com.sapari.live.application.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.live.view.ThumbnailUploadView;

@DisplayName("업로드 허가 — toString 에 정책·서명이 나오지 않는다")
class PresignedPostTest {

    private static final Map<String, String> FIELDS = Map.of("policy", "SECRET-POLICY", "x-amz-signature", "SECRET-SIG");
    private static final Instant AT = Instant.parse("2026-11-09T00:00:00Z");

    @Test
    void presignedPostMasksFieldValues() {
        String text = new PresignedPost("https://bucket.s3", FIELDS, AT).toString();

        assertThat(text).contains("policy", "x-amz-signature").doesNotContain("SECRET-POLICY", "SECRET-SIG");
    }

    @Test
    void viewMasksFieldValues() {
        String text = new ThumbnailUploadView("https://bucket.s3", FIELDS, AT, "https://cdn/t").toString();

        assertThat(text).contains("policy").doesNotContain("SECRET-POLICY", "SECRET-SIG");
    }
}
