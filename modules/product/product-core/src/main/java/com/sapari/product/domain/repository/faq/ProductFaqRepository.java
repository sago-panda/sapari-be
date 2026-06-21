package com.sapari.product.domain.repository.faq;

import com.sapari.product.domain.model.faq.ProductFaq;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ProductFaq 애그리거트(문의 + 첨부 이미지) 영속 포트. 저장은 루트와 자식 이미지를 함께 동기화하고, 조회는 첨부 이미지를 조립해 완전한 {@link ProductFaq}로 돌려준다. 구현은
 * infrastructure, 트랜잭션 경계는 application이 소유한다.
 */
public interface ProductFaqRepository {
    /**
     * 신규(id == null)면 INSERT, 기존이면 본문 갱신 + 첨부 이미지 replace 후 저장된 애그리거트를 반환한다.
     */
    ProductFaq save(ProductFaq faq);

    /**
     * id로 문의를 첨부 이미지까지 조립해 조회한다.
     */
    Optional<ProductFaq> findById(UUID id);

    /**
     * 특정 상품의 문의 목록(각각 첨부 이미지 포함). 비밀글 노출 여부는 호출측 책임.
     */
    List<ProductFaq> findByProductId(UUID productId);
}
