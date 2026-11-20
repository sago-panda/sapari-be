package com.sapari.productapp.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sapari.product.domain.exception.InvalidBulkUploadException;
import com.sapari.productapp.controller.product.dto.request.CombinationOverrideRequest;
import com.sapari.productapp.controller.product.dto.request.CreateProductRequest;
import com.sapari.productapp.controller.product.dto.request.ExclusionRuleRequest;
import com.sapari.productapp.controller.product.dto.request.OptionTypeRequest;

@DisplayName("XlsxWorkbookReader")
class XlsxWorkbookReaderTest {

    private final XlsxWorkbookReader reader = new XlsxWorkbookReader();

    @TempDir
    Path tempDir;

    private File write(XSSFWorkbook workbook) throws IOException {
        File file = tempDir.resolve("upload.xlsx").toFile();
        try (OutputStream out = Files.newOutputStream(file.toPath()); workbook) {
            workbook.write(out);
        }
        return file;
    }

    /**
     * xlsx(zip) 안의 한 엔트리 텍스트를 바꾼 새 파일을 만든다(엑셀 도구로 만들 수 없는 조작 파일 재현용).
     */
    private File replaceZipText(File source, String entryName, String target, String replacement) throws IOException {
        File out = tempDir.resolve("crafted.xlsx").toFile();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(source.toPath()));
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(out.toPath()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                byte[] content = zin.readAllBytes();
                if (entry.getName().equals(entryName)) {
                    content = new String(content, StandardCharsets.UTF_8)
                            .replace(target, replacement)
                            .getBytes(StandardCharsets.UTF_8);
                }
                zout.putNextEntry(new ZipEntry(entry.getName()));
                zout.write(content);
                zout.closeEntry();
            }
        }
        return out;
    }

    @Nested
    @DisplayName("셀 읽기")
    class ReadsCells {

        @Test
        @DisplayName("요청한 시트만, 비어 있지 않은 행·셀을 1-based 행 번호와 0-based 열로 읽는다 (숫자는 엑셀 표시 서식대로)")
        void readsRequestedSheetsWithFormattedValues() throws IOException {
            XSSFWorkbook workbook = new XSSFWorkbook();
            Sheet guide = workbook.createSheet("0.안내");
            guide.createRow(0).createCell(0).setCellValue("읽지 않는 시트");
            Sheet products = workbook.createSheet("1.상품");
            products.createRow(0).createCell(0).setCellValue("상품코드*");
            Row data = products.createRow(2);
            data.createCell(0).setCellValue("P-001");
            data.createCell(1).setCellValue(1001);
            CellStyle thousands = workbook.createCellStyle();
            thousands.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            data.createCell(3).setCellValue(19900);
            data.getCell(3).setCellStyle(thousands);
            data.createCell(8).setCellValue("<p>설명</p>");

            Map<String, List<WorkbookRow>> sheets = reader.read(write(workbook), Set.of("1.상품", "2.옵션"));

            assertThat(sheets).containsOnlyKeys("1.상품");
            assertThat(sheets.get("1.상품")).extracting(WorkbookRow::rowNumber).containsExactly(1, 3);
            WorkbookRow row = sheets.get("1.상품").get(1);
            assertThat(row.cells()).containsOnlyKeys(0, 1, 3, 8);
            assertThat(row.text(0)).isEqualTo("P-001");
            assertThat(row.text(1)).isEqualTo("1001");
            assertThat(row.text(3)).isEqualTo("19,900");
            assertThat(row.text(8)).isEqualTo("<p>설명</p>");
        }

        @Test
        @DisplayName("템플릿 밖 열(J열~)은 읽지 않는다")
        void dropsColumnsOutsideTemplate() throws IOException {
            XSSFWorkbook workbook = new XSSFWorkbook();
            Row data = workbook.createSheet("1.상품").createRow(2);
            data.createCell(8).setCellValue("설명");
            data.createCell(9).setCellValue("템플릿 밖");
            data.createCell(200).setCellValue("멀리 떨어진 열");

            WorkbookRow row = reader.read(write(workbook), Set.of("1.상품")).get("1.상품").get(0);

            assertThat(row.cells()).containsOnlyKeys(8);
        }

        @Test
        @DisplayName("셀 값은 상한 길이에서 자른다 — 엑셀로는 만들 수 없는(셀 32,767자 초과) 조작 파일 방어")
        void truncatesOversizedCellFromCraftedFile() throws IOException {
            XSSFWorkbook workbook = new XSSFWorkbook();
            workbook.createSheet("1.상품").createRow(2).createCell(8).setCellValue("PLACEHOLDER");
            File crafted = replaceZipText(write(workbook), "xl/sharedStrings.xml",
                    "PLACEHOLDER", "x".repeat(XlsxWorkbookReader.MAX_CELL_CHARS + 100));

            WorkbookRow row = reader.read(crafted, Set.of("1.상품")).get("1.상품").get(0);

            assertThat(row.raw(8)).hasSize(XlsxWorkbookReader.MAX_CELL_CHARS);
        }

        @Test
        @DisplayName("실제 템플릿 예시 파일을 파서까지 통과시키면 형식 오류 없이 상품 3개·옵션·조합 오버라이드·제외 룰이 조립된다")
        void readsBundledTemplateEndToEnd() throws IOException {
            File template = tempDir.resolve("template.xlsx").toFile();
            try (InputStream in = getClass().getResourceAsStream("/bulk/product-bulk-upload-template.xlsx")) {
                Files.copy(in, template.toPath());
            }

            ProductWorkbookParser.Result result = ProductWorkbookParser.parse(
                    reader.read(template, Set.copyOf(ProductWorkbookTemplate.SHEETS)));

            assertThat(result.errors().isEmpty()).isTrue();
            assertThat(result.drafts()).extracting(ProductDraft::productCode).containsExactly("P-001", "P-002", "P-003");
            CreateProductRequest tee = result.drafts().get(0).request();
            assertThat(tee.basePrice()).isEqualTo(19_900);
            assertThat(tee.tags()).containsExactly("여름", "코튼", "반팔");
            assertThat(tee.optionTypes()).extracting(OptionTypeRequest::name).containsExactly("색상", "사이즈");
            assertThat(tee.optionTypes().get(0).values()).extracting(v -> v.value(), v -> v.priceDelta(), v -> v.metadata())
                    .containsExactly(
                            tuple("화이트", 0, "{\"hex\":\"#FFFFFF\"}"),
                            tuple("네이비", 0, "{\"hex\":\"#000080\"}"),
                            tuple("차콜", 1_000, "{\"hex\":\"#36454F\"}"));
            assertThat(tee.combinations()).containsExactly(
                    new CombinationOverrideRequest(List.of("색상=차콜", "사이즈=L"), null, null, 30, "TS-CC-L"),
                    new CombinationOverrideRequest(List.of("색상=화이트", "사이즈=S"), 18_900, null, null, null));
            assertThat(tee.exclusionRules()).containsExactly(
                    new ExclusionRuleRequest(List.of("색상=차콜", "사이즈=S"), null));
            CreateProductRequest mug = result.drafts().get(2).request();
            assertThat(mug.optionTypes()).isEmpty();
            assertThat(mug.additionalShippingFee()).isEqualTo(3_000);
        }
    }

    @Nested
    @DisplayName("읽을 수 없는 파일 (InvalidBulkUploadException, PRODUCT-012)")
    class Unreadable {

        @Test
        @DisplayName("xlsx가 아닌 파일(텍스트·CSV)은 거절한다")
        void rejectsNonXlsx() throws IOException {
            File csv = tempDir.resolve("products.xlsx").toFile();
            Files.writeString(csv.toPath(), "상품코드,카테고리ID\nP-001,1001\n");

            assertThatThrownBy(() -> reader.read(csv, Set.of("1.상품")))
                    .isInstanceOf(InvalidBulkUploadException.class);
        }

        @Test
        @DisplayName("시트 행이 상한(50,100)을 넘으면 끝까지 읽지 않고 거절한다")
        void rejectsTooManyRows() throws IOException {
            File file = tempDir.resolve("huge.xlsx").toFile();
            try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); OutputStream out = Files.newOutputStream(file.toPath())) {
                Sheet sheet = workbook.createSheet("2.옵션");
                for (int i = 0; i <= XlsxWorkbookReader.MAX_ROWS_PER_SHEET; i++) {
                    sheet.createRow(i).createCell(0).setCellValue("P-" + i);
                }
                workbook.write(out);
            }

            assertThatThrownBy(() -> reader.read(file, Set.of("2.옵션")))
                    .isInstanceOf(InvalidBulkUploadException.class)
                    .hasMessageContaining("2.옵션");
        }
    }

    @Nested
    @DisplayName("메모리 상한 — ZIP 엔트리·공유 문자열 표")
    class MemoryLimits {

        /** 고정 시드 — 압축률이 실제 문서에 가깝게(지나친 반복이면 POI 압축률 하한에 걸린다) 섞인 한글을 만든다. */
        private final Random random = new Random(42);

        /**
         * 압축이 잘 되지 않는 한글 본문을 만든다(문단마다 임의 음절 40자 + 숫자).
         */
        private String koreanText(int length) {
            StringBuilder text = new StringBuilder(length + 64);
            while (text.length() < length) {
                text.append("<p>");
                for (int i = 0; i < 40; i++) {
                    text.append((char) (0xAC00 + random.nextInt(2_000)));
                }
                text.append(" 소재 ").append(random.nextInt(100_000)).append("</p>");
            }
            return text.substring(0, length);
        }

        /**
         * 5MB 업로드로 담을 수 있는 최대치를 넘는 정상 형식 파일을 만든다 — 상품 100개(상세설명 = 엑셀 셀 한도 32,767자) + 조합 행 50,000개.
         * 이 파일이 읽히면 한도 안의 어떤 정상 파일도 상한에 걸리지 않는다.
         *
         * @param useSharedStrings true면 문자열을 공유 문자열 표에(엑셀 방식), false면 셀에 인라인으로(코드 생성 도구 방식) 저장
         */
        private File largestRealisticWorkbook(boolean useSharedStrings) throws IOException {
            File file = tempDir.resolve("largest-" + useSharedStrings + ".xlsx").toFile();
            String description = koreanText(32_000);
            try (SXSSFWorkbook workbook = new SXSSFWorkbook(null, 100, false, useSharedStrings);
                 OutputStream out = Files.newOutputStream(file.toPath())) {
                Sheet products = workbook.createSheet(ProductWorkbookTemplate.PRODUCT_SHEET);
                for (int p = 0; p < 100; p++) {
                    Row row = products.createRow(p);
                    row.createCell(0).setCellValue("P-" + p);
                    row.createCell(2).setCellValue("여름 린넨 오버핏 셔츠 " + p);
                    // 상품마다 다른 문자열(공유 문자열 표에서 중복 제거되지 않게), 엑셀 셀 한도 32,767자에 맞춘다
                    row.createCell(8).setCellValue((p + description + koreanText(767)).substring(0, 32_767));
                }
                Sheet overrides = workbook.createSheet(ProductWorkbookTemplate.OVERRIDE_SHEET);
                for (int p = 0; p < 100; p++) {
                    for (int c = 0; c < 500; c++) {
                        Row row = overrides.createRow(p * 500 + c);
                        row.createCell(0).setCellValue("P-" + p);
                        row.createCell(1).setCellValue("색상값" + c % 10 + "|사이즈값" + c / 10 % 10 + "|소재값" + c / 100);
                        row.createCell(2).setCellValue(41_000 + c);
                        row.createCell(3).setCellValue(50);
                        row.createCell(4).setCellValue("SKU-P" + p + "-COMBO-" + c + "-LINEN-SHIRT");
                        row.createCell(5).setCellValue("N");
                    }
                }
                workbook.write(out);
            }
            return file;
        }

        /**
         * 한도 안 최대치를 넘는 정상 파일이 두 저장 방식 모두에서 상한에 걸리지 않고 끝까지 읽히는지 — 상한을 실측보다 낮추는 회귀를 막는다.
         *
         * @param useSharedStrings 문자열 저장 방식
         */
        @ParameterizedTest(name = "공유 문자열 표 사용 = {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("한도 안 최대 크기의 정상 파일은 상한에 걸리지 않고 끝까지 읽힌다 (실측 근거 — 공유 문자열 ≈ 10.6MB, 시트 ≈ 17.8MB)")
        void readsLargestRealisticWorkbook(boolean useSharedStrings) throws IOException {
            File file = largestRealisticWorkbook(useSharedStrings);

            Map<String, List<WorkbookRow>> sheets = reader.read(file, Set.copyOf(ProductWorkbookTemplate.SHEETS));

            assertThat(sheets.get(ProductWorkbookTemplate.PRODUCT_SHEET)).hasSize(100);
            assertThat(sheets.get(ProductWorkbookTemplate.OVERRIDE_SHEET)).hasSize(50_000);
            assertThat(sheets.get(ProductWorkbookTemplate.PRODUCT_SHEET).get(0).cells().get(8))
                    .hasSize(32_767);  // 엑셀 셀 한도 길이 그대로 — 자르지 않는다
        }

        /**
         * 공유 문자열 표가 상한을 넘으면(엔트리 상한 아래여도) 표를 끝까지 힙에 올리지 않고 거절하는지.
         */
        @Test
        @DisplayName("공유 문자열 표가 상한(16MB)을 넘으면 — ZIP 엔트리 상한(24MB) 아래여도 — 거절한다")
        void rejectsOversizedSharedStrings() throws IOException {
            File file = tempDir.resolve("strings.xlsx").toFile();
            // 셀 하나 ≈ 75KB(30,000자, 한글 3바이트·태그/숫자 1바이트 섞임) × 250개 ≈ 19MB — 16MB < 표 < 24MB
            try (SXSSFWorkbook workbook = new SXSSFWorkbook(null, 100, false, true);
                 OutputStream out = Files.newOutputStream(file.toPath())) {
                Sheet sheet = workbook.createSheet(ProductWorkbookTemplate.PRODUCT_SHEET);
                for (int i = 0; i < 250; i++) {
                    sheet.createRow(i).createCell(8).setCellValue(koreanText(30_000));
                }
                workbook.write(out);
            }
            long sharedStringsBytes = entrySize(file, "xl/sharedStrings.xml");
            assertThat(sharedStringsBytes)
                    .isGreaterThan(XlsxWorkbookReader.MAX_SHARED_STRINGS_BYTES)
                    .isLessThan(XlsxWorkbookReader.MAX_ZIP_ENTRY_BYTES);

            assertThatThrownBy(() -> reader.read(file, Set.of(ProductWorkbookTemplate.PRODUCT_SHEET)))
                    .isInstanceOf(InvalidBulkUploadException.class)
                    .hasMessageContaining("공유 문자열");
        }

        /**
         * 공유 문자열 하나(엑셀 셀 한도 안의 30,000자)를 {@code references}개 셀이 참조하는 작은 파일을 만든다. 공유 문자열 표는 중복을 제거하므로
         * 파일은 작지만 셀 문자 수 합계는 30,000 × references가 된다.
         *
         * @param column     참조 셀을 둘 열(템플릿 밖 열이면 읽기 결과에서 버려진다)
         * @param references 참조 셀 수
         */
        private File sharedStringReferencedBy(int column, int references) throws IOException {
            XSSFWorkbook workbook = new XSSFWorkbook();
            Sheet sheet = workbook.createSheet(ProductWorkbookTemplate.PRODUCT_SHEET);
            String text = koreanText(30_000);
            for (int i = 0; i < references; i++) {
                sheet.createRow(i).createCell(column).setCellValue(text);
            }
            return write(workbook);
        }

        /**
         * 셀 문자 수 합계가 상한과 정확히 같으면 읽고, 한 셀만 더해 넘으면 거절하는지(경계) — 상한은 잘리기 전 길이로 센다.
         */
        @Test
        @DisplayName("셀 문자 수 합계 상한(24M자) — 같으면 읽고, 넘으면 거절한다")
        void enforcesTotalCellCharsBoundary() throws IOException {
            int atLimit = (int) (XlsxWorkbookReader.MAX_TOTAL_CELL_CHARS / 30_000);

            Map<String, List<WorkbookRow>> sheets = reader.read(
                    sharedStringReferencedBy(ProductWorkbookTemplate.P_DESCRIPTION, atLimit),
                    Set.of(ProductWorkbookTemplate.PRODUCT_SHEET));

            assertThat((long) atLimit * 30_000).isEqualTo(XlsxWorkbookReader.MAX_TOTAL_CELL_CHARS);
            assertThat(sheets.get(ProductWorkbookTemplate.PRODUCT_SHEET)).hasSize(atLimit);
            assertThatThrownBy(() -> reader.read(
                    sharedStringReferencedBy(ProductWorkbookTemplate.P_DESCRIPTION, atLimit + 1),
                    Set.of(ProductWorkbookTemplate.PRODUCT_SHEET)))
                    .isInstanceOf(InvalidBulkUploadException.class)
                    .hasMessageContaining("셀 문자 수 합계");
        }

        /**
         * 템플릿 밖 열의 셀도 합계에 넣는지 — 결과에서 버리는 셀도 POI는 이미 처리했으므로 CPU 증폭을 막으려면 세야 한다.
         */
        @Test
        @DisplayName("템플릿 밖 열의 셀도 셀 문자 수 합계에 넣는다")
        void countsCellsOutsideTemplateColumns() throws IOException {
            int overLimit = (int) (XlsxWorkbookReader.MAX_TOTAL_CELL_CHARS / 30_000) + 1;
            File file = sharedStringReferencedBy(ProductWorkbookTemplate.MAX_COLUMNS + 10, overLimit);

            assertThatThrownBy(() -> reader.read(file, Set.of(ProductWorkbookTemplate.PRODUCT_SHEET)))
                    .isInstanceOf(InvalidBulkUploadException.class)
                    .hasMessageContaining("셀 문자 수 합계");
        }

        /**
         * 리뷰에서 재현된 공격 — 상한 길이를 넘는 공유 문자열 하나를 여러 셀이 참조하면 셀마다 잘린 사본이 생겨 1MB 미만 파일로 힙이 GB 단위로 불어났다.
         * 이제는 사본을 만들기 전에 합계 상한에서 멈춘다.
         */
        @Test
        @DisplayName("상한을 넘는 공유 문자열을 많은 셀이 참조하는 1MB 미만 조작 파일은 셀마다 잘린 사본을 만들기 전에 거절한다")
        void rejectsAmplifiedOversizedSharedString() throws IOException {
            XSSFWorkbook workbook = new XSSFWorkbook();
            Sheet sheet = workbook.createSheet(ProductWorkbookTemplate.PRODUCT_SHEET);
            for (int i = 0; i < 3_000; i++) {
                sheet.createRow(i).createCell(ProductWorkbookTemplate.P_DESCRIPTION).setCellValue("PLACEHOLDER");
            }
            // 엑셀로는 만들 수 없는 1,000,000자 공유 문자열 — 3,000셀이 참조하면 합계 3G자. 임의 영문이라 압축률 하한에 걸리지 않고 1MB 안에 든다
            StringBuilder huge = new StringBuilder(1_000_000);
            for (int i = 0; i < 1_000_000; i++) {
                huge.append((char) ('a' + random.nextInt(26)));
            }
            File crafted = replaceZipText(write(workbook), "xl/sharedStrings.xml", "PLACEHOLDER", huge.toString());

            assertThat(crafted.length()).isLessThan(1024 * 1024);
            assertThatThrownBy(() -> reader.read(crafted, Set.of(ProductWorkbookTemplate.PRODUCT_SHEET)))
                    .isInstanceOf(InvalidBulkUploadException.class)
                    .hasMessageContaining("셀 문자 수 합계");
        }

        /**
         * xlsx(zip) 안 엔트리의 풀린 크기.
         */
        private long entrySize(File file, String entryName) throws IOException {
            try (ZipFile zip = new ZipFile(file)) {
                return zip.getEntry(entryName).getSize();
            }
        }
    }
}
