package com.sapari.product.application.port;

/**
 * 사용자 작성 HTML(상품 상세 설명 등)을 안전한 HTML로 정제하는 아웃바운드 포트.
 *
 * <p>상세 설명은 공개 조회로 익명 사용자에게 그대로 렌더링되므로, 저장 전에 허용 태그만 통과시켜
 * 저장형 XSS를 차단한다(심층 방어 — FE 정제에 의존하지 않는다). 외부 정제 라이브러리는 어댑터에 격리한다.
 */
public interface HtmlSanitizer {

    /**
     * HTML 문자열에서 허용되지 않은 태그·속성·스크립트를 제거한 안전한 HTML을 돌려준다.
     *
     * @param html 정제할 원본 HTML, {@code null}이면 {@code null} 반환
     * @return 허용 목록만 남은 안전한 HTML(입력이 null이면 null)
     */
    String sanitize(String html);
}
