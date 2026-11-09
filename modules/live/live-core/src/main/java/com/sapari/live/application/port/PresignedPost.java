package com.sapari.live.application.port;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 브라우저가 저장소로 직접 올리는 POST 업로드 허가 — {@code url} 로 {@code fields} 전부와 마지막에 파일을 보낸다.
 * {@code fields} 는 넣은 순서를 지킨다(정책·서명 포함).
 */
public record PresignedPost(String url, Map<String, String> fields, Instant expiresAt) {

    public PresignedPost {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** 정책·서명은 업로드 허가 그 자체라 어떤 로그에도 남기지 않는다 — 필드 이름만 보인다. */
    @Override
    public String toString() {
        return "PresignedPost[url=" + url + ", fields=" + fields.keySet() + ", expiresAt=" + expiresAt + "]";
    }
}
