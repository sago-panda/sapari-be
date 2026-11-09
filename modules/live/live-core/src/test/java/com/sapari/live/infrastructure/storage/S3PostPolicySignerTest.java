package com.sapari.live.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.live.application.port.PresignedPost;

@DisplayName("S3PostPolicySigner — SigV4 POST 정책 서명")
class S3PostPolicySignerTest {

    /**
     * AWS 공개 테스트 벡터 — "Example: Browser-Based Upload using HTTP POST (Using AWS Signature Version 4)"
     * (Amazon S3 API Reference, sigv4-post-example). 예제 자격증명은 AWS 문서의 가짜 키다.
     * 정책 본문은 CRLF 를 포함한 원문을 base64 한 문서 값 그대로다.
     */
    private static final String AWS_EXAMPLE_SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String AWS_EXAMPLE_POLICY_B64 =
            "eyAiZXhwaXJhdGlvbiI6ICIyMDE1LTEyLTMwVDEyOjAwOjAwLjAwMFoiLA0KICAiY29uZGl0aW9ucyI6IFsNCiAgICB7ImJ1Y2tldCI6ICJzaWd2NGV4YW1wbGVidWNrZXQifSwNCiAgICBbInN0YXJ0cy13aXRoIiwgIiRrZXkiLCAidXNlci91c2VyMS8iXSwNCiAgICB7ImFjbCI6ICJwdWJsaWMtcmVhZCJ9LA0KICAgIHsic3VjY2Vzc19hY3Rpb25fcmVkaXJlY3QiOiAiaHR0cDovL3NpZ3Y0ZXhhbXBsZWJ1Y2tldC5zMy5hbWF6b25hd3MuY29tL3N1Y2Nlc3NmdWxfdXBsb2FkLmh0bWwifSwNCiAgICBbInN0YXJ0cy13aXRoIiwgIiRDb250ZW50LVR5cGUiLCAiaW1hZ2UvIl0sDQogICAgeyJ4LWFtei1tZXRhLXV1aWQiOiAiMTQzNjUxMjM2NTEyNzQifSwNCiAgICB7IngtYW16LXNlcnZlci1zaWRlLWVuY3J5cHRpb24iOiAiQUVTMjU2In0sDQogICAgWyJzdGFydHMtd2l0aCIsICIkeC1hbXotbWV0YS10YWciLCAiIl0sDQoNCiAgICB7IngtYW16LWNyZWRlbnRpYWwiOiAiQUtJQUlPU0ZPRE5ON0VYQU1QTEUvMjAxNTEyMjkvdXMtZWFzdC0xL3MzL2F3czRfcmVxdWVzdCJ9LA0KICAgIHsieC1hbXotYWxnb3JpdGhtIjogIkFXUzQtSE1BQy1TSEEyNTYifSwNCiAgICB7IngtYW16LWRhdGUiOiAiMjAxNTEyMjlUMDAwMDAwWiIgfQ0KICBdDQp9";
    private static final String AWS_EXAMPLE_SIGNATURE =
            "8afdbf4008c03f22c2cd3cdb72e4afbb1f6a588f3255ac628749a66d7f09699e";

    private static final Instant NOW = Instant.parse("2026-11-08T03:04:05.678Z");
    private static final String KEY = "uploads/thumbnails/33333333-3333-3333-3333-333333333333/44444444-4444-4444-4444-444444444444";

    private final S3PostPolicySigner signer =
            new S3PostPolicySigner("test-bucket", "ap-northeast-2", "AKIDEXAMPLE", "TESTSECRETVALUE");

    @Test
    @DisplayName("AWS 문서 벡터와 같은 서명을 낸다")
    void matchesAwsPublishedVector() {
        String signature = S3PostPolicySigner.signature(AWS_EXAMPLE_SECRET, "20151229", "us-east-1", AWS_EXAMPLE_POLICY_B64);

        assertThat(signature).isEqualTo(AWS_EXAMPLE_SIGNATURE);
    }

    @Test
    @DisplayName("정책은 버킷·정확한 key·정확한 Content-Type·크기 범위·10분 만료를 건다")
    void policyConditions() {
        PresignedPost post = signer.sign(KEY, "image/png", NOW);

        String policy = new String(Base64.getDecoder().decode(post.fields().get("policy")), StandardCharsets.UTF_8);
        assertThat(policy).isEqualTo("{\"expiration\":\"2026-11-08T03:14:05.678Z\",\"conditions\":["
                + "{\"bucket\":\"test-bucket\"},"
                + "{\"key\":\"" + KEY + "\"},"
                + "{\"Content-Type\":\"image/png\"},"
                + "[\"content-length-range\",1,5242880],"
                + "{\"x-amz-algorithm\":\"AWS4-HMAC-SHA256\"},"
                + "{\"x-amz-credential\":\"AKIDEXAMPLE/20261108/ap-northeast-2/s3/aws4_request\"},"
                + "{\"x-amz-date\":\"20261108T030405Z\"}]}");
        assertThat(post.expiresAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    @DisplayName("폼 필드와 업로드 주소 — 서명은 정책 base64 에 대한 SigV4")
    void fieldsAndUrl() {
        PresignedPost post = signer.sign(KEY, "image/webp", NOW);

        assertThat(post.url()).isEqualTo("https://test-bucket.s3.ap-northeast-2.amazonaws.com/");
        assertThat(post.fields()).containsOnlyKeys("key", "Content-Type", "x-amz-algorithm",
                "x-amz-credential", "x-amz-date", "policy", "x-amz-signature");
        assertThat(post.fields()).containsEntry("key", KEY)
                .containsEntry("Content-Type", "image/webp")
                .containsEntry("x-amz-algorithm", "AWS4-HMAC-SHA256")
                .containsEntry("x-amz-credential", "AKIDEXAMPLE/20261108/ap-northeast-2/s3/aws4_request")
                .containsEntry("x-amz-date", "20261108T030405Z");
        assertThat(post.fields().get("x-amz-signature")).isEqualTo(
                S3PostPolicySigner.signature("TESTSECRETVALUE", "20261108", "ap-northeast-2", post.fields().get("policy")));
    }

    @Test
    @DisplayName("toString 에 비밀키가 나오지 않는다")
    void toStringMasksSecret() {
        assertThat(signer.toString()).doesNotContain("TESTSECRETVALUE").doesNotContain("AKIDEXAMPLE");
    }
}
