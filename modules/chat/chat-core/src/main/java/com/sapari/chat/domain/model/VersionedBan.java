package com.sapari.chat.domain.model;

/**
 * 미러에 실을 밴과, 정본 잠금 안에서 집은 그 변경의 번호.
 *
 * <p>둘이 한 값인 이유: 번호는 잠금 안에서만 의미가 있고 미러 쓰기는 잠금 밖에서 일어난다. 따로 들고
 * 다니면 다른 트랜잭션의 번호와 섞일 자리가 생긴다.
 */
public record VersionedBan(ChatBan ban, long version) {

    public VersionedBan {
        if (ban == null) {
            throw new IllegalArgumentException("ban은 필수입니다.");
        }
    }
}
