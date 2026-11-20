package com.sapari.productapp.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.sapari.product.domain.exception.InvalidBulkUploadException;
import com.sapari.productapp.controller.product.dto.request.CombinationOverrideRequest;
import com.sapari.productapp.controller.product.dto.request.CreateProductRequest;
import com.sapari.productapp.controller.product.dto.request.ExclusionRuleRequest;
import com.sapari.productapp.controller.product.dto.request.OptionTypeRequest;
import com.sapari.productapp.controller.product.dto.request.OptionValueRequest;
import com.sapari.productapp.controller.product.dto.response.BulkProductUploadResponse.UploadError;

@DisplayName("ProductWorkbookParser")
class ProductWorkbookParserTest {

    private static final String POLICY_ID = "00000000-0000-0000-0000-0000000000c1";

    /** 비어 있지 않은 셀만 담아 행을 만든다(null은 빈 셀). */
    private static WorkbookRow row(int rowNumber, String... cells) {
        Map<Integer, String> map = new HashMap<>();
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != null) {
                map.put(i, cells[i]);
            }
        }
        return new WorkbookRow(rowNumber, map);
    }

    /** 헤더(1행)·설명(2행)이 붙은 시트 행 목록. */
    private static List<WorkbookRow> sheet(List<String> headers, WorkbookRow... dataRows) {
        List<WorkbookRow> rows = new ArrayList<>();
        rows.add(row(1, headers.toArray(String[]::new)));
        rows.add(row(2, "설명"));
        rows.addAll(List.of(dataRows));
        return rows;
    }

    private static Map<String, List<WorkbookRow>> workbook(
            List<WorkbookRow> products, List<WorkbookRow> options, List<WorkbookRow> overrides) {
        return Map.of(
                ProductWorkbookTemplate.PRODUCT_SHEET, products,
                ProductWorkbookTemplate.OPTION_SHEET, options,
                ProductWorkbookTemplate.OVERRIDE_SHEET, overrides);
    }

    private static List<WorkbookRow> products(WorkbookRow... rows) {
        return sheet(ProductWorkbookTemplate.PRODUCT_HEADERS, rows);
    }

    private static List<WorkbookRow> options(WorkbookRow... rows) {
        return sheet(ProductWorkbookTemplate.OPTION_HEADERS, rows);
    }

    private static List<WorkbookRow> overrides(WorkbookRow... rows) {
        return sheet(ProductWorkbookTemplate.OVERRIDE_HEADERS, rows);
    }

    /** 템플릿 예시와 같은 P-001 상품 행(천 단위 쉼표 가격, 공백·빈 조각이 섞인 태그). */
    private static WorkbookRow teeRow(int rowNumber) {
        return row(rowNumber, "P-001", "1001", "베이직 코튼 티셔츠", "19,900", "100", "여름| 코튼 ||반팔",
                POLICY_ID, "2500", "<p>설명</p>");
    }

    private static List<WorkbookRow> teeOptions() {
        return options(
                row(3, "P-001", "색상", "1", "화이트", "1", "0", "{\"hex\":\"#FFFFFF\"}"),
                row(4, "P-001", "사이즈", "2", "S", "1", null),
                row(5, "P-001", "색상", "1", "차콜", "2", "1000"),
                row(6, "P-001", "사이즈", "2", "L", "2", "2000"));
    }

    private static void assertSingleFormatError(ProductWorkbookParser.Result result, String sheet, int row, String column) {
        assertThat(result.errors().total()).isEqualTo(1);
        assertThat(result.errors().kept()).singleElement().satisfies(error -> {
            assertThat(error.sheet()).isEqualTo(sheet);
            assertThat(error.row()).isEqualTo(row);
            assertThat(error.column()).isEqualTo(column);
            assertThat(error.code()).isEqualTo("PRODUCT-012");
        });
    }

    @Nested
    @DisplayName("정상 조립")
    class Assembles {

        @Test
        @DisplayName("상품 행의 모든 열을 단건 등록 요청 필드로 옮긴다 (천 단위 쉼표 정수, 태그 '|' 분리·공백 조각 제거, 설명 원문 보존)")
        void mapsProductColumns() {
            ProductWorkbookParser.Result result =
                    ProductWorkbookParser.parse(workbook(products(teeRow(3)), options(), overrides()));

            assertThat(result.errors().isEmpty()).isTrue();
            assertThat(result.drafts()).singleElement().satisfies(draft -> {
                assertThat(draft.productCode()).isEqualTo("P-001");
                assertThat(draft.productRow()).isEqualTo(3);
                CreateProductRequest request = draft.request();
                assertThat(request.categoryId()).isEqualTo(1001L);
                assertThat(request.name()).isEqualTo("베이직 코튼 티셔츠");
                assertThat(request.basePrice()).isEqualTo(19_900);
                assertThat(request.defaultStock()).isEqualTo(100);
                assertThat(request.tags()).containsExactly("여름", "코튼", "반팔");
                assertThat(request.shippingPolicyId()).isEqualTo(UUID.fromString(POLICY_ID));
                assertThat(request.additionalShippingFee()).isEqualTo(2_500);
                assertThat(request.description()).isEqualTo("<p>설명</p>");
                assertThat(request.optionTypes()).isEmpty();
                assertThat(request.surchargeRules()).isEmpty();
                assertThat(request.exclusionRules()).isEmpty();
                assertThat(request.combinations()).isEmpty();
            });
        }

        @Test
        @DisplayName("같은 상품코드+옵션타입명 행을 한 타입으로 묶고(떨어져 있어도), ref는 '타입명=옵션값', 가산액 빈칸은 0이다")
        void groupsOptionRowsIntoTypes() {
            ProductWorkbookParser.Result result =
                    ProductWorkbookParser.parse(workbook(products(teeRow(3)), teeOptions(), overrides()));

            assertThat(result.errors().isEmpty()).isTrue();
            List<OptionTypeRequest> types = result.drafts().get(0).request().optionTypes();
            assertThat(types).extracting(OptionTypeRequest::name, OptionTypeRequest::sortOrder)
                    .containsExactly(tuple("색상", (short) 1), tuple("사이즈", (short) 2));
            assertThat(types.get(0).values())
                    .extracting(OptionValueRequest::value, OptionValueRequest::priceDelta,
                            OptionValueRequest::sortOrder, OptionValueRequest::metadata, OptionValueRequest::ref)
                    .containsExactly(
                            tuple("화이트", 0, (short) 1, "{\"hex\":\"#FFFFFF\"}", "색상=화이트"),
                            tuple("차콜", 1_000, (short) 2, null, "색상=차콜"));
            assertThat(types.get(1).values())
                    .extracting(OptionValueRequest::value, OptionValueRequest::priceDelta, OptionValueRequest::ref)
                    .containsExactly(tuple("S", 0, "사이즈=S"), tuple("L", 2_000, "사이즈=L"));
        }

        @Test
        @DisplayName("옵션값조합을 타입순서대로 ref로 해석해 제외여부 Y는 제외 룰, 그 외는 오버라이드로 만든다 (타입 입력 순서와 무관)")
        void resolvesOverridesByTypeOrder() {
            // 사이즈(타입순서 2)를 먼저 적어도 조합 표기는 타입순서(색상→사이즈)를 따른다
            List<WorkbookRow> reversedOptions = options(
                    row(3, "P-001", "사이즈", "2", "S", "1"),
                    row(4, "P-001", "색상", "1", "차콜", "1"),
                    row(5, "P-001", "사이즈", "2", "L", "2"));

            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(products(teeRow(3)), reversedOptions,
                    overrides(
                            row(3, "P-001", "차콜|L", null, "30", "TS-CC-L"),
                            row(4, "P-001", "차콜 | S", "18,900", null, null, "n"),
                            row(5, "P-001", "차콜|S", null, null, null, "Y"),
                            row(6, "※ 안내 문구는 건너뛴다"))));

            assertThat(result.errors().isEmpty()).isTrue();
            CreateProductRequest request = result.drafts().get(0).request();
            assertThat(request.combinations()).containsExactly(
                    new CombinationOverrideRequest(List.of("색상=차콜", "사이즈=L"), null, null, 30, "TS-CC-L"),
                    new CombinationOverrideRequest(List.of("색상=차콜", "사이즈=S"), 18_900, null, null, null));
            assertThat(request.exclusionRules()).containsExactly(
                    new ExclusionRuleRequest(List.of("색상=차콜", "사이즈=S"), null));
        }

        @Test
        @DisplayName("빈 행과 1~2행(헤더·설명)은 데이터로 읽지 않고, 상품은 파일 순서를 유지한다")
        void skipsBlankRowsAndKeepsOrder() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(
                    products(
                            row(3, "P-B", "1001", "B", "1000", "1"),
                            row(4, " ", " "),
                            row(5, "P-A", "1001", "A", "1000", "1")),
                    options(), overrides()));

            assertThat(result.errors().isEmpty()).isTrue();
            assertThat(result.drafts()).extracting(ProductDraft::productCode).containsExactly("P-B", "P-A");
        }

        @Test
        @DisplayName("요청 프로퍼티 경로를 원본 셀로 되돌린다 (정확한 경로 → 상위 경로 → 상품 행)")
        void locatesCellsForPropertyPaths() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(products(teeRow(3)), teeOptions(),
                    overrides(row(7, "P-001", "차콜|L", "-1", null, "SKU"))));

            ProductDraft draft = result.drafts().get(0);
            assertThat(draft.locate("name")).isEqualTo(new CellLocation("1.상품", 3, 2));
            assertThat(draft.locate("tags[1].<list element>")).isEqualTo(new CellLocation("1.상품", 3, 5));
            assertThat(draft.locate("optionTypes[0].name")).isEqualTo(new CellLocation("2.옵션", 3, 1));
            assertThat(draft.locate("optionTypes[0].values[1].metadata")).isEqualTo(new CellLocation("2.옵션", 5, 6));
            assertThat(draft.locate("optionTypes[1].values[0].value")).isEqualTo(new CellLocation("2.옵션", 4, 3));
            assertThat(draft.locate("combinations[0].price")).isEqualTo(new CellLocation("3.조합오버라이드", 7, 2));
            assertThat(draft.locate("combinations[0].valueRefs[0].<list element>"))
                    .isEqualTo(new CellLocation("3.조합오버라이드", 7, 1));
            assertThat(draft.locate("optionTypes")).isEqualTo(new CellLocation("1.상품", 3, 0));
        }
    }

    @Nested
    @DisplayName("형식 오류 (PRODUCT-012, 셀 위치) — 해당 상품은 요청을 만들지 않는다")
    class FormatErrors {

        @Test
        @DisplayName("숫자·UUID로 읽을 수 없는 셀과 필수 셀 누락을 셀마다 모두 보고한다")
        void reportsUnreadableCells() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(
                    products(row(3, "P-1", "abc", "이름", "19900.5", null, null, "not-a-uuid", "9999999999")),
                    options(), overrides()));

            assertThat(result.drafts()).isEmpty();
            assertThat(result.errors().kept())
                    .extracting(UploadError::sheet, UploadError::row, UploadError::column, UploadError::productCode, UploadError::code)
                    .containsExactly(
                            tuple("1.상품", 3, "B", "P-1", "PRODUCT-012"),
                            tuple("1.상품", 3, "D", "P-1", "PRODUCT-012"),
                            tuple("1.상품", 3, "E", "P-1", "PRODUCT-012"),
                            tuple("1.상품", 3, "G", "P-1", "PRODUCT-012"),
                            tuple("1.상품", 3, "H", "P-1", "PRODUCT-012"));
        }

        @Test
        @DisplayName("상품코드가 없거나 중복되면 그 행을 보고한다 (첫 행은 정상 조립)")
        void reportsMissingAndDuplicateCodes() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(
                    products(
                            row(3, "P-1", "1001", "A", "1000", "1"),
                            row(4, null, "1001", "B", "1000", "1"),
                            row(5, "P-1", "1001", "C", "1000", "1")),
                    options(), overrides()));

            assertThat(result.drafts()).extracting(ProductDraft::productCode).containsExactly("P-1");
            assertThat(result.errors().kept()).extracting(UploadError::row, UploadError::column)
                    .containsExactly(tuple(4, "A"), tuple(5, "A"));
            assertThat(result.errors().kept().get(1).message()).contains("3");
        }

        @Test
        @DisplayName("[2.옵션]·[3.조합오버라이드]가 [1.상품]에 없는 상품코드를 가리키면 보고한다")
        void reportsUnknownProductCodeReferences() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(
                    products(teeRow(3)),
                    options(row(3, "P-404", "색상", "1", "빨강", "1")),
                    overrides(row(3, "P-405", "빨강"))));

            assertThat(result.errors().kept()).extracting(UploadError::sheet, UploadError::row, UploadError::column)
                    .containsExactly(tuple("2.옵션", 3, "A"), tuple("3.조합오버라이드", 3, "A"));
            // 참조 오류는 참조한 행의 문제 — 참조된 적 없는 P-001은 정상 조립된다
            assertThat(result.drafts()).extracting(ProductDraft::productCode).containsExactly("P-001");
        }

        @Test
        @DisplayName("같은 옵션 타입인데 타입순서가 다르면 보고한다")
        void reportsInconsistentTypeOrder() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(products(teeRow(3)),
                    options(row(3, "P-001", "색상", "1", "빨강", "1"), row(4, "P-001", "색상", "2", "파랑", "2")),
                    overrides()));

            assertSingleFormatError(result, "2.옵션", 4, "C");
            assertThat(result.drafts()).isEmpty();
        }

        @Test
        @DisplayName("옵션값에 구분자 '|'가 있으면 보고한다 (조합 표기를 해석할 수 없음)")
        void reportsSeparatorInValue() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(products(teeRow(3)),
                    options(row(3, "P-001", "색상", "1", "빨강|파랑", "1")), overrides()));

            assertSingleFormatError(result, "2.옵션", 3, "D");
        }

        @Test
        @DisplayName("옵션값조합의 값 개수가 타입 수와 다르면 보고한다")
        void reportsWrongSegmentCount() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(products(teeRow(3)), teeOptions(),
                    overrides(row(3, "P-001", "차콜"))));

            assertSingleFormatError(result, "3.조합오버라이드", 3, "B");
            assertThat(result.errors().kept().get(0).message()).contains("2개");
        }

        @Test
        @DisplayName("옵션값조합의 값이 해당 타입에 없으면 보고한다 (순서가 뒤바뀐 표기 포함)")
        void reportsUnknownSegmentValue() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(products(teeRow(3)), teeOptions(),
                    overrides(row(3, "P-001", "L|차콜"))));

            assertSingleFormatError(result, "3.조합오버라이드", 3, "B");
            assertThat(result.errors().kept().get(0).message()).contains("L").contains("색상");
        }

        @Test
        @DisplayName("옵션이 없는 상품의 조합 행, 빈 옵션값조합을 보고한다")
        void reportsCombinationWithoutOptionsOrNotation() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(
                    products(teeRow(3), row(4, "P-002", "1001", "단일", "1000", "1")),
                    teeOptions(),
                    overrides(row(3, "P-002", "L"), row(4, "P-001", null, "1000"))));

            assertThat(result.errors().kept()).extracting(UploadError::row, UploadError::column, UploadError::productCode)
                    .containsExactly(tuple(3, "B", "P-002"), tuple(4, "B", "P-001"));
        }

        @Test
        @DisplayName("제외여부가 Y/N/빈칸이 아니거나, Y인데 판매가·재고·SKU를 적으면 보고한다")
        void reportsInvalidExclusionFlag() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(products(teeRow(3)), teeOptions(),
                    overrides(
                            row(3, "P-001", "차콜|L", null, null, null, "예"),
                            row(4, "P-001", "차콜|S", null, "5", null, "Y"))));

            assertThat(result.errors().kept()).extracting(UploadError::row, UploadError::column)
                    .containsExactly(tuple(3, "F"), tuple(4, "F"));
        }

        @Test
        @DisplayName("[2.옵션]에서 형식 오류가 난 상품의 조합 행은 추가 오류 없이 건너뛴다 (해석 기준 없음, 같은 원인 중복 보고 방지)")
        void skipsOverridesOfBrokenProduct() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(products(teeRow(3)),
                    options(row(3, "P-001", "색상", "x", "빨강", "1")),
                    overrides(row(3, "P-001", "없는값"))));

            assertSingleFormatError(result, "2.옵션", 3, "C");
        }

        @Test
        @DisplayName("옵션타입명에 ref 구분자 '='가 있으면 보고한다")
        void reportsRefSeparatorInTypeName() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(products(teeRow(3)),
                    options(row(3, "P-001", "색=상", "1", "빨강", "1")), overrides()));

            assertSingleFormatError(result, "2.옵션", 3, "B");
        }

        @Test
        @DisplayName("셀 값이 길어도 오류 메시지에는 앞부분만 되울린다 (오류마다 긴 값이 복제되지 않도록)")
        void truncatesEchoedCellValue() {
            String longText = "x".repeat(10_000);
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(
                    products(row(3, "P-1", longText, "이름", "1000", "1")), options(), overrides()));

            assertThat(result.errors().kept()).singleElement()
                    .satisfies(error -> assertThat(error.message().length()).isLessThan(100));
        }
    }

    @Nested
    @DisplayName("상한 — 입력 제약과 같은 값에서 파싱 단계에 끊고 한 번만 보고한다")
    class Limits {

        @Test
        @DisplayName("옵션 타입이 31개째가 되면 그 행을 한 번 보고하고 이후 새 타입 행은 쌓지 않는다")
        void capsOptionTypesPerProduct() {
            WorkbookRow[] rows = new WorkbookRow[40];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = row(3 + i, "P-001", "t" + i, "1", "v", "1");
            }

            ProductWorkbookParser.Result result =
                    ProductWorkbookParser.parse(workbook(products(teeRow(3)), options(rows), overrides()));

            assertSingleFormatError(result, "2.옵션", 3 + ProductWorkbookTemplate.MAX_OPTION_TYPES, "B");
            assertThat(result.drafts()).isEmpty();
        }

        @Test
        @DisplayName("한 타입의 옵션값이 101개째가 되면 한 번 보고하고 이후 값 행은 쌓지 않는다")
        void capsValuesPerType() {
            WorkbookRow[] rows = new WorkbookRow[120];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = row(3 + i, "P-001", "색상", "1", "v" + i, "1");
            }

            ProductWorkbookParser.Result result =
                    ProductWorkbookParser.parse(workbook(products(teeRow(3)), options(rows), overrides()));

            assertSingleFormatError(result, "2.옵션", 3 + ProductWorkbookTemplate.MAX_VALUES_PER_TYPE, "D");
        }

        @Test
        @DisplayName("상품의 조합 행이 501개째가 되면 한 번 보고하고 이후 행은 해석하지 않는다")
        void capsCombinationRowsPerProduct() {
            WorkbookRow[] rows = new WorkbookRow[510];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = row(3 + i, "P-001", "화이트|S", String.valueOf(i));
            }

            ProductWorkbookParser.Result result =
                    ProductWorkbookParser.parse(workbook(products(teeRow(3)), teeOptions(), overrides(rows)));

            assertSingleFormatError(result, "3.조합오버라이드", 3 + ProductWorkbookTemplate.MAX_COMBINATION_ROWS, "B");
            assertThat(result.drafts()).isEmpty();
        }

        @Test
        @DisplayName("회귀(보안 리뷰 High): 한 상품에 타입 5만 행 + 조합 5만 행을 넣어도 오류는 1건, 조합 행은 해석하지 않는다")
        void hugeAbusiveSheetsStayBounded() {
            int size = 50_000;
            WorkbookRow[] optionRows = new WorkbookRow[size];
            WorkbookRow[] overrideRows = new WorkbookRow[size];
            for (int i = 0; i < size; i++) {
                optionRows[i] = row(3 + i, "P-001", "t" + i, "1", "v", "1");
                overrideRows[i] = row(3 + i, "P-001", "x");
            }

            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(
                    workbook(products(teeRow(3)), options(optionRows), overrides(overrideRows)));

            assertThat(result.errors().total()).isEqualTo(1);
            assertThat(result.drafts()).isEmpty();
        }

        @Test
        @DisplayName("회귀(보안 재리뷰): 옵션타입명 51자·옵션값 101자는 파싱 단계에서 보고하고, 그 상품의 조합 행은 비교하지 않는다")
        void rejectsOverlongTypeNameAndValueBeforeCombinationMatching() {
            String longValue = "가".repeat(ProductWorkbookTemplate.MAX_VALUE_CHARS + 1);
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(products(teeRow(3)),
                    options(
                            row(3, "P-001", "t".repeat(ProductWorkbookTemplate.MAX_TYPE_NAME_CHARS + 1), "1", "S", "1"),
                            row(4, "P-001", "색상", "2", longValue, "1")),
                    overrides(row(3, "P-001", "S|" + longValue), row(4, "P-001", "없음"))));

            assertThat(result.errors().kept()).extracting(UploadError::sheet, UploadError::row, UploadError::column)
                    .containsExactly(tuple("2.옵션", 3, "B"), tuple("2.옵션", 4, "D"));
            assertThat(result.drafts()).isEmpty();
        }

        @Test
        @DisplayName("경계: 옵션타입명 50자·옵션값 100자는 형식 오류가 아니다")
        void acceptsTypeNameAndValueAtLimit() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(products(teeRow(3)),
                    options(row(3, "P-001", "t".repeat(ProductWorkbookTemplate.MAX_TYPE_NAME_CHARS), "1",
                            "가".repeat(ProductWorkbookTemplate.MAX_VALUE_CHARS), "1")),
                    overrides()));

            assertThat(result.errors().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("오류는 500건까지만 보관하고 전체 건수는 따로 센다 (수집 시점 상한)")
        void keepsAtMostMaxErrors() {
            WorkbookRow[] rows = new WorkbookRow[600];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = row(3 + i, "P-404", "색상", "1", "v" + i, "1");
            }

            ProductWorkbookParser.Result result =
                    ProductWorkbookParser.parse(workbook(products(teeRow(3)), options(rows), overrides()));

            assertThat(result.errors().total()).isEqualTo(600);
            assertThat(result.errors().kept()).hasSize(500);
        }

        @Test
        @DisplayName("타입명·옵션값에 ':' 등이 섞여도 ref가 (타입명, 옵션값)마다 유일하다 (합성 ref 충돌 방지)")
        void refsStayUniqueAcrossTypes() {
            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(workbook(products(teeRow(3)),
                    options(row(3, "P-001", "a:b", "1", "c", "1"), row(4, "P-001", "a", "2", "b:c", "1")),
                    overrides()));

            assertThat(result.errors().isEmpty()).isTrue();
            assertThat(result.drafts().get(0).request().optionTypes())
                    .flatExtracting(OptionTypeRequest::values)
                    .extracting(OptionValueRequest::ref)
                    .containsExactly("a:b=c", "a=b:c");
        }
    }

    @Nested
    @DisplayName("파일 전체를 해석할 수 없음 (InvalidBulkUploadException)")
    class Unparseable {

        @Test
        @DisplayName("필수 시트가 없으면 거절한다")
        void rejectsMissingSheet() {
            Map<String, List<WorkbookRow>> sheets = new HashMap<>(workbook(products(teeRow(3)), options(), overrides()));
            sheets.remove(ProductWorkbookTemplate.OVERRIDE_SHEET);

            assertThatThrownBy(() -> ProductWorkbookParser.parse(sheets))
                    .isInstanceOf(InvalidBulkUploadException.class)
                    .hasMessageContaining("3.조합오버라이드");
        }

        @Test
        @DisplayName("헤더가 템플릿과 다르면(열 밀림) 거절한다")
        void rejectsShiftedHeader() {
            List<WorkbookRow> shifted = new ArrayList<>(products(teeRow(3)));
            shifted.set(0, row(1, "상품코드*", "상품명*", "카테고리ID*"));

            assertThatThrownBy(() -> ProductWorkbookParser.parse(workbook(shifted, options(), overrides())))
                    .isInstanceOf(InvalidBulkUploadException.class)
                    .hasMessageContaining("B1");
        }

        @Test
        @DisplayName("상품 행이 하나도 없으면 거절한다")
        void rejectsNoProducts() {
            assertThatThrownBy(() -> ProductWorkbookParser.parse(workbook(products(), options(), overrides())))
                    .isInstanceOf(InvalidBulkUploadException.class);
        }

        @Test
        @DisplayName("상품이 101개면 거절하고, 정확히 100개는 조립한다")
        void enforcesProductLimit() {
            WorkbookRow[] hundred = new WorkbookRow[100];
            for (int i = 0; i < 100; i++) {
                hundred[i] = row(3 + i, "P-" + i, "1001", "상품", "1000", "1");
            }
            WorkbookRow[] hundredOne = Arrays.copyOf(hundred, 101);
            hundredOne[100] = row(103, "P-100", "1001", "상품", "1000", "1");

            assertThat(ProductWorkbookParser.parse(workbook(products(hundred), options(), overrides())).drafts())
                    .hasSize(100);
            assertThatThrownBy(() -> ProductWorkbookParser.parse(workbook(products(hundredOne), options(), overrides())))
                    .isInstanceOf(InvalidBulkUploadException.class);
        }
    }
}
