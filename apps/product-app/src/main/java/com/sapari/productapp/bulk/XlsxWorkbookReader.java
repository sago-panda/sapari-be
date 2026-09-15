package com.sapari.productapp.bulk;

import java.io.File;
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

    /** 풀린 ZIP 엔트리 하나의 크기 상한 (상한 행 수의 정상 시트 XML을 덮으면서 5MB 업로드에 맞춘 값). */
    private static final long MAX_ZIP_ENTRY_BYTES = 32L * 1024 * 1024;

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
     * @throws InvalidBulkUploadException xlsx가 아니거나 손상됐거나 행 수 상한을 넘은 경우
     */
    public Map<String, List<WorkbookRow>> read(File file, Set<String> sheetNames) {
        try (OPCPackage pkg = OPCPackage.open(file, PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            DataFormatter formatter = new DataFormatter();

            Map<String, List<WorkbookRow>> result = new HashMap<>();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream sheet = sheets.next()) {
                    String name = sheets.getSheetName();
                    if (!sheetNames.contains(name)) {
                        continue;
                    }
                    RowCollector collector = new RowCollector(name);
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
        } catch (IOException | OpenXML4JException | SAXException | ParserConfigurationException
                 | IllegalArgumentException | IndexOutOfBoundsException | RecordFormatException | POIXMLException e) {
            // xlsx가 아닌 파일(xls·csv·손상된 zip), zip bomb 차단, 조작된 셀 참조·공유 문자열 인덱스 등 — 입력 파일 문제로 번역한다
            throw new InvalidBulkUploadException("xlsx 파일을 읽을 수 없습니다: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 시트 이벤트를 행 단위로 모으는 핸들러. 비어 있지 않은 셀만 전달받는다.
     */
    private static final class RowCollector implements SheetContentsHandler {

        private final String sheetName;
        private final List<WorkbookRow> rows = new ArrayList<>();
        private Map<Integer, String> currentCells;

        private RowCollector(String sheetName) {
            this.sheetName = sheetName;
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
     * SAX 콜백 안에서 행 수 상한을 넘었음을 알리는 신호. {@link #read}가 입력 오류로 번역한다.
     */
    private static final class RowLimitExceededException extends RuntimeException {

        private final String sheetName;

        private RowLimitExceededException(String sheetName) {
            super(null, null, false, false);
            this.sheetName = sheetName;
        }
    }
}
