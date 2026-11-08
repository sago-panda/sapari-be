package com.sapari.live.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * 썸네일 객체의 위치. 경로는 서버가 정한다 — 사용자 파일명은 쓰지 않는다.
 *
 * <p>업로드 영역({@code uploads/thumbnails/})은 비공개이고, 인프라 검증(별도 티켓)이 내용을 확인한 뒤 같은
 * {@code {sellerId}/{objectId}} 를 검증 완료 영역({@code thumbnails/})으로 옮긴다. 두 경로는 영역 접두사만
 * 다르다 — 그래야 발급 시점에 최종 주소가 확정되고, 판매자 ID 로 본인 여부를 문자열만으로 판정할 수 있다.
 */
public record ThumbnailKey(UUID sellerId, UUID objectId) {

    static final String UPLOAD_AREA = "uploads/thumbnails/";
    static final String PUBLIC_AREA = "thumbnails/";

    public ThumbnailKey {
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(objectId, "objectId");
    }

    public String uploadKey() {
        return UPLOAD_AREA + sellerId + "/" + objectId;
    }

    public String publicKey() {
        return PUBLIC_AREA + sellerId + "/" + objectId;
    }
}
