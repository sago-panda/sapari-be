package com.sapari.seller.command;

/**
 * 판매자 프로필 이미지 변경 요청이다.
 * accessToken으로 판매자 본인 여부를 확정하고, 파일 메타데이터는 user-core 검증 경계로 전달한다.
 */
public record SellerProfileImageChangeCommand(
        String accessToken,
        String originalFilename,
        String contentType,
        byte[] content
) {
}
