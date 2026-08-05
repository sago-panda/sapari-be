package com.sapari.customer.infrastructure.external;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 소셜 프로필 이미지 다운로드 전용 HTTP client를 구성한다.
 * redirect 횟수를 직접 제한하기 위해 자동 추적하지 않는다.
 */
@Configuration
@EnableConfigurationProperties(SocialProfileImageDownloadProperties.class)
public class SocialProfileImageDownloadConfig {

    /** 연결·읽기 timeout과 수동 redirect 정책이 적용된 전용 RestClient를 생성한다. */
    @Bean
    public RestClient socialProfileImageRestClient(SocialProfileImageDownloadProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
