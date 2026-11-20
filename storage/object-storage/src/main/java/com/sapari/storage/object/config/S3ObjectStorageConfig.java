package com.sapari.storage.object.config;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * S3-compatible object storage client를 공통 Bean으로 제공한다.
 * 실행 앱의 application.yml이 endpoint/credential 값을 제공하고, 도메인 모듈은 bucket/key 정책만 결정한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3ObjectStorageProperties.class)
public class S3ObjectStorageConfig {

    @Bean
    S3Client objectStorageS3Client(S3ObjectStorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccessEnabled())
                        .build())
                .build();
    }
}
