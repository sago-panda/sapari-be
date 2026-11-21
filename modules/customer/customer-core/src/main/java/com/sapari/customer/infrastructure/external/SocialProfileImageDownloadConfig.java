package com.sapari.customer.infrastructure.external;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 소셜 프로필 이미지 다운로드 전용 Apache HTTP client를 구성한다.
 * redirect 목적지를 애플리케이션에서 매번 재검증하고 횟수를 제한하기 위해 자동 추적하지 않는다.
 */
@Configuration
@EnableConfigurationProperties(SocialProfileImageDownloadProperties.class)
public class SocialProfileImageDownloadConfig {

    /** 최초 URL과 redirect URL에 HTTP/HTTPS 표준 목적지 정책을 적용한다. */
    @Bean
    public SocialProfileImageUriPolicy socialProfileImageUriPolicy() {
        return new SocialProfileImageUriPolicy();
    }

    /** DNS A/AAAA 전체에서 내부·로컬·특수 주소를 거부하는 정책을 생성한다. */
    @Bean
    public PublicInternetAddressPolicy publicInternetAddressPolicy() {
        return new PublicInternetAddressPolicy();
    }

    /** 검증한 DNS 주소를 실제 Apache 연결에도 사용하는 resolver를 생성한다. */
    @Bean
    public SocialProfileImageDnsResolver socialProfileImageDnsResolver(PublicInternetAddressPolicy addressPolicy) {
        return new SocialProfileImageDnsResolver(addressPolicy);
    }

    /**
     * custom DNS resolver와 connect/read timeout을 연결하고 자동 redirect를 끈 전용 client를 생성한다.
     * system property 기반 proxy는 목적지 검증을 우회할 수 있어 활성화하지 않는다.
     */
    @Bean(destroyMethod = "close")
    public CloseableHttpClient socialProfileImageHttpClient(
            SocialProfileImageDnsResolver dnsResolver,
            SocialProfileImageDownloadProperties properties
    ) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                .setSocketTimeout(Timeout.of(properties.readTimeout()))
                .build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                .setDefaultConnectionConfig(connectionConfig)
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling()
                .build();
    }

    /** 연결·읽기 timeout과 수동 redirect 정책이 적용된 전용 RestClient를 생성한다. */
    @Bean
    public RestClient socialProfileImageRestClient(
            @Qualifier("socialProfileImageHttpClient") CloseableHttpClient httpClient,
            SocialProfileImageDownloadProperties properties
    ) {
        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
