package com.sapari.customer.infrastructure.external.sms;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.solapi.sdk.SolapiClient;

@Configuration
@EnableConfigurationProperties(SolapiSmsProperties.class)
public class SolapiSmsConfig {

    /**
     * SOLAPI 공식 SDK client를 감싼 발송 client bean을 생성한다.
     */
    @Bean
    SolapiMessageClient solapiMessageClient(SolapiSmsProperties properties) {
        return new SolapiSdkMessageClient(
                SolapiClient.INSTANCE.createInstance(properties.apiKey(), properties.apiSecret())
        );
    }
}
