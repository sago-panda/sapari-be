package com.sapari.live.view;

import java.time.Instant;
import java.util.Map;

/**
 * 썸네일 업로드 허가.
 *
 * @param uploadUrl    {@code fields} 전부와 마지막에 {@code file} 을 multipart/form-data 로 POST 할 저장소 주소
 * @param fields       정책·서명을 포함한 폼 필드(순서 유지)
 * @param expiresAt    허가 만료 시각
 * @param thumbnailUrl 검증 후 노출될 최종 주소 — 방 생성·수정 요청의 thumbnailUrl 에 그대로 넣는다.
 *                     인프라 검증이 끝나기 전에는 404 일 수 있다.
 */
public record ThumbnailUploadView(
        String uploadUrl,
        Map<String, String> fields,
        Instant expiresAt,
        String thumbnailUrl
) {
}
