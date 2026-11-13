package com.sapari.productapp.bulk;

import java.util.Map;

import com.sapari.productapp.controller.product.dto.request.CreateProductRequest;

/**
 * 엑셀에서 조립한 상품 한 건. 단건 등록 API와 같은 요청 DTO로 조립해 입력 제약(Bean Validation)을 그대로 재사용한다.
 *
 * @param productCode 파일의 상품코드
 * @param productRow  [1.상품] 시트의 행 번호
 * @param request     조립된 등록 요청
 * @param locations   요청 프로퍼티 경로(예: {@code optionTypes[0].values[1].value}) → 원본 셀. 검증 위반을 셀로 되돌리는 데 쓴다
 */
record ProductDraft(
        String productCode,
        int productRow,
        CreateProductRequest request,
        Map<String, CellLocation> locations
) {

    ProductDraft {
        locations = Map.copyOf(locations);
    }

    /**
     * 프로퍼티 경로에 해당하는 셀을 찾는다. 정확한 경로가 없으면 뒤 세그먼트({@code .name}·{@code [0]})를 하나씩 떼며 가장 가까운 상위 경로를 쓰고,
     * 끝까지 없으면 [1.상품]의 상품 행을 가리킨다.
     *
     * @param propertyPath Bean Validation 위반 경로(예: {@code tags[3].<list element>})
     * @return 원본 셀 위치
     */
    CellLocation locate(String propertyPath) {
        String path = propertyPath;
        while (!path.isEmpty()) {
            CellLocation location = locations.get(path);
            if (location != null) {
                return location;
            }
            path = parentPath(path);
        }
        return new CellLocation(ProductWorkbookTemplate.PRODUCT_SHEET, productRow, ProductWorkbookTemplate.P_CODE);
    }

    /**
     * 마지막 세그먼트를 뗀 경로. {@code a.b[1]} → {@code a.b}, {@code a.b} → {@code a}, {@code a} → 빈 문자열.
     */
    private static String parentPath(String path) {
        int cut = Math.max(path.lastIndexOf('.'), path.lastIndexOf('['));
        return cut <= 0 ? "" : path.substring(0, cut);
    }
}
