package com.sapari.seller.view;

import java.util.UUID;

/**
 * refreshToken 이 null 이면 재사용 유예(직전 회전 직후의 동시 재발급)라 회전하지 않은 것이다 — refresh 쿠키를 바꾸지 않는다.
 */
public record SellerTokenReissueResult(
        UUID userId,
        String accessToken,
        String refreshToken,
        long refreshTokenMaxAgeSeconds
) {
}
