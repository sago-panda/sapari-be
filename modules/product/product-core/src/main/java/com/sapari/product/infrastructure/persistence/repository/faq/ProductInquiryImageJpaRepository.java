package com.sapari.product.infrastructure.persistence.repository.faq;

import com.sapari.product.infrastructure.persistence.entity.faq.ProductInquiryImageEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 문의 첨부 이미지 Spring Data 어댑터. 문의별 정렬 조회 + 문의 단위 일괄 삭제(자식 replace용).
 */
public interface ProductInquiryImageJpaRepository extends JpaRepository<ProductInquiryImageEntity, UUID> {
    List<ProductInquiryImageEntity> findByInquiryIdOrderBySortOrder(UUID inquiryId);

    void deleteByInquiryId(UUID inquiryId);
}
