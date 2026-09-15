package com.sapari.productapp.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import com.sapari.product.command.BulkCreateProductsCommand;
import com.sapari.product.command.CreateProductCommand;
import com.sapari.product.domain.exception.InvalidBulkUploadException;
import com.sapari.product.port.BulkCreateProductsUseCase;
import com.sapari.product.view.BulkCreateProductsView;
import com.sapari.product.view.BulkCreatedProductView;
import com.sapari.product.view.BulkProductErrorView;
import com.sapari.productapp.controller.product.dto.response.BulkProductUploadResponse;
import com.sapari.productapp.controller.product.dto.response.BulkProductUploadResponse.Created;
import com.sapari.productapp.controller.product.dto.response.BulkProductUploadResponse.UploadError;

@DisplayName("BulkProductUploadProcessor")
class BulkProductUploadProcessorTest {

    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private BulkCreateProductsUseCase useCase;
    private BulkProductUploadProcessor processor;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        useCase = mock(BulkCreateProductsUseCase.class);
        processor = new BulkProductUploadProcessor(new XlsxWorkbookReader(), validator, useCase);
    }

    /**
     * 템플릿 헤더가 있는 3시트 워크북을 만들고 각 시트 3행부터 데이터 행을 채운다.
     */
    private static MockMultipartFile upload(String[][] products, String[][] options, String[][] overrides) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fill(workbook.createSheet(ProductWorkbookTemplate.PRODUCT_SHEET), ProductWorkbookTemplate.PRODUCT_HEADERS, products);
            fill(workbook.createSheet(ProductWorkbookTemplate.OPTION_SHEET), ProductWorkbookTemplate.OPTION_HEADERS, options);
            fill(workbook.createSheet(ProductWorkbookTemplate.OVERRIDE_SHEET), ProductWorkbookTemplate.OVERRIDE_HEADERS, overrides);
            workbook.write(out);
            return new MockMultipartFile("file", "products.xlsx", XLSX, out.toByteArray());
        }
    }

    private static void fill(Sheet sheet, List<String> headers, String[][] rows) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            header.createCell(i).setCellValue(headers.get(i));
        }
        sheet.createRow(1).createCell(0).setCellValue("설명");
        for (int r = 0; r < rows.length; r++) {
            Row row = sheet.createRow(2 + r);
            for (int c = 0; c < rows[r].length; c++) {
                if (rows[r][c] != null) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
        }
    }

    private static String[] product(String code, String name) {
        return new String[] {code, "1001", name, "19900", "100", "여름"};
    }

    private static String[][] rows(String[]... rows) {
        return rows;
    }

    @Nested
    @DisplayName("요청 파일 자체 거절 (InvalidBulkUploadException)")
    class RejectsFile {

        @Test
        @DisplayName("빈 파일은 거절하고 유스케이스를 호출하지 않는다")
        void rejectsEmptyFile() {
            MockMultipartFile empty = new MockMultipartFile("file", "p.xlsx", XLSX, new byte[0]);

            assertThatThrownBy(() -> processor.process(SELLER_ID, empty))
                    .isInstanceOf(InvalidBulkUploadException.class);
            then(useCase).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("5MB를 1바이트라도 넘으면 읽기 전에 거절한다 (경계: 정확히 5MB는 크기 검사 통과)")
        void rejectsOversizedFile() {
            MockMultipartFile over = new MockMultipartFile(
                    "file", "p.xlsx", XLSX, new byte[(int) BulkProductUploadProcessor.MAX_FILE_BYTES + 1]);
            MockMultipartFile exact = new MockMultipartFile(
                    "file", "p.xlsx", XLSX, new byte[(int) BulkProductUploadProcessor.MAX_FILE_BYTES]);

            assertThatThrownBy(() -> processor.process(SELLER_ID, over))
                    .isInstanceOf(InvalidBulkUploadException.class)
                    .hasMessageContaining("크기");
            // 정확히 5MB는 크기 검사를 지나 xlsx 해석 단계에서 거절된다(0으로 채운 바이트는 xlsx가 아님)
            assertThatThrownBy(() -> processor.process(SELLER_ID, exact))
                    .isInstanceOf(InvalidBulkUploadException.class)
                    .hasMessageContaining("xlsx");
        }
    }

    @Nested
    @DisplayName("검증 오류 — REJECTED, 유스케이스 미호출")
    class Rejected {

        @Test
        @DisplayName("입력 제약 위반(Bean Validation)을 원본 셀과 COMMON-001로 보고한다 — 단건 API와 같은 규칙")
        void reportsConstraintViolationsAtCells() throws IOException {
            MockMultipartFile file = upload(
                    rows(product("P-1", "가".repeat(101))),
                    rows(new String[] {"P-1", "색상", "1", "빨강", "1", "0", "not-json"}),
                    rows(new String[] {"P-1", "빨강", null, "-5"}));

            BulkProductUploadResponse response = processor.process(SELLER_ID, file);

            assertThat(response.status()).isEqualTo(BulkCreateProductsView.REJECTED);
            assertThat(response.created()).isEmpty();
            assertThat(response.errors())
                    .extracting(UploadError::sheet, UploadError::row, UploadError::column, UploadError::productCode, UploadError::code)
                    .containsExactly(
                            tuple("1.상품", 3, "C", "P-1", "COMMON-001"),
                            tuple("2.옵션", 3, "G", "P-1", "COMMON-001"),
                            tuple("3.조합오버라이드", 3, "D", "P-1", "COMMON-001"));
            assertThat(response.totalErrorCount()).isEqualTo(3);
            then(useCase).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("합성 키(ref·valueRefs)의 길이 위반은 보고하지 않는다 — 타입명 50자 + 옵션값 100자 조합 오버라이드도 정상")
        void ignoresSyntheticRefViolations() throws IOException {
            given(useCase.create(any())).willReturn(new BulkCreateProductsView(
                    BulkCreateProductsView.COMPLETED, List.of(new BulkCreatedProductView("P-1", UUID.randomUUID())), List.of()));
            String typeName = "t".repeat(50);
            String value = "가".repeat(100);
            MockMultipartFile file = upload(
                    rows(product("P-1", "티셔츠")),
                    rows(new String[] {"P-1", typeName, "1", value, "1"}),
                    rows(new String[] {"P-1", value, "15000"}));

            BulkProductUploadResponse response = processor.process(SELLER_ID, file);

            assertThat(response.errors()).isEmpty();
            assertThat(response.status()).isEqualTo(BulkCreateProductsView.COMPLETED);
            ArgumentCaptor<BulkCreateProductsCommand> captor = ArgumentCaptor.forClass(BulkCreateProductsCommand.class);
            then(useCase).should().create(captor.capture());
            assertThat(captor.getValue().items().get(0).product().combinations()).singleElement()
                    .satisfies(c -> assertThat(c.valueRefs()).containsExactly(typeName + "=" + value));
        }

        @Test
        @DisplayName("형식 오류와 입력 제약 오류를 함께 시트 → 행 → 열 순으로 정렬해 돌려준다")
        void sortsMixedErrors() throws IOException {
            MockMultipartFile file = upload(
                    rows(product("P-2", "가".repeat(101)), new String[] {"P-1", "abc", "이름", "1000", "1"}),
                    rows(),
                    rows());

            BulkProductUploadResponse response = processor.process(SELLER_ID, file);

            assertThat(response.errors()).extracting(UploadError::row, UploadError::column, UploadError::code)
                    .containsExactly(tuple(3, "C", "COMMON-001"), tuple(4, "B", "PRODUCT-012"));
        }

        @Test
        @DisplayName("오류는 500건까지만 담고 전체 건수는 따로 알려준다")
        void capsErrors() throws IOException {
            // 없는 상품코드를 가리키는 옵션 행 600개 — 행마다 오류 1건
            String[][] badOptions = new String[600][];
            for (int i = 0; i < badOptions.length; i++) {
                badOptions[i] = new String[] {"P-404", "색상", "1", "v" + i, "1"};
            }

            BulkProductUploadResponse response = processor.process(SELLER_ID,
                    upload(rows(product("P-1", "티셔츠")), badOptions, rows()));

            assertThat(response.errors()).hasSize(BulkProductUploadResponse.MAX_ERRORS);
            assertThat(response.totalErrorCount()).isEqualTo(600);
            assertThat(response.errors().get(0).row()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("유스케이스 호출과 결과 매핑")
    class Delegates {

        @Test
        @DisplayName("검증을 통과하면 상품코드와 인증 판매자 id를 담은 커맨드로 유스케이스를 한 번 호출하고 저장 결과를 옮긴다")
        void callsUseCaseAndMapsCreated() throws IOException {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            given(useCase.create(any())).willReturn(new BulkCreateProductsView(BulkCreateProductsView.COMPLETED,
                    List.of(new BulkCreatedProductView("P-1", id1), new BulkCreatedProductView("P-2", id2)), List.of()));

            BulkProductUploadResponse response = processor.process(SELLER_ID, upload(
                    rows(product("P-1", "티셔츠"), product("P-2", "후드")),
                    rows(new String[] {"P-1", "사이즈", "1", "S", "1"}, new String[] {"P-1", "사이즈", "1", "M", "2", "1000"}),
                    rows(new String[] {"P-1", "M", "15000", null, "SKU-M"})));

            ArgumentCaptor<BulkCreateProductsCommand> captor = ArgumentCaptor.forClass(BulkCreateProductsCommand.class);
            then(useCase).should().create(captor.capture());
            assertThat(captor.getValue().items()).extracting(item -> item.productCode()).containsExactly("P-1", "P-2");
            CreateProductCommand first = captor.getValue().items().get(0).product();
            assertThat(first.sellerId()).isEqualTo(SELLER_ID);
            assertThat(first.name()).isEqualTo("티셔츠");
            assertThat(first.optionTypes()).singleElement()
                    .satisfies(type -> assertThat(type.values()).extracting(v -> v.ref()).containsExactly("사이즈=S", "사이즈=M"));
            assertThat(first.combinations()).singleElement().satisfies(c -> {
                assertThat(c.valueRefs()).containsExactly("사이즈=M");
                assertThat(c.price()).isEqualTo(15_000);
                assertThat(c.sku()).isEqualTo("SKU-M");
            });

            assertThat(response.status()).isEqualTo(BulkCreateProductsView.COMPLETED);
            assertThat(response.created()).containsExactly(new Created("P-1", id1), new Created("P-2", id2));
            assertThat(response.errors()).isEmpty();
            assertThat(response.totalErrorCount()).isZero();
        }

        @Test
        @DisplayName("유스케이스의 도메인 오류는 [1.상품]의 해당 상품 행(열 없음)으로 되돌려 보고한다")
        void mapsDomainErrorsToProductRow() throws IOException {
            given(useCase.create(any())).willReturn(new BulkCreateProductsView(BulkCreateProductsView.REJECTED, List.of(),
                    List.of(new BulkProductErrorView("P-2", "PRODUCT-005", "카테고리를 찾을 수 없습니다: 1001"))));

            BulkProductUploadResponse response = processor.process(SELLER_ID, upload(
                    rows(product("P-1", "티셔츠"), product("P-2", "후드")), rows(), rows()));

            assertThat(response.status()).isEqualTo(BulkCreateProductsView.REJECTED);
            assertThat(response.errors()).containsExactly(
                    new UploadError("1.상품", 4, null, "P-2", "PRODUCT-005", "카테고리를 찾을 수 없습니다: 1001"));
        }

        @Test
        @DisplayName("저장 중단(STOPPED)이면 이미 저장된 상품과 실패 상품을 함께 돌려준다")
        void mapsStoppedResult() throws IOException {
            UUID id1 = UUID.randomUUID();
            given(useCase.create(any())).willReturn(new BulkCreateProductsView(BulkCreateProductsView.STOPPED,
                    List.of(new BulkCreatedProductView("P-1", id1)),
                    List.of(new BulkProductErrorView("P-2", "PRODUCT-005", "카테고리를 찾을 수 없습니다: 1001"))));

            BulkProductUploadResponse response = processor.process(SELLER_ID, upload(
                    rows(product("P-1", "티셔츠"), product("P-2", "후드")), rows(), rows()));

            assertThat(response.status()).isEqualTo(BulkCreateProductsView.STOPPED);
            assertThat(response.created()).containsExactly(new Created("P-1", id1));
            assertThat(response.errors()).extracting(UploadError::row, UploadError::productCode).containsExactly(tuple(4, "P-2"));
        }
    }
}
