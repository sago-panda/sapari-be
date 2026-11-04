package com.sapari.productapp.bulk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.sapari.common.core.exception.CommonErrorCode;
import com.sapari.product.command.BulkCreateProductsCommand;
import com.sapari.product.command.BulkProductItemCommand;
import com.sapari.product.domain.exception.InvalidBulkUploadException;
import com.sapari.product.port.BulkCreateProductsUseCase;
import com.sapari.product.view.BulkCreateProductsView;
import com.sapari.productapp.controller.product.dto.response.BulkProductUploadResponse;
import com.sapari.productapp.controller.product.dto.response.BulkProductUploadResponse.Created;
import com.sapari.productapp.controller.product.dto.response.BulkProductUploadResponse.UploadError;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 상품 대량 등록 엑셀 업로드를 처리한다(웹 어댑터 — 엑셀은 JSON과 같은 입력 형식일 뿐이라 해석은 앱 레이어가 맡는다).
 *
 * <p>검증은 세 겹이며, 앞 단계에서 오류가 하나라도 나면 뒤 단계로 가지 않고 {@code REJECTED}로 돌려준다.
 * <ol>
 *   <li>엑셀 형식 — 셀 타입·필수 셀·상품코드 참조·옵션값조합 표기({@link ProductWorkbookParser})
 *   <li>입력 제약 — 조립된 {@code CreateProductRequest}에 Bean Validation(단건 등록 API와 같은 규칙)
 *   <li>도메인 규칙·저장 — {@link BulkCreateProductsUseCase}(태그·옵션 룰·조합 수·카테고리, 통과 시 상품별 저장)
 * </ol>
 * 모든 오류는 원본 시트·행·열로 되돌려 보고한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BulkProductUploadProcessor {

    /** 업로드 파일 크기 상한. 서블릿 multipart 설정과 같은 값이다(설정이 바뀌어도 이 한도는 유지). */
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private static final String CONSTRAINT_ERROR = CommonErrorCode.INVALID_INPUT.getCode();

    private final XlsxWorkbookReader workbookReader;
    private final Validator validator;
    private final BulkCreateProductsUseCase bulkCreateProductsUseCase;

    /**
     * 엑셀 파일을 검증하고 상품을 일괄 등록한다.
     *
     * @param sellerId 인증된 판매자 id
     * @param file     업로드된 xlsx 파일
     * @return 처리 결과(거절·완료·중단)와 셀 위치가 붙은 오류
     * @throws InvalidBulkUploadException 파일이 비었거나 크기 한도를 넘거나 xlsx가 아니거나 템플릿 구조가 다른 경우
     */
    public BulkProductUploadResponse process(UUID sellerId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidBulkUploadException("업로드된 파일이 없습니다.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new InvalidBulkUploadException("파일 크기가 한도(" + MAX_FILE_BYTES + " bytes)를 넘습니다: " + file.getSize());
        }

        ProductWorkbookParser.Result parsed = ProductWorkbookParser.parse(readSheets(file));
        UploadErrors errors = parsed.errors();
        validateConstraints(parsed.drafts(), errors);
        if (!errors.isEmpty()) {
            return response(BulkCreateProductsView.REJECTED, List.of(), errors.kept(), errors.total());
        }

        Map<String, ProductDraft> draftsByCode = parsed.drafts()
                .stream()
                .collect(Collectors.toMap(ProductDraft::productCode, Function.identity()));
        List<BulkProductItemCommand> items = parsed.drafts()
                .stream()
                .map(draft -> new BulkProductItemCommand(draft.productCode(), draft.request().toCommand(sellerId)))
                .toList();
        BulkCreateProductsView view = bulkCreateProductsUseCase.create(new BulkCreateProductsCommand(items));

        // 도메인 오류는 상품 단위라 [1.상품] 시트의 상품 행을 가리킨다(메시지에 위반한 옵션값·SKU 등이 담긴다)
        List<UploadError> domainErrors = view.errors()
                .stream()
                .map(error -> new UploadError(ProductWorkbookTemplate.PRODUCT_SHEET,
                        draftsByCode.get(error.productCode()).productRow(), null,
                        error.productCode(), error.code(), error.message()))
                .toList();
        List<Created> created = view.created()
                .stream()
                .map(product -> new Created(product.productCode(), product.productId()))
                .toList();
        return response(view.status(), created, domainErrors, domainErrors.size());
    }

    /**
     * 업로드 파일을 임시 파일로 옮겨 읽는다 — 파일 경로로 열어야 ZIP 엔트리를 필요한 만큼만 읽는다. 임시 파일은 항상 지운다.
     */
    private Map<String, List<WorkbookRow>> readSheets(MultipartFile file) {
        Path temp = null;
        try {
            temp = Files.createTempFile("product-bulk-", ".xlsx");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            return workbookReader.read(temp.toFile(), new HashSet<>(ProductWorkbookTemplate.SHEETS));
        } catch (IOException e) {
            // 업로드 스트림을 임시 파일로 옮기지 못함 — 요청 파일을 처리할 수 없는 경우로 번역한다
            throw new InvalidBulkUploadException("업로드 파일을 읽을 수 없습니다.", e);
        } finally {
            deleteQuietly(temp);
        }
    }

    /**
     * 조립된 요청마다 Bean Validation을 걸어 위반을 원본 셀 위치로 바꿔 기록한다.
     *
     * <p>옵션값 ref와 룰·오버라이드의 valueRefs는 파서가 "타입명=옵션값"으로 합성한 요청 내부 키라 판매자가 적은 값이 아니다. 그 길이 제약 위반은
     * 보고하지 않는다(타입명·옵션값 자체의 길이 위반은 해당 셀로 따로 보고된다).
     */
    private void validateConstraints(List<ProductDraft> drafts, UploadErrors errors) {
        for (ProductDraft draft : drafts) {
            for (ConstraintViolation<?> violation : validator.validate(draft.request())) {
                String path = violation.getPropertyPath().toString();
                if (path.endsWith(".ref") || path.contains(".valueRefs")) {
                    continue;
                }
                CellLocation location = draft.locate(path);
                errors.add(new UploadError(location.sheet(), location.row(), location.columnLetter(),
                        draft.productCode(), CONSTRAINT_ERROR, violation.getMessage()));
            }
        }
    }

    /**
     * 오류를 시트 → 행 → 열 순으로 정렬해 응답을 만든다(오류는 이미 수집 단계에서 상한까지만 보관됨).
     */
    private static BulkProductUploadResponse response(
            String status, List<Created> created, List<UploadError> errors, int totalErrorCount) {
        List<UploadError> sorted = errors.stream()
                .sorted(Comparator.comparingInt((UploadError error) -> ProductWorkbookTemplate.SHEETS.indexOf(error.sheet()))
                        .thenComparingInt(UploadError::row)
                        .thenComparing(UploadError::column, Comparator.nullsFirst(Comparator.naturalOrder())))
                .limit(BulkProductUploadResponse.MAX_ERRORS)
                .toList();
        return new BulkProductUploadResponse(status, created, sorted, totalErrorCount);
    }

    /**
     * 임시 파일을 지운다. 실패해도 요청 결과(이미 저장된 상품 포함)를 뒤집지 않도록 경고 로그만 남긴다 — 임시 디렉터리는 OS가 정리한다.
     */
    private static void deleteQuietly(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("대량 등록 임시 파일 삭제 실패. path={}", temp, e);
        }
    }
}
