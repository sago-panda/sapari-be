package com.sapari.global.validator;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.regex.Pattern;

/**
 * 프로젝트 내 사용되는 URL 검증
 * HTTP, HLS, S3, CDN URL이 맞는지 검증한다.
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public final class UrlValidator {

    private static final Pattern HTTP_URL = Pattern.compile("^https?://\\S+$");
    private static final Pattern HLS_PLAYLIST = Pattern.compile("^https?://\\S+\\.m3u8$");
    private static final Pattern S3_URL = Pattern.compile("^https?://[\\w.-]+\\.s3[.-][\\w.-]+\\.amazonaws\\.com/\\S+$");
    private static final Pattern CDN_URL = Pattern.compile("^https?://\\S+\\.cloudfront\\.net/\\S+$");

    public static void validateHttpUrl(String url) {
        validate(url, HTTP_URL, "유효하지 않은 URL 형식입니다: " + url);
    }

    public static void validateHlsUrl(String url) {
        validate(url, HLS_PLAYLIST, "유효하지 않은 HLS URL 형식입니다: " + url);
    }

    public static void validateS3Url(String url) {
        validate(url, S3_URL, "유효하지 않은 S3 URL 형식입니다: " + url);
    }

    public static void validateCdnUrl(String url) {
        validate(url, CDN_URL, "유효하지 않은 CDN URL 형식입니다: " + url);
    }

    private static void validate(String url, Pattern pattern, String message) {
        if (url == null || !pattern.matcher(url).matches()) {
            throw new IllegalArgumentException(message);
        }
    }
}
