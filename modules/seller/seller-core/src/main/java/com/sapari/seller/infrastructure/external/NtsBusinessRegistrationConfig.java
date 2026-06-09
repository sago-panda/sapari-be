package com.sapari.seller.infrastructure.external;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(NtsBusinessRegistrationProperties.class)
public class NtsBusinessRegistrationConfig {

    /**
     * 국세청 사업자 진위확인 API 전용 RestClient를 생성한다.
     */
    @Bean
    public RestClient ntsBusinessRegistrationRestClient(NtsBusinessRegistrationProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
