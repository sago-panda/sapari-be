package com.sapari.productapp.bulk;

import java.util.Map;

/**
 * 엑셀 시트의 한 행. 비어 있지 않은 셀만 담는다.
 *
 * @param rowNumber 행 번호(1-based, 엑셀 화면 표기와 같음)
 * @param cells     0-based 열 번호 → 셀 표시 문자열(엑셀 서식이 적용된 값)
 */
record WorkbookRow(int rowNumber, Map<Integer, String> cells) {

    WorkbookRow {
        cells = Map.copyOf(cells);
    }

    /**
     * 셀 값을 앞뒤 공백을 제거해 돌려준다. 비었으면 null.
     */
    String text(int columnIndex) {
        String value = cells.get(columnIndex);
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 셀 값을 공백 제거 없이 그대로 돌려준다(상세설명 HTML처럼 원문 보존이 필요한 열). 비었으면 null.
     */
    String raw(int columnIndex) {
        String value = cells.get(columnIndex);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 모든 셀이 비었는지.
     */
    boolean isBlank() {
        return cells.values().stream().allMatch(String::isBlank);
    }
}
