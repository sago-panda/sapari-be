package com.sapari.productapp.bulk;

/**
 * 엑셀 셀(또는 행) 위치.
 *
 * @param sheet       시트 이름
 * @param row         행 번호(1-based)
 * @param columnIndex 0-based 열 번호, 행 단위 위치면 null
 */
record CellLocation(String sheet, int row, Integer columnIndex) {

    /**
     * 열 문자(예: "D"), 행 단위 위치면 null.
     */
    String columnLetter() {
        return columnIndex == null ? null : ProductWorkbookTemplate.columnLetter(columnIndex);
    }
}
