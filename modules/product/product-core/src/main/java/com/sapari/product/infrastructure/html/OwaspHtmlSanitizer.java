package com.sapari.product.infrastructure.html;

import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.stereotype.Component;

import com.sapari.product.application.port.HtmlSanitizer;

/**
 * OWASP Java HTML Sanitizer 기반 {@link HtmlSanitizer} 어댑터.
 *
 * <p>허용 목록(allowlist) 정책으로 서식·블록·링크·이미지·표만 통과시키고, {@code <script>}·이벤트 핸들러
 * ({@code onerror} 등)·{@code javascript:} URL 등 실행 가능한 마크업은 제거한다. style 속성은 CSS 기반
 * XSS 표면을 줄이기 위해 의도적으로 허용하지 않는다. 정책 인스턴스는 불변·스레드 안전이라 정적 공유한다.
 */
@Component
public class OwaspHtmlSanitizer implements HtmlSanitizer {

    private static final PolicyFactory POLICY = Sanitizers.FORMATTING
            .and(Sanitizers.BLOCKS)
            .and(Sanitizers.LINKS)
            .and(Sanitizers.IMAGES)
            .and(Sanitizers.TABLES);

    /**
     * 허용 목록 정책으로 HTML을 정제한다.
     *
     * @param html 정제할 원본 HTML, {@code null}이면 {@code null} 반환
     * @return 안전한 HTML(입력이 null이면 null)
     */
    @Override
    public String sanitize(String html) {
        if (html == null) {
            return null;
        }
        return POLICY.sanitize(html);
    }
}
