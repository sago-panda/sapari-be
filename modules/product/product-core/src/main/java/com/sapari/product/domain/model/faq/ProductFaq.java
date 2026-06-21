package com.sapari.product.domain.model.faq;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/**
 * 상품 문의(Q&A) 애그리거트 루트. 첨부 이미지를 자식으로 보유.
 */
@Builder(toBuilder = true)
public record ProductFaq(
        UUID id,
        UUID productId,
        UUID userId,
        InquiryType inquiryType,
        String title,
        String content,
        boolean isPrivate,
        FaqStatus status,
        String answerContent,
        UUID answeredBy,
        Instant answeredAt,
        Instant deletedAt,
        List<InquiryImageRef> images,
        Instant createdAt,
        Instant updatedAt
) {

    public ProductFaq {
        if (productId == null) {
            throw new IllegalArgumentException("productId는 필수입니다.");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }
        if (inquiryType == null) {
            throw new IllegalArgumentException("inquiryType은 필수입니다.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title은 필수입니다.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content는 필수입니다.");
        }
        images = images == null ? List.of() : List.copyOf(images);
    }

    /**
     * 신규 문의를 생성한다. productId·userId·inquiryType·title·content 필수.
     *
     * <p>생성 직후 상태는 항상 답변 대기({@link FaqStatus#WAITING}). 첨부 이미지는 누락 시 빈 목록으로,
     * 전달되면 불변 복사본으로 보관한다.
     */
    public static ProductFaq create(UUID productId, UUID userId, InquiryType inquiryType,
                                    String title, String content, boolean isPrivate, List<InquiryImageRef> images) {
        return ProductFaq.builder()
                .productId(productId)
                .userId(userId)
                .inquiryType(inquiryType)
                .title(title)
                .content(content)
                .isPrivate(isPrivate)
                .status(FaqStatus.WAITING)
                .images(images)
                .build();
    }

    /**
     * 문의에 답변을 달고 답변 완료({@link FaqStatus#ANSWERED})로 전이한 새 인스턴스를 반환한다. answerContent·answeredBy 필수.
     */
    public ProductFaq answer(String answerContent, UUID answeredBy, Instant now) {
        if (answerContent == null || answerContent.isBlank()) {
            throw new IllegalArgumentException("answerContent는 필수입니다.");
        }
        if (answeredBy == null) {
            throw new IllegalArgumentException("answeredBy는 필수입니다.");
        }
        return toBuilder()
                .status(FaqStatus.ANSWERED)
                .answerContent(answerContent)
                .answeredBy(answeredBy)
                .answeredAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * deletedAt을 채워 소프트 딜리트한 새 인스턴스를 반환한다(행은 보존).
     */
    public ProductFaq delete(Instant now) {
        return toBuilder()
                .deletedAt(now)
                .updatedAt(now)
                .build();
    }
}
