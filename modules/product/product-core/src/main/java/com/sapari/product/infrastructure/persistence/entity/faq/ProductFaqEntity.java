package com.sapari.product.infrastructure.persistence.entity.faq;

import com.sapari.product.domain.model.faq.FaqStatus;
import com.sapari.product.domain.model.faq.InquiryType;

import com.sapari.storage.db.entity.UuidTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 문의 (Q&A). 비밀글 지원, 판매자 전용 답변.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_faq", schema = "product_schema")
public class ProductFaqEntity extends UuidTimeEntity {

    // ref: products.id. 물리 FK 미사용.
    private UUID productId;

    // ref: users.id (문의 작성자). 물리 FK 미사용.
    private UUID userId;

    @Enumerated(EnumType.STRING)
    private InquiryType inquiryType;

    private String title;

    private String content;

    private Boolean isPrivate;

    @Enumerated(EnumType.STRING)
    private FaqStatus status;

    private String answerContent;

    // ref: users.id (답변자: 판매자 전용). 물리 FK 미사용.
    private UUID answeredBy;

    private Instant answeredAt;

    private Instant deletedAt;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 한다.
     */
    @Builder
    public ProductFaqEntity(UUID productId, UUID userId, InquiryType inquiryType, String title,
                            String content, Boolean isPrivate, FaqStatus status, String answerContent,
                            UUID answeredBy, Instant answeredAt, Instant deletedAt) {
        this.productId = productId;
        this.userId = userId;
        this.inquiryType = inquiryType;
        this.title = title;
        this.content = content;
        this.isPrivate = isPrivate;
        this.status = status;
        this.answerContent = answerContent;
        this.answeredBy = answeredBy;
        this.answeredAt = answeredAt;
        this.deletedAt = deletedAt;
    }

    /**
     * 작성자가 편집하는 문의 본문(유형·제목·내용·비밀글 여부)을 일괄 갱신한다.
     */
    public void updateInquiry(InquiryType inquiryType, String title, String content, Boolean isPrivate) {
        this.inquiryType = inquiryType;
        this.title = title;
        this.content = content;
        this.isPrivate = isPrivate;
    }

    /**
     * 판매자 답변을 반영한다.
     *
     * <p>답변 등록은 상태 전이(예: 답변 완료)와 답변 내용·답변자·답변 시각이 항상 함께 성립하므로,
     * 이들을 묶어 갱신해 답변 메타데이터가 부분적으로 비는 상태를 막는다.
     */
    public void applyAnswer(FaqStatus status, String answerContent, UUID answeredBy, Instant answeredAt) {
        this.status = status;
        this.answerContent = answerContent;
        this.answeredBy = answeredBy;
        this.answeredAt = answeredAt;
    }

    /**
     * 삭제 시각을 기록해 소프트 삭제 처리한다.
     */
    public void markDeleted(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
