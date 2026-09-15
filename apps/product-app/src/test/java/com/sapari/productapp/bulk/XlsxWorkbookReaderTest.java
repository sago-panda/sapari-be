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
import java.util.Set;
import java.util.zip.ZipEntry;
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
}
