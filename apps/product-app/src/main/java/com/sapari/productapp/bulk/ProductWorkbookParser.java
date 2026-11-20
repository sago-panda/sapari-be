package com.sapari.productapp.bulk;

import static com.sapari.productapp.bulk.ProductWorkbookTemplate.C_CODE;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.C_EXCLUDED;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.C_PRICE;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.C_SKU;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.C_STOCK;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.C_VALUES;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.MAX_COMBINATION_ROWS;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.MAX_OPTION_TYPES;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.MAX_VALUES_PER_TYPE;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.O_CODE;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.O_METADATA;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.O_PRICE_DELTA;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.O_TYPE_NAME;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.O_TYPE_ORDER;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.O_VALUE;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.O_VALUE_ORDER;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.OPTION_SHEET;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.OVERRIDE_SHEET;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.P_BASE_PRICE;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.P_CATEGORY;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.P_CODE;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.P_DEFAULT_STOCK;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.P_DESCRIPTION;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.P_NAME;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.P_SHIPPING_FEE;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.P_SHIPPING_POLICY;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.P_TAGS;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.PRODUCT_SHEET;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.SEPARATOR;
import static com.sapari.productapp.bulk.ProductWorkbookTemplate.echo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.sapari.product.command.BulkCreateProductsCommand;
import com.sapari.product.domain.exception.InvalidBulkUploadException;
import com.sapari.product.domain.exception.ProductErrorCode;
import com.sapari.productapp.controller.product.dto.request.CombinationOverrideRequest;
import com.sapari.productapp.controller.product.dto.request.CreateProductRequest;
import com.sapari.productapp.controller.product.dto.request.ExclusionRuleRequest;
import com.sapari.productapp.controller.product.dto.request.OptionTypeRequest;
import com.sapari.productapp.controller.product.dto.request.OptionValueRequest;
import com.sapari.productapp.controller.product.dto.response.BulkProductUploadResponse.UploadError;

/**
 * 템플릿 시트 행을 상품 등록 요청({@link CreateProductRequest})으로 조립한다. 순수 함수이며 I/O가 없다.
 *
 * <p>여기서 잡는 것은 <b>엑셀 형식 오류</b>뿐이다 — 숫자·UUID로 읽을 수 없는 셀, 필수 셀 누락, 상품코드 누락·중복·미존재 참조, 같은 옵션 타입의 순서
 * 불일치, 옵션값조합 표기 오류, 제외여부 값 오류. 길이·범위 같은 입력 제약은 조립된 요청에 Bean Validation을 걸어(단건 API와 같은 규칙), 도메인 규칙은 대량
 * 등록 유스케이스가 검증한다. 형식 오류가 난 상품은 요청을 만들지 않는다(같은 원인의 오류가 뒤 단계에서 중복 보고되지 않도록).
 *
 * <p>시트나 헤더가 없거나 상품 수가 한도를 넘는 등 파일 전체를 해석할 수 없으면 {@link InvalidBulkUploadException}을 던진다.
 */
final class ProductWorkbookParser {

    private static final String FORMAT_ERROR = ProductErrorCode.INVALID_BULK_UPLOAD.getCode();
    // ref의 타입명·옵션값 구분자 — 옵션타입명에는 쓸 수 없게 막아 ref가 (타입명, 옵션값)마다 유일하도록 한다
    private static final String REF_SEPARATOR = "=";
    // 엑셀 서식이 붙은 정수(예: "19,900")도 받는다
    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,3}(,\\d{3})*|-?\\d+");

    private ProductWorkbookParser() {
    }

    /**
     * 조립 결과.
     *
     * @param drafts 형식 오류 없이 조립된 상품들(파일 순서)
     * @param errors 형식 오류들(보관 상한 적용)
     */
    record Result(List<ProductDraft> drafts, UploadErrors errors) {
    }

    /**
     * 세 시트의 행을 상품 요청으로 조립한다.
     *
     * @param sheets 시트 이름 → 행들(헤더 포함)
     * @return 조립된 상품과 형식 오류
     * @throws InvalidBulkUploadException 필수 시트·헤더가 없거나, 상품이 없거나, 상품 수가 한도를 넘는 경우
     */
    static Result parse(Map<String, List<WorkbookRow>> sheets) {
        List<WorkbookRow> productRows = requireSheet(sheets, PRODUCT_SHEET, ProductWorkbookTemplate.PRODUCT_HEADERS);
        List<WorkbookRow> optionRows = requireSheet(sheets, OPTION_SHEET, ProductWorkbookTemplate.OPTION_HEADERS);
        List<WorkbookRow> overrideRows = requireSheet(sheets, OVERRIDE_SHEET, ProductWorkbookTemplate.OVERRIDE_HEADERS);

        UploadErrors errors = new UploadErrors();
        Map<String, ProductBuilder> products = readProducts(productRows, errors);
        if (products.isEmpty() && errors.isEmpty()) {
            throw new InvalidBulkUploadException("등록할 상품이 없습니다.");
        }
        if (products.size() > BulkCreateProductsCommand.MAX_ITEMS) {
            throw new InvalidBulkUploadException(
                    "상품은 한 번에 " + BulkCreateProductsCommand.MAX_ITEMS + "개까지 등록할 수 있습니다: " + products.size());
        }
        readOptions(optionRows, products, errors);
        readOverrides(overrideRows, products, errors);

        List<ProductDraft> drafts = products.values()
                .stream()
                .filter(product -> !product.broken)
                .map(ProductBuilder::toDraft)
                .toList();
        return new Result(drafts, errors);
    }

    /**
     * 필수 시트와 헤더를 확인하고 데이터 행만 돌려준다(3행부터, 빈 행·안내 문구 행 제외).
     */
    private static List<WorkbookRow> requireSheet(
            Map<String, List<WorkbookRow>> sheets, String sheetName, List<String> headers) {
        List<WorkbookRow> rows = sheets.get(sheetName);
        if (rows == null) {
            throw new InvalidBulkUploadException("필수 시트 '" + sheetName + "'가 없습니다.");
        }
        WorkbookRow header = rows.stream()
                .filter(row -> row.rowNumber() == ProductWorkbookTemplate.HEADER_ROW)
                .findFirst()
                .orElseThrow(() -> new InvalidBulkUploadException("시트 '" + sheetName + "'에 헤더 행이 없습니다."));
        for (int column = 0; column < headers.size(); column++) {
            // 열이 밀린 파일은 값이 엉뚱한 필드로 들어가므로 헤더 문구가 템플릿과 정확히 같아야 한다
            if (!headers.get(column).equals(header.text(column))) {
                throw new InvalidBulkUploadException("시트 '" + sheetName + "'의 헤더가 템플릿과 다릅니다: "
                        + ProductWorkbookTemplate.columnLetter(column) + "1 기대='" + headers.get(column)
                        + "', 실제='" + header.text(column) + "'");
            }
        }
        return rows.stream()
                .filter(row -> row.rowNumber() >= ProductWorkbookTemplate.FIRST_DATA_ROW)
                .filter(row -> !row.isBlank())
                .filter(row -> row.text(0) == null || !row.text(0).startsWith(ProductWorkbookTemplate.NOTE_PREFIX))
                .toList();
    }

    /**
     * [1.상품] 시트를 읽는다. 상품코드가 없거나 중복된 행은 상품으로 만들지 않는다.
     */
    private static Map<String, ProductBuilder> readProducts(List<WorkbookRow> rows, UploadErrors errors) {
        Map<String, ProductBuilder> products = new LinkedHashMap<>();
        for (WorkbookRow row : rows) {
            CellReader cells = new CellReader(PRODUCT_SHEET, row, null, errors);
            String code = row.text(P_CODE);
            if (code == null) {
                cells.error(P_CODE, "상품코드는 필수입니다.");
                continue;
            }
            ProductBuilder existing = products.get(code);
            if (existing != null) {
                cells.withCode(code).error(P_CODE, "상품코드가 중복됩니다(처음 나온 행: " + existing.row + ").");
                continue;
            }
            cells = cells.withCode(code);
            ProductBuilder product = new ProductBuilder(code, row.rowNumber());
            product.categoryId = cells.longValue(P_CATEGORY, true);
            product.name = row.text(P_NAME);
            product.basePrice = cells.intValue(P_BASE_PRICE, true);
            product.defaultStock = cells.intValue(P_DEFAULT_STOCK, true);
            product.tags = splitTags(row.text(P_TAGS));
            product.shippingPolicyId = cells.uuidValue(P_SHIPPING_POLICY);
            product.shippingFee = cells.intValue(P_SHIPPING_FEE, false);
            product.description = row.raw(P_DESCRIPTION);
            product.broken = cells.failed;
            products.put(code, product);
        }
        return products;
    }

    /**
     * [2.옵션] 시트를 읽어 상품별 옵션 타입/값을 만든다. 같은 상품코드 + 옵션타입명이면 같은 타입이다.
     */
    private static void readOptions(
            List<WorkbookRow> rows, Map<String, ProductBuilder> products, UploadErrors errors) {
        for (WorkbookRow row : rows) {
            String code = row.text(O_CODE);
            CellReader cells = new CellReader(OPTION_SHEET, row, code, errors);
            ProductBuilder product = findProduct(cells, code, O_CODE, products);
            if (product == null) {
                continue;
            }
            String typeName = row.text(O_TYPE_NAME);
            String typeKey = typeName == null ? "" : typeName;
            TypeBuilder existingType = product.types.get(typeKey);
            // 상한을 넘는 행은 조립하지 않고 상품당(타입당) 한 번만 보고한다 — 한 상품에 타입·값을 무한정 쌓아
            // 파싱 비용과 오류 수를 키우는 파일을 입력 단계에서 끊는다(상한은 입력 제약과 같은 값)
            if (existingType == null && product.types.size() >= MAX_OPTION_TYPES) {
                if (!product.typeLimitReported) {
                    product.typeLimitReported = true;
                    cells.error(O_TYPE_NAME, "옵션 타입은 상품당 " + MAX_OPTION_TYPES + "개까지 적을 수 있습니다.");
                }
                product.markOptionsBroken();
                continue;
            }
            if (existingType != null && existingType.values.size() >= MAX_VALUES_PER_TYPE) {
                if (!existingType.limitReported) {
                    existingType.limitReported = true;
                    cells.error(O_VALUE, "옵션값은 옵션 타입당 " + MAX_VALUES_PER_TYPE + "개까지 적을 수 있습니다.");
                }
                product.markOptionsBroken();
                continue;
            }
            // 옵션타입명·옵션값은 조합 표기 해석에서 문자열 비교 대상이라, 입력 제약과 같은 길이 상한을 파싱 단계에서 먼저 건다
            // (긴 값이 조합 행마다 반복 비교돼 CPU를 키우지 않도록 — 초과한 상품은 조합을 해석하지 않는다)
            if (typeName != null && typeName.length() > ProductWorkbookTemplate.MAX_TYPE_NAME_CHARS) {
                cells.error(O_TYPE_NAME, "옵션타입명은 " + ProductWorkbookTemplate.MAX_TYPE_NAME_CHARS + "자까지 적을 수 있습니다.");
            }
            String rawValue = row.text(O_VALUE);
            if (rawValue != null && rawValue.length() > ProductWorkbookTemplate.MAX_VALUE_CHARS) {
                cells.error(O_VALUE, "옵션값은 " + ProductWorkbookTemplate.MAX_VALUE_CHARS + "자까지 적을 수 있습니다.");
            }
            if (typeName != null && typeName.contains(REF_SEPARATOR)) {
                cells.error(O_TYPE_NAME, "옵션타입명에는 '" + REF_SEPARATOR + "'를 쓸 수 없습니다.");
            }
            Short typeOrder = cells.shortValue(O_TYPE_ORDER, true);
            String value = row.text(O_VALUE);
            Short valueOrder = cells.shortValue(O_VALUE_ORDER, true);
            Integer priceDelta = cells.intValue(O_PRICE_DELTA, false);
            // [3.조합오버라이드]가 '|'로 옵션값을 이어 적으므로, 값에 '|'가 있으면 조합을 해석할 수 없다
            if (value != null && value.contains(SEPARATOR)) {
                cells.error(O_VALUE, "옵션값에는 '" + SEPARATOR + "'를 쓸 수 없습니다.");
            }

            // 옵션타입명이 빈 행끼리는 한 타입으로 묶인다 — 이름 누락은 조립 후 입력 제약(@NotBlank)이 셀 위치와 함께 보고한다
            TypeBuilder type = product.types.computeIfAbsent(
                    typeKey, key -> new TypeBuilder(typeName, typeOrder, row.rowNumber()));
            // 타입순서는 타입의 속성이라 같은 타입의 모든 행에서 같아야 한다
            if (typeOrder != null && type.order != null && !typeOrder.equals(type.order)) {
                cells.error(O_TYPE_ORDER, "같은 옵션 타입의 타입순서가 다릅니다(처음 나온 행 " + type.firstRow + ": " + type.order + ").");
            }
            type.values.add(new ValueRow(row.rowNumber(), value, valueOrder,
                    priceDelta == null ? 0 : priceDelta, row.text(O_METADATA)));
            if (cells.failed) {
                product.markOptionsBroken();
            }
        }
    }

    /**
     * [3.조합오버라이드] 시트를 읽는다. 옵션값조합을 타입순서대로 해석해 제외여부가 Y면 제외 룰, 아니면 조합 오버라이드로 만든다.
     */
    private static void readOverrides(
            List<WorkbookRow> rows, Map<String, ProductBuilder> products, UploadErrors errors) {
        for (WorkbookRow row : rows) {
            String code = row.text(C_CODE);
            CellReader cells = new CellReader(OVERRIDE_SHEET, row, code, errors);
            ProductBuilder product = findProduct(cells, code, C_CODE, products);
            // 옵션 시트에서 형식 오류가 난 상품은 조합을 해석할 기준(타입·값)이 불완전하므로 건너뛴다(오류는 이미 보고됨)
            if (product == null || product.optionsBroken) {
                continue;
            }
            if (product.combinationRows >= MAX_COMBINATION_ROWS) {
                if (!product.combinationLimitReported) {
                    product.combinationLimitReported = true;
                    cells.error(C_VALUES, "조합 행은 상품당 " + MAX_COMBINATION_ROWS + "개까지 적을 수 있습니다.");
                }
                product.broken = true;
                continue;
            }
            product.combinationRows++;
            List<String> valueRefs = resolveCombination(cells, row.text(C_VALUES), product);
            Integer price = cells.intValue(C_PRICE, false);
            Integer stock = cells.intValue(C_STOCK, false);
            String sku = row.text(C_SKU);
            Boolean excluded = cells.excludedValue(C_EXCLUDED);

            if (Boolean.TRUE.equals(excluded) && (row.text(C_PRICE) != null || row.text(C_STOCK) != null || sku != null)) {
                cells.error(C_EXCLUDED, "제외할 조합에는 판매가·재고·SKU를 적을 수 없습니다.");
            }
            if (!cells.failed) {
                if (Boolean.TRUE.equals(excluded)) {
                    product.exclusions.add(new RuleRow(row.rowNumber(), new ExclusionRuleRequest(valueRefs, null)));
                } else {
                    product.overrides.add(new RuleRow(row.rowNumber(),
                            new CombinationOverrideRequest(valueRefs, price, null, stock, sku)));
                }
            }
            product.broken |= cells.failed;
        }
    }

    /**
     * 옵션값조합 표기("차콜|L")를 타입순서대로 해석해 옵션값 ref 목록으로 바꾼다. 해석할 수 없으면 오류를 남기고 null.
     */
    private static List<String> resolveCombination(CellReader cells, String notation, ProductBuilder product) {
        if (notation == null) {
            cells.error(C_VALUES, "옵션값조합은 필수입니다.");
            return null;
        }
        if (product.types.isEmpty()) {
            cells.error(C_VALUES, "옵션이 없는 상품에는 조합을 적을 수 없습니다.");
            return null;
        }
        List<TypeBuilder> orderedTypes = product.orderedTypes();
        List<String> segments = Arrays.stream(notation.split(Pattern.quote(SEPARATOR), -1))
                .map(String::strip)
                .toList();
        if (segments.size() != orderedTypes.size()) {
            cells.error(C_VALUES, "옵션값조합은 타입순서대로 옵션값 " + orderedTypes.size() + "개를 '" + SEPARATOR
                    + "'로 이어 적어야 합니다(적은 값 " + segments.size() + "개).");
            return null;
        }
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            TypeBuilder type = orderedTypes.get(i);
            String segment = segments.get(i);
            if (type.values.stream().noneMatch(value -> segment.equals(value.value))) {
                cells.error(C_VALUES, "'" + echo(segment) + "'은(는) 옵션 타입 '" + echo(type.name) + "'에 없는 값입니다.");
                return null;
            }
            refs.add(ref(type.name, segment));
        }
        return refs;
    }

    /**
     * 참조하는 상품을 찾는다. 상품코드가 없거나 [1.상품]에 없으면 오류를 남기고 null.
     */
    private static ProductBuilder findProduct(
            CellReader cells, String code, int codeColumn, Map<String, ProductBuilder> products) {
        if (code == null) {
            cells.error(codeColumn, "상품코드는 필수입니다.");
            return null;
        }
        ProductBuilder product = products.get(code);
        if (product == null) {
            cells.error(codeColumn, "[" + PRODUCT_SHEET + "]에 없는 상품코드입니다.");
        }
        return product;
    }

    /**
     * 태그 셀('|' 구분)을 목록으로 바꾼다. 비었거나 공백뿐인 조각은 버린다.
     */
    private static List<String> splitTags(String text) {
        if (text == null) {
            return List.of();
        }
        return Arrays.stream(text.split(Pattern.quote(SEPARATOR)))
                .map(String::strip)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }

    /**
     * 옵션값 ref — 룰·오버라이드가 옵션값을 가리키는 요청 내 키. 도메인 오류 메시지에 그대로 나오므로 판매자가 읽을 수 있는 "타입명=옵션값"으로 만든다.
     *
     * <p>옵션타입명에 {@code =}를 금지하므로 첫 {@code =}가 타입명과 옵션값을 가른다 — 한 상품 안에서 (타입명, 옵션값)마다 유일하다.
     * 판매자가 적은 값이 아니므로 길이 제약 위반은 보고하지 않는다({@link BulkProductUploadProcessor}).
     */
    static String ref(String typeName, String value) {
        return typeName + REF_SEPARATOR + value;
    }

    /**
     * 한 행의 셀을 타입에 맞게 읽고, 읽을 수 없으면 셀 위치와 함께 오류를 남긴다.
     */
    private static final class CellReader {

        private final String sheet;
        private final WorkbookRow row;
        private final String productCode;
        private final UploadErrors errors;
        private boolean failed;

        private CellReader(String sheet, WorkbookRow row, String productCode, UploadErrors errors) {
            this.sheet = sheet;
            this.row = row;
            this.productCode = productCode;
            this.errors = errors;
        }

        /**
         * 같은 행을 상품코드를 붙여 읽는 리더.
         */
        private CellReader withCode(String code) {
            return new CellReader(sheet, row, code, errors);
        }

        /**
         * 셀 위치로 형식 오류를 남기고 이 행을 실패로 표시한다.
         */
        private void error(int column, String message) {
            failed = true;
            errors.add(new UploadError(sheet, row.rowNumber(), ProductWorkbookTemplate.columnLetter(column),
                    productCode, FORMAT_ERROR, message));
        }

        /**
         * 정수 셀. 비었으면 필수면 오류, 아니면 null.
         */
        private Integer intValue(int column, boolean required) {
            Long value = longValue(column, required);
            if (value == null) {
                return null;
            }
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                error(column, "숫자가 너무 큽니다: " + echo(row.text(column)));
                return null;
            }
            return value.intValue();
        }

        /**
         * 순서 셀(short 범위 정수).
         */
        private Short shortValue(int column, boolean required) {
            Long value = longValue(column, required);
            if (value == null) {
                return null;
            }
            if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                error(column, "순서 값이 너무 큽니다: " + echo(row.text(column)));
                return null;
            }
            return value.shortValue();
        }

        /**
         * 긴 정수 셀. 천 단위 쉼표를 허용하고 소수·문자는 거절한다.
         */
        private Long longValue(int column, boolean required) {
            String text = row.text(column);
            if (text == null) {
                if (required) {
                    error(column, "필수 값입니다.");
                }
                return null;
            }
            if (!INTEGER.matcher(text).matches()) {
                error(column, "정수가 아닙니다: " + echo(text));
                return null;
            }
            try {
                return Long.parseLong(text.replace(",", ""));
            } catch (NumberFormatException e) {
                // 패턴은 맞지만 long 범위를 넘는 자릿수 — 셀 오류로 번역한다
                error(column, "숫자가 너무 큽니다: " + echo(text));
                return null;
            }
        }

        /**
         * UUID 셀. 비었으면 null.
         */
        private UUID uuidValue(int column) {
            String text = row.text(column);
            if (text == null) {
                return null;
            }
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                // UUID 형식이 아닌 셀 — 셀 오류로 번역한다
                error(column, "UUID 형식이 아닙니다: " + echo(text));
                return null;
            }
        }

        /**
         * 제외여부 셀. Y → true, 비었거나 N → false, 그 밖의 값은 오류.
         */
        private Boolean excludedValue(int column) {
            String text = row.text(column);
            if (text == null || "N".equalsIgnoreCase(text)) {
                return false;
            }
            if ("Y".equalsIgnoreCase(text)) {
                return true;
            }
            error(column, "제외여부는 Y 또는 빈칸이어야 합니다: " + echo(text));
            return null;
        }
    }

    /**
     * 상품 한 건의 조립 중 상태.
     */
    private static final class ProductBuilder {

        private final String code;
        private final int row;
        private final Map<String, TypeBuilder> types = new LinkedHashMap<>();
        private final List<RuleRow<CombinationOverrideRequest>> overrides = new ArrayList<>();
        private final List<RuleRow<ExclusionRuleRequest>> exclusions = new ArrayList<>();
        // 어느 시트든 형식 오류가 있음 → 요청을 만들지 않는다
        private boolean broken;
        // [2.옵션] 형식 오류가 있음 → 조합 표기를 해석하지 않는다
        private boolean optionsBroken;
        private boolean typeLimitReported;
        private boolean combinationLimitReported;
        private int combinationRows;
        private List<TypeBuilder> orderedTypesCache;
        private Long categoryId;
        private String name;
        private Integer basePrice;
        private Integer defaultStock;
        private List<String> tags;
        private UUID shippingPolicyId;
        private Integer shippingFee;
        private String description;

        private ProductBuilder(String code, int row) {
            this.code = code;
            this.row = row;
        }

        /**
         * [2.옵션] 형식 오류로 표시한다 — 요청을 만들지 않고 조합 표기도 해석하지 않는다.
         */
        private void markOptionsBroken() {
            optionsBroken = true;
            broken = true;
        }

        /**
         * 옵션 타입을 타입순서 → 처음 나온 행 순으로 정렬한다(옵션값조합 표기의 해석 순서). [2.옵션]을 다 읽은 뒤에만 불리므로 상품당 한 번 계산해 둔다.
         */
        private List<TypeBuilder> orderedTypes() {
            if (orderedTypesCache == null) {
                orderedTypesCache = types.values()
                        .stream()
                        .sorted(Comparator.comparing((TypeBuilder type) -> type.order)
                                .thenComparingInt(type -> type.firstRow))
                        .toList();
            }
            return orderedTypesCache;
        }

        /**
         * 등록 요청과 프로퍼티 경로 → 셀 위치 색인을 만든다. 형식 오류가 없는 상품에만 호출한다.
         */
        private ProductDraft toDraft() {
            Map<String, CellLocation> locations = new HashMap<>();
            locations.put("categoryId", productCell(P_CATEGORY));
            locations.put("name", productCell(P_NAME));
            locations.put("description", productCell(P_DESCRIPTION));
            locations.put("basePrice", productCell(P_BASE_PRICE));
            locations.put("defaultStock", productCell(P_DEFAULT_STOCK));
            locations.put("tags", productCell(P_TAGS));
            locations.put("shippingPolicyId", productCell(P_SHIPPING_POLICY));
            locations.put("additionalShippingFee", productCell(P_SHIPPING_FEE));

            List<OptionTypeRequest> optionTypes = new ArrayList<>();
            List<TypeBuilder> typeList = new ArrayList<>(types.values());
            for (int i = 0; i < typeList.size(); i++) {
                TypeBuilder type = typeList.get(i);
                String typePath = "optionTypes[" + i + "]";
                locations.put(typePath, new CellLocation(OPTION_SHEET, type.firstRow, O_TYPE_NAME));
                locations.put(typePath + ".sortOrder", new CellLocation(OPTION_SHEET, type.firstRow, O_TYPE_ORDER));
                List<OptionValueRequest> values = new ArrayList<>();
                for (int j = 0; j < type.values.size(); j++) {
                    ValueRow value = type.values.get(j);
                    String valuePath = typePath + ".values[" + j + "]";
                    locations.put(valuePath, new CellLocation(OPTION_SHEET, value.row, O_VALUE));
                    locations.put(valuePath + ".sortOrder", new CellLocation(OPTION_SHEET, value.row, O_VALUE_ORDER));
                    locations.put(valuePath + ".priceDelta", new CellLocation(OPTION_SHEET, value.row, O_PRICE_DELTA));
                    locations.put(valuePath + ".metadata", new CellLocation(OPTION_SHEET, value.row, O_METADATA));
                    values.add(new OptionValueRequest(
                            value.value, null, value.metadata, value.priceDelta, value.order, ref(type.name, value.value)));
                }
                optionTypes.add(new OptionTypeRequest(type.name, null, type.order, values));
            }

            List<CombinationOverrideRequest> combinationRequests = new ArrayList<>();
            for (int k = 0; k < overrides.size(); k++) {
                RuleRow<CombinationOverrideRequest> override = overrides.get(k);
                String path = "combinations[" + k + "]";
                locations.put(path, new CellLocation(OVERRIDE_SHEET, override.row, C_VALUES));
                locations.put(path + ".price", new CellLocation(OVERRIDE_SHEET, override.row, C_PRICE));
                locations.put(path + ".stock", new CellLocation(OVERRIDE_SHEET, override.row, C_STOCK));
                locations.put(path + ".sku", new CellLocation(OVERRIDE_SHEET, override.row, C_SKU));
                combinationRequests.add(override.request);
            }
            List<ExclusionRuleRequest> exclusionRequests = new ArrayList<>();
            for (int k = 0; k < exclusions.size(); k++) {
                RuleRow<ExclusionRuleRequest> exclusion = exclusions.get(k);
                String path = "exclusionRules[" + k + "]";
                locations.put(path, new CellLocation(OVERRIDE_SHEET, exclusion.row, C_EXCLUDED));
                locations.put(path + ".valueRefs", new CellLocation(OVERRIDE_SHEET, exclusion.row, C_VALUES));
                exclusionRequests.add(exclusion.request);
            }

            CreateProductRequest request = new CreateProductRequest(
                    categoryId, name, description, basePrice, shippingPolicyId, shippingFee, tags,
                    optionTypes, defaultStock, List.of(), exclusionRequests, combinationRequests);
            return new ProductDraft(code, row, request, locations);
        }

        private CellLocation productCell(int column) {
            return new CellLocation(PRODUCT_SHEET, row, column);
        }
    }

    /**
     * 옵션 타입 한 개의 조립 중 상태.
     */
    private static final class TypeBuilder {

        private final String name;
        private final Short order;
        private final int firstRow;
        private final List<ValueRow> values = new ArrayList<>();
        private boolean limitReported;

        private TypeBuilder(String name, Short order, int firstRow) {
            this.name = name;
            this.order = order;
            this.firstRow = firstRow;
        }
    }

    /**
     * [2.옵션] 한 행.
     */
    private record ValueRow(int row, String value, Short order, int priceDelta, String metadata) {
    }

    /**
     * [3.조합오버라이드] 한 행에서 만든 룰·오버라이드와 원본 행.
     */
    private record RuleRow<T>(int row, T request) {
    }
}
