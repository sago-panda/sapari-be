package com.sapari.product.domain.model.faq;

/**
 * 상품 문의(Q&A)의 답변 진행 상태. 엔티티에 varchar로 저장된다.
 */
public enum FaqStatus {
    WAITING,  // 답변 대기
    ANSWERED  // 답변 완료
}
