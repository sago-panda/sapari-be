package com.sapari.productapp.controller.product;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.sapari.common.response.ResponseEnvelope;
import com.sapari.common.web.security.CurrentUserId;
import com.sapari.productapp.bulk.BulkProductUploadProcessor;
import com.sapari.productapp.controller.product.dto.response.BulkProductUploadResponse;

import lombok.RequiredArgsConstructor;

/**
 * 판매자 상품 대량 등록(엑셀 업로드) REST 컨트롤러. {@code /api/v1/seller/**} 시큐리티 체인이 {@code ROLE_SELLER}를 요구하며,
 * 판매자 식별자는 {@code @CurrentUserId}로만 받는다.
 */
@RestController
@RequestMapping("/api/v1/seller/products")
@RequiredArgsConstructor
public class SellerProductBulkController {

    private final BulkProductUploadProcessor bulkProductUploadProcessor;

    /**
     * 템플릿 엑셀(xlsx)로 상품을 일괄 등록한다. 파일 전체를 검증해 오류가 하나라도 있으면 아무것도 저장하지 않는다.
     *
     * <p>검증 오류도 처리 결과이므로 200으로 돌려준다({@code status=REJECTED}, 셀 위치가 붙은 오류 목록). 파일을 해석할 수 없는 경우(xlsx 아님·템플릿
     * 구조 불일치·한도 초과)만 400 PRODUCT-012다.
     *
     * @param sellerId 인증된 판매자 id
     * @param file     템플릿 형식의 xlsx 파일(최대 5MB, 상품 100개)
     * @return 200 OK + 처리 결과
     */
    @PostMapping(path = "/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEnvelope<BulkProductUploadResponse> uploadBulk(
            @CurrentUserId UUID sellerId,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEnvelope.success(bulkProductUploadProcessor.process(sellerId, file));
    }
}
