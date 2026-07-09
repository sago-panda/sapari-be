package com.sapari.user.application.dto;

/**
 * 외부 저장소 업로드가 완료된 뒤 user 도메인에 남길 최소 증적이다.
 * 공개 URL은 조회 응답을 만들 때 계산하므로 영속 상태에는 내부 object key만 저장한다.
 */
public record StoredProfileImage(
        String key,
        String contentType,
        long size
) {
}
