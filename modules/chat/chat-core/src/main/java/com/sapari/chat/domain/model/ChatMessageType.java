package com.sapari.chat.domain.model;

public sealed interface ChatMessageType
        permits ChatMessageType.Normal, ChatMessageType.Notice, ChatMessageType.System {

    record Normal() implements ChatMessageType { }

    // 공지 — 발신 권한(SELLER·ADMIN)은 ChatPermissionPolicy가 강제한다
    record Notice() implements ChatMessageType { }

    // 전이성 신호. code가 신호 종류(입장·강퇴·종료 등) — MongoDB에 영속하지 않고 각 Pod가 로컬 렌더만 한다
    record System(String code) implements ChatMessageType {
        public System {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("System 메시지 code는 필수입니다.");
            }
        }
    }
}
