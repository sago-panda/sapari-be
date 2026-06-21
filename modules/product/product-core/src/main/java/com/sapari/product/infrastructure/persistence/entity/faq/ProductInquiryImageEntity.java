package com.sapari.product.infrastructure.persistence.entity.faq;

import com.sapari.storage.db.entity.BaseUuidEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 문의 첨부 이미지.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_inquiry_images", schema = "product_schema")
public class ProductInquiryImageEntity extends BaseUuidEntity {

    // ref: product_faq.id. 물리 FK 미사용.
    private UUID inquiryId;

    private String imageKey;

    private String originFileName;

    private Integer sortOrder;

    private Instant deletedAt;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 한다.
     */
    @Builder
    public ProductInquiryImageEntity(UUID inquiryId, String imageKey, String originFileName,
                                     Integer sortOrder, Instant deletedAt) {
        this.inquiryId = inquiryId;
        this.imageKey = imageKey;
        this.originFileName = originFileName;
        this.sortOrder = sortOrder;
        this.deletedAt = deletedAt;
    }
}
