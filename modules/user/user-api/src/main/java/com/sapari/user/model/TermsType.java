package com.sapari.user.model;

/**
 * 회원가입 약관 이력에서 허용하는 약관 타입이다.
 * Sapari는 필수 동의를 PRIVACY 타입의 단일 약관 번들로 운영하고, 필수 내용 변경은 새 타입 추가가 아니라 PRIVACY의 새 version으로 처리한다.
 */
public enum TermsType {
    PRIVACY,
    MARKETING
}
