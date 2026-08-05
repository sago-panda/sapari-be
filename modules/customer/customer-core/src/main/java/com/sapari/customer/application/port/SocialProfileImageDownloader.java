package com.sapari.customer.application.port;

import java.util.Optional;

import com.sapari.customer.application.dto.SocialProfileImageDownloadResult;
import com.sapari.user.model.ProviderType;

/**
 * 서버가 OAuth callback에서 받은 provider 프로필 이미지 URL만 다운로드하는 port다.
 * 구현체는 URL allowlist, redirect, timeout, size limit 같은 SSRF 방어 정책을 적용한다.
 */
public interface SocialProfileImageDownloader {

    Optional<SocialProfileImageDownloadResult> download(ProviderType provider, String providerProfileImageUrl);
}
