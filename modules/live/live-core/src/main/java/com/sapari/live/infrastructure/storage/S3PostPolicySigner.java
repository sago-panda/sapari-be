package com.sapari.live.infrastructure.storage;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sapari.live.application.port.PresignedPost;
import com.sapari.live.application.port.ThumbnailUploadSigner;

/**
 * S3 브라우저 POST 업로드용 SigV4 정책 서명기 — AWS SDK 없이 JDK HMAC 만 쓴다.
 *
 * <p><b>왜 직접 서명하나</b>: AWS SDK for Java v2 의 {@code S3Presigner} 는 GET·PUT 같은 요청 단위 presign 만 있고
 * POST 정책을 만들지 않는다. 크기 상한을 저장소가 업로드 시점에 거부하게 하려면 POST 정책의
 * {@code content-length-range} 가 필요하다(PUT 에는 그 조건이 없다). 정확성은 AWS 문서의 공개 벡터로 고정한다
 * ({@code S3PostPolicySignerTest}).
 *
 * <p><b>한계 — 정적 키만 지원</b>: 임시 자격증명({@code x-amz-security-token})을 지원하지 않는다. IRSA(기본 체인)로
 * 넘어가면 STS 임시 키를 얻어 와야 하는데 SDK 없이는 그 경로가 없다 — 그때 이 서명기도 함께 바꾼다
 * (자격증명 공급자 + 정책·폼에 security-token 조건 추가). infra/AGENTS.md "Egress credentials" 참조.
 *
 * <p>로그를 남기지 않는다 — 정책 원문·서명·키는 어떤 경로로도 기록되지 않아야 한다.
 */
public class S3PostPolicySigner implements ThumbnailUploadSigner {

    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final Duration EXPIRY = Duration.ofMinutes(10);

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String HMAC = "HmacSHA256";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter EXPIRATION =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;

    public S3PostPolicySigner(String bucket, String region, String accessKey, String secretKey) {
        this.bucket = bucket;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    @Override
    public PresignedPost sign(String key, String contentType, Instant now) {
        String date = DATE.format(now);
        String amzDate = AMZ_DATE.format(now);
        String credential = accessKey + "/" + date + "/" + region + "/s3/aws4_request";
        Instant expiresAt = now.plus(EXPIRY);

        String policy = "{\"expiration\":" + json(EXPIRATION.format(expiresAt)) + ",\"conditions\":["
                + "{\"bucket\":" + json(bucket) + "},"
                + "{\"key\":" + json(key) + "},"
                + "{\"Content-Type\":" + json(contentType) + "},"
                + "[\"content-length-range\",1," + MAX_BYTES + "],"
                + "{\"x-amz-algorithm\":\"" + ALGORITHM + "\"},"
                + "{\"x-amz-credential\":" + json(credential) + "},"
                + "{\"x-amz-date\":" + json(amzDate) + "}]}";
        String encodedPolicy = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", key);
        fields.put("Content-Type", contentType);
        fields.put("x-amz-algorithm", ALGORITHM);
        fields.put("x-amz-credential", credential);
        fields.put("x-amz-date", amzDate);
        fields.put("policy", encodedPolicy);
        fields.put("x-amz-signature", signature(secretKey, date, region, encodedPolicy));

        return new PresignedPost("https://" + bucket + ".s3." + region + ".amazonaws.com/", fields, expiresAt);
    }

    /** SigV4 서명: 서명 키 = HMAC 체인(date → region → s3 → aws4_request), 서명 = hex(HMAC(키, base64 정책)). */
    static String signature(String secretKey, String date, String region, String stringToSign) {
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        return HexFormat.of().formatHex(hmac(key, stringToSign));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // JDK 기본 제공 알고리즘이 없는 건 런타임 결함이다 — 서버 오류로 드러낸다.
            throw new IllegalStateException("HmacSHA256 서명을 만들 수 없습니다.", e);
        }
    }

    /** 정책 JSON 문자열 리터럴. 값은 서버가 만든 것이지만 따옴표·역슬래시·제어문자를 이스케이프해 정책 구조를 지킨다. */
    private static String json(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    @Override
    public String toString() {
        return "S3PostPolicySigner[bucket=" + bucket + ", region=" + region + ", accessKey=***, secretKey=***]";
    }
}
