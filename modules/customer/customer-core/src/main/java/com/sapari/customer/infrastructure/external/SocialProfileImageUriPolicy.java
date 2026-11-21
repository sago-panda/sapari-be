package com.sapari.customer.infrastructure.external;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 소셜 프로필 이미지 요청 전에 URI 구조를 검사하는 SSRF 정책이다.
 * HTTP/HTTPS 표준 포트의 도메인 URL만 허용하고 직접 IP 목적지는 DNS 정책을 우회하지 못하게 거부한다.
 */
public class SocialProfileImageUriPolicy {

    private static final int MAX_URI_LENGTH = 2048;
    private static final Pattern DECIMAL_HOST = Pattern.compile("[0-9]+");
    private static final Pattern HEX_HOST = Pattern.compile("0[xX][0-9a-fA-F]+");
    private static final Pattern DOTTED_NUMERIC_HOST = Pattern.compile("[0-9]+(?:\\.[0-9]+)+");

    /**
     * 비정상적으로 긴 입력을 조기에 거부하고 HTTP/HTTPS 도메인과 각 표준 포트만 허용한다.
     * 이 검사는 DNS 조회 없이 수행하며, 통과한 host의 실제 주소 안전성은 연결 계층 resolver가 검사한다.
     */
    public boolean isAllowed(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.toString().length() > MAX_URI_LENGTH) {
            return false;
        }

        String scheme = normalizedScheme(uri);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return false;
        }
        if (uri.getRawUserInfo() != null || uri.getHost() == null || uri.getHost().isBlank()) {
            return false;
        }
        if (!hasStandardPort(uri, scheme)) {
            return false;
        }
        return !isIpLiteralOrNumericForm(uri.getHost());
    }

    /** URI scheme을 로케일 영향 없이 정책 비교용 소문자로 바꾼다. */
    private String normalizedScheme(URI uri) {
        return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    }

    /** HTTP는 미지정/80, HTTPS는 미지정/443 포트만 허용해 내부 서비스 포트 접근을 막는다. */
    private boolean hasStandardPort(URI uri, String scheme) {
        int port = uri.getPort();
        return port == -1 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
    }

    /**
     * IPv4·IPv6 literal과 decimal/hex/octal 계열 숫자형 host를 문자열로 판정한다.
     * IP 판정을 위한 DNS 조회는 검사와 실제 연결 사이 주소가 달라지는 간극을 만들 수 있어 사용하지 않는다.
     */
    private boolean isIpLiteralOrNumericForm(String host) {
        String normalizedHost = stripIpv6Brackets(host);
        if (normalizedHost.indexOf(':') >= 0) {
            return true;
        }
        return DECIMAL_HOST.matcher(normalizedHost).matches()
                || HEX_HOST.matcher(normalizedHost).matches()
                || DOTTED_NUMERIC_HOST.matcher(normalizedHost).matches();
    }

    /** IPv6 literal의 대괄호 유무와 무관하게 주소 본문을 비교할 수 있게 정규화한다. */
    private String stripIpv6Brackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
