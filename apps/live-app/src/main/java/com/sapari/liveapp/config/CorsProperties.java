package com.sapari.liveapp.config;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 브라우저 호출을 허용할 프론트 오리진(CORS_ALLOWED_ORIGINS, 쉼표 구분). 비어 있으면 CORS 를 켜지 않는다.
 * 정확한 값만 받고 모양이 틀리면 기동 실패다 — 틀린 값은 어떤 요청과도 맞지 않아 브라우저에서만 조용히 막히기 때문이다.
 *
 * @param allowedOrigins scheme://host[:port]. 경로 · 끝의 '/' · 와일드카드 · 기본 포트 없이
 */
@ConfigurationProperties("cors")
public record CorsProperties(List<String> allowedOrigins) {

    private static final Pattern ORIGIN = Pattern.compile("(https?)://[A-Za-z0-9.-]+(?::([1-9]\\d{0,4}))?");
    private static final int MAX_PORT = 65_535;

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        for (String origin : allowedOrigins) {
            if (!isBrowserOrigin(origin)) {
                throw new IllegalArgumentException("cors.allowed-origins 는 브라우저가 보내는 모양(scheme://host[:port])이어야 한다"
                        + "(경로 · 끝 '/' · 와일드카드 · 기본 포트 · 범위 밖 포트 불가): " + origin);
            }
        }
    }

    /** 브라우저는 기본 포트(https 443 · http 80)를 빼고 보낸다 — 붙여 적으면 어떤 요청과도 맞지 않는다. */
    private static boolean isBrowserOrigin(String origin) {
        Matcher matcher = ORIGIN.matcher(origin);
        if (!matcher.matches()) {
            return false;
        }
        if (matcher.group(2) == null) {
            return true;
        }
        int port = Integer.parseInt(matcher.group(2));
        int defaultPort = matcher.group(1).equals("https") ? 443 : 80;
        return port <= MAX_PORT && port != defaultPort;
    }
}
