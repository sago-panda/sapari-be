package com.sapari.product.domain.model.faq;

/**
 * 상품 문의 유형. 구매 전 Q&A를 주제별로 분류한다.
 */
public enum InquiryType {
    PRODUCT,  // 상품
    DELIVERY, // 배송
    EXCHANGE, // 교환
    REFUND,   // 환불
    ETC       // 기타
}
