package com.sapari.productapp.bulk;

import java.util.List;

/**
 * 상품 대량 등록 엑셀 템플릿 규격. 시트 이름·헤더(1행)·데이터 시작 행을 한곳에 둔다.
 *
 * <p>1행은 헤더, 2행은 작성 설명, 3행부터 데이터다. {@code ※}로 시작하는 행은 안내 문구로 보고 건너뛴다.
 * 헤더 문구가 템플릿과 다르면 열이 밀린 파일로 보고 파일 전체를 거절한다(잘못된 열에 값이 들어가는 사고 방지).
 */
final class ProductWorkbookTemplate {

    static final String PRODUCT_SHEET = "1.상품";
    static final String OPTION_SHEET = "2.옵션";
    static final String OVERRIDE_SHEET = "3.조합오버라이드";

    /** 시트 표시 순서(오류 정렬 기준). */
    static final List<String> SHEETS = List.of(PRODUCT_SHEET, OPTION_SHEET, OVERRIDE_SHEET);

    static final List<String> PRODUCT_HEADERS =
            List.of("상품코드*", "카테고리ID*", "상품명*", "기본가격*", "기본재고*", "태그", "배송정책ID", "추가배송비", "상세설명(HTML)");
    static final List<String> OPTION_HEADERS =
            List.of("상품코드*", "옵션타입명*", "타입순서*", "옵션값*", "값순서*", "가격가산액", "메타데이터(JSON)");
    static final List<String> OVERRIDE_HEADERS =
            List.of("상품코드*", "옵션값조합*", "판매가", "재고", "SKU", "제외여부");

    /** 헤더 행 번호(1-based). */
    static final int HEADER_ROW = 1;
    /** 첫 데이터 행 번호(1-based). 2행은 작성 설명. */
    static final int FIRST_DATA_ROW = 3;
    /** 안내 문구 행 표식. */
    static final String NOTE_PREFIX = "※";
    /** 태그·옵션값조합 구분자. */
    static final String SEPARATOR = "|";

    /** 옵션타입명 길이 상한 — {@code OptionTypeRequest.name @Size(max = 50)}와 같다. */
    static final int MAX_TYPE_NAME_CHARS = 50;
    /** 옵션값 길이 상한 — {@code OptionValueRequest.value @Size(max = 100)}와 같다. */
    static final int MAX_VALUE_CHARS = 100;
    /** 상품당 옵션 타입 상한 — {@code CreateProductRequest.optionTypes @Size(max = 10)}·{@code CombinationGenerator.MAX_OPTION_TYPES}와 같다. */
    static final int MAX_OPTION_TYPES = 10;
    /** 옵션 타입당 옵션값 상한 — {@code OptionTypeRequest.values @Size(max = 100)}와 같다. */
    static final int MAX_VALUES_PER_TYPE = 100;
    /**
     * 상품당 [3.조합오버라이드] 행 상한 — 오버라이드·제외는 서로 다른 조합을 가리켜야 하므로 생성 조합 상한(500)을 넘을 수 없다.
     */
    static final int MAX_COMBINATION_ROWS = 500;
    /** 템플릿이 쓰는 열 수(가장 넓은 [1.상품] 기준). 그 밖의 열은 읽지 않는다. */
    static final int MAX_COLUMNS = PRODUCT_HEADERS.size();
    /** 오류 메시지에 되울리는 셀 값 길이 상한. */
    static final int MAX_ECHO_CHARS = 50;

    // 1.상품 열
    static final int P_CODE = 0;
    static final int P_CATEGORY = 1;
    static final int P_NAME = 2;
    static final int P_BASE_PRICE = 3;
    static final int P_DEFAULT_STOCK = 4;
    static final int P_TAGS = 5;
    static final int P_SHIPPING_POLICY = 6;
    static final int P_SHIPPING_FEE = 7;
    static final int P_DESCRIPTION = 8;

    // 2.옵션 열
    static final int O_CODE = 0;
    static final int O_TYPE_NAME = 1;
    static final int O_TYPE_ORDER = 2;
    static final int O_VALUE = 3;
    static final int O_VALUE_ORDER = 4;
    static final int O_PRICE_DELTA = 5;
    static final int O_METADATA = 6;

    // 3.조합오버라이드 열
    static final int C_CODE = 0;
    static final int C_VALUES = 1;
    static final int C_PRICE = 2;
    static final int C_STOCK = 3;
    static final int C_SKU = 4;
    static final int C_EXCLUDED = 5;

    private ProductWorkbookTemplate() {
    }

    /**
     * 0-based 열 번호를 엑셀 열 문자로 바꾼다(0 → A). 템플릿은 26열 미만이라 한 글자로 충분하다.
     */
    static String columnLetter(int columnIndex) {
        return String.valueOf((char) ('A' + columnIndex));
    }

    /**
     * 오류 메시지에 넣을 셀 값을 {@link #MAX_ECHO_CHARS}자로 자른다(긴 값이 오류마다 복제돼 응답·메모리를 키우지 않도록).
     */
    static String echo(String value) {
        if (value == null || value.length() <= MAX_ECHO_CHARS) {
            return value;
        }
        return value.substring(0, MAX_ECHO_CHARS) + "…";
    }
}
