package com.sapari.product.infrastructure.html;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.product.application.port.HtmlSanitizer;

/**
 * {@link OwaspHtmlSanitizer} 단위 테스트. 실행 가능한 마크업(script·이벤트 핸들러·javascript: URL)이 제거되고,
 * 허용된 서식·텍스트는 보존되는지 고정한다(상품 상세 설명 저장형 XSS 차단).
 */
@DisplayName("HTML 정제 어댑터 테스트")
class OwaspHtmlSanitizerTest {

    private final HtmlSanitizer sanitizer = new OwaspHtmlSanitizer();

    /**
     * null 입력은 null로 통과한다(선택 필드).
     */
    @Test
    @DisplayName("null은 null로 반환한다")
    void nullPassesThrough() {
        assertThat(sanitizer.sanitize(null)).isNull();
    }

    /**
     * script 태그는 제거된다.
     */
    @Test
    @DisplayName("script 태그를 제거한다")
    void removesScriptTag() {
        String result = sanitizer.sanitize("<p>hi</p><script>alert(1)</script>");

        assertThat(result).doesNotContain("<script").doesNotContain("alert(1)");
    }

    /**
     * 이벤트 핸들러 속성(onerror 등)은 제거된다.
     */
    @Test
    @DisplayName("onerror 등 이벤트 핸들러 속성을 제거한다")
    void removesEventHandlerAttribute() {
        String result = sanitizer.sanitize("<img src=\"x\" onerror=\"alert(1)\">");

        assertThat(result).doesNotContain("onerror").doesNotContain("alert(1)");
    }

    /**
     * javascript: 스킴 URL은 제거된다.
     */
    @Test
    @DisplayName("javascript: 스킴 링크를 제거한다")
    void removesJavascriptScheme() {
        String result = sanitizer.sanitize("<a href=\"javascript:alert(1)\">click</a>");

        assertThat(result).doesNotContain("javascript:").doesNotContain("alert(1)");
    }

    /**
     * 허용된 서식 태그와 텍스트(한글 포함)는 보존된다.
     */
    @Test
    @DisplayName("허용 서식 태그와 텍스트는 보존한다")
    void keepsSafeFormatting() {
        String result = sanitizer.sanitize("<p>안녕 <strong>세상</strong></p>");

        assertThat(result)
                .contains("<strong>")
                .contains("세상")
                .contains("안녕");
    }
}
