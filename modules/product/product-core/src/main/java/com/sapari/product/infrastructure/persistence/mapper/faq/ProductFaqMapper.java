package com.sapari.product.infrastructure.persistence.mapper.faq;

import com.sapari.product.domain.model.faq.InquiryImageRef;
import com.sapari.product.domain.model.faq.ProductFaq;
import com.sapari.product.infrastructure.persistence.entity.faq.ProductFaqEntity;
import com.sapari.product.infrastructure.persistence.entity.faq.ProductInquiryImageEntity;
import java.util.List;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * {@link ProductFaq} 도메인 ↔ {@link ProductFaqEntity} 변환.
 *
 * <p>루트 {@code toEntity}(평면 1:1, enum은 공유 enum 패스스루)는 MapStruct가 생성한다. 첨부 이미지는 별도
 * 테이블이라 {@code toDomain}은 별도 로드된 이미지 리스트를 받아 조립하는 default, 자식 빌더/갱신도 default로 둔다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProductFaqMapper {

    ProductFaqEntity toEntity(ProductFaq faq);

    /**
     * 루트 + 별도 로드된 첨부 이미지를 하나의 애그리거트로 조립한다(자식은 RepositoryImpl이 로드해 전달).
     */
    default ProductFaq toDomain(ProductFaqEntity entity, List<ProductInquiryImageEntity> images) {
        if (entity == null) {
            return null;
        }
        return ProductFaq.builder()
                .id(entity.getId())
                .productId(entity.getProductId())
                .userId(entity.getUserId())
                .inquiryType(entity.getInquiryType())
                .title(entity.getTitle())
                .content(entity.getContent())
                .isPrivate(Boolean.TRUE.equals(entity.getIsPrivate()))
                .status(entity.getStatus())
                .answerContent(entity.getAnswerContent())
                .answeredBy(entity.getAnsweredBy())
                .answeredAt(entity.getAnsweredAt())
                .deletedAt(entity.getDeletedAt())
                .images(toImageRefs(images))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    default void updateEntityFromDomain(ProductFaqEntity entity, ProductFaq faq) {
        entity.updateInquiry(faq.inquiryType(), faq.title(), faq.content(), faq.isPrivate());
        entity.applyAnswer(faq.status(), faq.answerContent(), faq.answeredBy(), faq.answeredAt());
        entity.markDeleted(faq.deletedAt());
    }

    default ProductInquiryImageEntity toImageEntity(UUID inquiryId, InquiryImageRef ref) {
        return ProductInquiryImageEntity.builder()
                .inquiryId(inquiryId)
                .imageKey(ref.imageKey())
                .originFileName(ref.originFileName())
                .sortOrder(ref.sortOrder())
                .build();
    }

    default List<InquiryImageRef> toImageRefs(List<ProductInquiryImageEntity> images) {
        if (images == null) {
            return List.of();
        }
        return images.stream()
                .map(i -> new InquiryImageRef(i.getId(), i.getImageKey(), i.getOriginFileName(), i.getSortOrder()))
                .toList();
    }
}
