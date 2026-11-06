package com.sapari.productapp.bulk;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.RecordFormatException;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import com.sapari.product.domain.exception.InvalidBulkUploadException;

/**
 * xlsx 파일을 스트리밍(SAX)으로 읽어 필요한 시트의 행만 모은다. 사용자 업로드 파일을 다루므로 메모리를 상한 안에 둔다.
 *
 * <ul>
 *   <li>파일 경로로 연다 — 입력 스트림으로 열면 ZIP 엔트리 전체가 메모리에 풀리지만, 파일로 열면 필요한 엔트리만 읽는다.
 *   <li>DOM(usermodel) 대신 SAX로 시트를 읽어 행 단위로만 메모리를 쓴다. 시트당 행 수, 템플릿 밖 열, 셀 길이에 상한을 둔다.
 *   <li>ZIP 엔트리 크기 상한을 낮춘다(zip bomb). 압축률 하한은 POI 기본값을 유지한다.
 *   <li>공유 문자열 표는 SAX로 흘려 읽을 수 없고 통째로 힙에 올라가므로(셀이 인덱스로 참조) 엔트리 상한보다 낮은 별도 상한을 둔다.
 *   <li>셀 문자 수의 워크북 합계에 상한을 둔다. 여러 셀이 같은 공유 문자열을 참조하면 파일은 작아도 셀마다 처리·복사(잘림·공백 제거)가 일어나므로,
 *       바이트 상한만으로는 메모리·CPU가 묶이지 않는다.
 *   <li>조작된 파일에서 POI가 던지는 런타임 예외도 입력 파일 문제(400)로 번역한다.
 * </ul>
 */
@Component
public class XlsxWorkbookReader {

    /**
     * 시트당 읽을 수 있는 (비어 있지 않은) 행 상한. 가장 긴 정상 시트는 [3.조합오버라이드]로 상품 100개 × 조합 행 500개 = 50,000행이고,
     * 여기에 헤더·설명·안내 행 여유를 더한다. [2.옵션]은 조합 500개 제약상 상품당 옵션값이 약 130행을 넘을 수 없어 이 안에 든다.
     */
    static final int MAX_ROWS_PER_SHEET = 50_100;

    /** 셀 문자열 길이 상한 — 가장 긴 필드(상세설명 50,000자)보다 한 글자 길게 둬, 초과 여부는 입력 제약이 판단하게 한다. */
    static final int MAX_CELL_CHARS = 50_001;

    /**
     * 워크북 전체 셀 문자 수(잘리기 전 길이, 템플릿 밖 열 포함) 합계 상한. 공유 문자열 하나를 수많은 셀이 참조하는 작은 파일이 셀마다 사본을 만들어
     * 힙·CPU를 증폭시키는 것을 막는다 — 사본은 셀 길이를 넘지 않으므로 증폭이 이 합계에 비례하는 수준으로 묶인다.
     * 실측: 한도 안 최대치를 넘는 정상 파일(상품 100개 × 상세설명 32,767자 + 조합 행 50,000개) ≈ 6.06M자. 상세설명을 inline으로
     * 50,000자까지 쓰고 [2.옵션]을 채워도 9M자 안팎이라, 여유를 둔다.
     */
    static final long MAX_TOTAL_CELL_CHARS = 24_000_000L;

    /**
     * 풀린 ZIP 엔트리 하나의 크기 상한. 시트 XML은 SAX로 흘려 읽어 힙에 머물지 않으므로 CPU·디스크 상한 성격이다.
     * 실측: 5MB 업로드 안의 최대 시트(조합 50,000행, 문자열 인라인 저장) ≈ 17.8MB — 여기에 여유를 둔다.
     */
    static final long MAX_ZIP_ENTRY_BYTES = 24L * 1024 * 1024;

    /**
     * 공유 문자열 표(xl/sharedStrings.xml) 크기 상한. 표 전체가 요청 동안 힙에 머물므로 엔트리 상한보다 낮게 둔다.
     * 실측: 5MB 업로드 안의 최대(상세설명 100개 × 엑셀 셀 한도 32,767자 + 조합 SKU 50,000개) ≈ 10.6MB.
     */
    static final long MAX_SHARED_STRINGS_BYTES = 16L * 1024 * 1024;

    static {
        // POI 전역 설정이지만 이 앱에서 POI를 쓰는 곳은 이 리더뿐이다
        ZipSecureFile.setMaxEntrySize(MAX_ZIP_ENTRY_BYTES);
    }

    /**
     * 파일에서 지정한 시트들의 행을 읽는다. 없는 시트는 결과에 담기지 않는다(필수 여부 판단은 호출측).
     *
     * @param file       xlsx 파일
     * @param sheetNames 읽을 시트 이름들
     * @return 시트 이름 → 비어 있지 않은 행들(행 번호 오름차순)
     * @throws InvalidBulkUploadException xlsx가 아니거나 손상됐거나 행 수·공유 문자열·셀 문자 수 합계 상한을 넘은 경우
     */
    public Map<String, List<WorkbookRow>> read(File file, Set<String> sheetNames) {
        try (OPCPackage pkg = OPCPackage.open(file, PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = readSharedStrings(pkg);
            StylesTable styles = reader.getStylesTable();
            DataFormatter formatter = new DataFormatter();

            Map<String, List<WorkbookRow>> result = new HashMap<>();
            CellCharBudget budget = new CellCharBudget();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream sheet = sheets.next()) {
                    String name = sheets.getSheetName();
                    if (!sheetNames.contains(name)) {
                        continue;
                    }
                    RowCollector collector = new RowCollector(name, budget);
                    XMLReader parser = XMLHelper.newXMLReader();
                    // 수식 셀은 저장된 결과값을 읽는다(formulasNotResults=false)
                    parser.setContentHandler(new XSSFSheetXMLHandler(styles, strings, collector, formatter, false));
                    parser.parse(new InputSource(sheet));
                    result.put(name, collector.rows);
                }
            }
            return result;
        } catch (RowLimitExceededException e) {
            throw new InvalidBulkUploadException(
                    "시트 '" + e.sheetName + "'의 행이 " + MAX_ROWS_PER_SHEET + "개를 넘습니다.", e);
        } catch (CellCharsLimitExceededException e) {
            throw new InvalidBulkUploadException(
                    "셀 문자 수 합계가 상한(" + MAX_TOTAL_CELL_CHARS + "자)을 넘습니다.", e);
        } catch (SharedStringsLimitExceededException e) {
            throw new InvalidBulkUploadException(
                    "공유 문자열 표가 상한(" + MAX_SHARED_STRINGS_BYTES + " bytes)을 넘습니다.", e);
        } catch (IOException | OpenXML4JException | SAXException | ParserConfigurationException
                 | IllegalArgumentException | IndexOutOfBoundsException | RecordFormatException | POIXMLException e) {
            // xlsx가 아닌 파일(xls·csv·손상된 zip), zip bomb 차단, 조작된 셀 참조·공유 문자열 인덱스 등 — 입력 파일 문제로 번역한다
            throw new InvalidBulkUploadException("xlsx 파일을 읽을 수 없습니다: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 공유 문자열 표를 크기 상한 안에서 읽는다. POI의 {@code ReadOnlySharedStringsTable(OPCPackage)}와 같은 파트를 고르되, 스트림을 상한으로 감싼다
     * — 엔트리 헤더의 크기 값은 조작될 수 있어 미리 확인하지 않고 실제로 읽은 바이트를 센다.
     *
     * @param pkg 열린 xlsx 패키지
     * @return 공유 문자열 표(파트가 없으면 빈 표)
     * @throws SharedStringsLimitExceededException 표가 {@link #MAX_SHARED_STRINGS_BYTES}를 넘는 경우
     */
    private static ReadOnlySharedStringsTable readSharedStrings(OPCPackage pkg)
            throws IOException, SAXException, OpenXML4JException {
        List<PackagePart> parts = pkg.getPartsByContentType(XSSFRelation.SHARED_STRINGS.getContentType());
        if (parts.isEmpty()) {
            return new ReadOnlySharedStringsTable(pkg);
        }
        try (InputStream in = new LimitedInputStream(parts.get(0).getInputStream(), MAX_SHARED_STRINGS_BYTES)) {
            return new ReadOnlySharedStringsTable(in);
        }
    }

    /**
     * 시트 이벤트를 행 단위로 모으는 핸들러. 비어 있지 않은 셀만 전달받는다.
     */
    private static final class RowCollector implements SheetContentsHandler {

        private final String sheetName;
        private final CellCharBudget budget;
        private final List<WorkbookRow> rows = new ArrayList<>();
        private Map<Integer, String> currentCells;

        private RowCollector(String sheetName, CellCharBudget budget) {
            this.sheetName = sheetName;
            this.budget = budget;
        }

        /**
         * 새 행 시작 — 행 수 상한을 넘으면 파싱을 중단한다.
         */
        @Override
        public void startRow(int rowIndex) {
            if (rows.size() >= MAX_ROWS_PER_SHEET) {
                throw new RowLimitExceededException(sheetName);
            }
            currentCells = new HashMap<>();
        }

        /**
         * 행 끝 — 셀이 하나라도 있으면 보관한다.
         */
        @Override
        public void endRow(int rowIndex) {
            if (!currentCells.isEmpty()) {
                rows.add(new WorkbookRow(rowIndex + 1, currentCells));
            }
        }

        /**
         * 셀 값 — 엑셀 서식이 적용된 표시 문자열을 열 번호로 보관한다. 템플릿 밖 열은 버리고, 긴 값은 상한에서 자른다.
         */
        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (cellReference == null || formattedValue == null) {
                return;
            }
            // 열 필터·잘림보다 먼저 센다 — 버리거나 자를 셀도 POI가 이미 처리했고, 잘린 사본도 메모리를 쓴다
            budget.consume(formattedValue.length());
            int column = new CellReference(cellReference).getCol();
            // 템플릿이 쓰지 않는 열은 읽을 이유가 없다 — 행마다 수십 열을 채운 파일의 메모리 증폭을 막는다
            if (column >= ProductWorkbookTemplate.MAX_COLUMNS) {
                return;
            }
            String value = formattedValue.length() > MAX_CELL_CHARS
                    ? formattedValue.substring(0, MAX_CELL_CHARS)
                    : formattedValue;
            currentCells.put(column, value);
        }
    }

    /**
     * 워크북 전체 셀 문자 수를 누적해 {@link #MAX_TOTAL_CELL_CHARS}를 넘으면 파싱을 중단시키는 카운터(요청마다 새로 만든다).
     */
    private static final class CellCharBudget {

        private long used;

        /**
         * 셀 하나의 문자 수를 더한다.
         *
         * @param chars 셀 문자열 길이
         * @throws CellCharsLimitExceededException 합계가 상한을 넘은 경우
         */
        private void consume(int chars) {
            used += chars;
            if (used > MAX_TOTAL_CELL_CHARS) {
                throw new CellCharsLimitExceededException();
            }
        }
    }

    /**
     * SAX 콜백 안에서 셀 문자 수 합계 상한을 넘었음을 알리는 신호. {@link #read}가 입력 오류로 번역한다.
     */
    private static final class CellCharsLimitExceededException extends RuntimeException {

        private CellCharsLimitExceededException() {
            super(null, null, false, false);
        }
    }

    /**
     * SAX 콜백 안에서 행 수 상한을 넘었음을 알리는 신호. {@link #read}가 입력 오류로 번역한다.
     */
    private static final class RowLimitExceededException extends RuntimeException {

        private final String sheetName;

        private RowLimitExceededException(String sheetName) {
            super(null, null, false, false);
            this.sheetName = sheetName;
        }
    }

    /**
     * 읽은 바이트 수가 상한을 넘으면 {@link SharedStringsLimitExceededException}을 던지는 스트림.
     */
    private static final class LimitedInputStream extends FilterInputStream {

        private final long limit;
        private long count;

        private LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        /**
         * 한 바이트를 읽고 센다.
         */
        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                add(1);
            }
            return b;
        }

        /**
         * 버퍼로 읽고 읽은 만큼 센다.
         */
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                add(n);
            }
            return n;
        }

        /**
         * 건너뛴 바이트도 센다(풀린 크기 기준 상한이므로).
         */
        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            add(skipped);
            return skipped;
        }

        /**
         * 누적 바이트를 더하고 상한을 넘으면 중단한다.
         */
        private void add(long bytes) throws SharedStringsLimitExceededException {
            count += bytes;
            if (count > limit) {
                throw new SharedStringsLimitExceededException();
            }
        }
    }

    /**
     * 공유 문자열 표가 상한을 넘었음을 알리는 신호. SAX 파서를 거쳐 그대로 전파되도록 {@link IOException}이며, {@link #read}가 입력 오류로 번역한다.
     */
    private static final class SharedStringsLimitExceededException extends IOException {

        private SharedStringsLimitExceededException() {
            super("shared strings limit exceeded");
        }
    }
}
