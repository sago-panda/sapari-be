package com.sapari.live.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.live.application.port.ThumbnailUploadSigner;
import com.sapari.live.domain.model.ThumbnailUrlRule;
import com.sapari.live.infrastructure.storage.S3PostPolicySigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ThumbnailProperties.class)
public class ThumbnailConfig {

    /** 잘못된 CDN 주소(https 아님·호스트 없음)는 여기서 기동 실패한다. */
    @Bean
    public ThumbnailUrlRule thumbnailUrlRule(ThumbnailProperties properties) {
        return ThumbnailUrlRule.of(properties.cdnBaseUrl());
    }

    @Bean
    public ThumbnailUploadSigner thumbnailUploadSigner(ThumbnailProperties properties) {
        return new S3PostPolicySigner(properties.bucket(), properties.region(),
                properties.accessKey(), properties.secretKey());
    }
}
