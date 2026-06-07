package com.sapari.chat.domain.model;

public enum ChatRole {
    BUYER,
    SELLER,
    ADMIN,
    GUEST,
    // 실제 사용자가 아닌 서버 발신 주체 — 입장·퇴장·강퇴·종료 같은 전이성 신호에만 쓴다
    SYSTEM
}
