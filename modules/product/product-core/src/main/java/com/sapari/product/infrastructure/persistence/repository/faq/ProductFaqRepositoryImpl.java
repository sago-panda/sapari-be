package com.sapari.product.infrastructure.persistence.repository.faq;

import com.sapari.product.infrastructure.persistence.entity.faq.ProductInquiryImageEntity;

import com.sapari.product.domain.model.faq.ProductFaq;
import com.sapari.product.domain.repository.faq.ProductFaqRepository;
import com.sapari.product.infrastructure.persistence.mapper.faq.ProductFaqMapper;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
/**
 * ProductFaq 애그리거트 영속 어댑터. 루트 upsert(id==null→INSERT, 아니면 갱신) + 첨부 이미지를
 * delete-by-inquiryId 후 재삽입으로 교체한다. 트랜잭션 경계는 application 레이어가 소유한다.
 */
public class ProductFaqRepositoryImpl implements ProductFaqRepository {

    private final ProductFaqJpaRepository faqJpaRepository;
    private final ProductInquiryImageJpaRepository imageJpaRepository;
    private final ProductFaqMapper mapper;

    @Override
    public ProductFaq save(ProductFaq faq) {
        if (faq.id() == null) {
            var saved = faqJpaRepository.save(mapper.toEntity(faq));
            var images = replaceImages(saved.getId(), faq);
            return mapper.toDomain(saved, images);
        }
        var existing = faqJpaRepository.findById(faq.id())
                .orElseThrow(() -> new EntityNotFoundException("해당 상품 문의를 찾을 수 없습니다."));
        mapper.updateEntityFromDomain(existing, faq);
        var saved = faqJpaRepository.save(existing);
        var images = replaceImages(saved.getId(), faq);
        return mapper.toDomain(saved, images);
    }

    @Override
    public Optional<ProductFaq> findById(UUID id) {
        return faqJpaRepository.findById(id)
                .map(e -> mapper.toDomain(e, imageJpaRepository.findByInquiryIdOrderBySortOrder(e.getId())));
    }

    @Override
    public List<ProductFaq> findByProductId(UUID productId) {
        return faqJpaRepository.findByProductId(productId)
                .stream()
                .map(e -> mapper.toDomain(e, imageJpaRepository.findByInquiryIdOrderBySortOrder(e.getId())))
                .toList();
    }

    private List<ProductInquiryImageEntity> replaceImages(
            UUID inquiryId, ProductFaq faq) {
        imageJpaRepository.deleteByInquiryId(inquiryId);
        if (faq.images() == null || faq.images()
                .isEmpty()) {
            return List.of();
        }
        var entities = faq.images()
                .stream()
                .map(ref -> mapper.toImageEntity(inquiryId, ref))
                .toList();
        return imageJpaRepository.saveAll(entities);
    }
}
