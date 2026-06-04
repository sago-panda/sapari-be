package com.sapari.apiapp.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.sapari.common.web.security.MdcContextFilter;

@Configuration
public class WebFilterConfig {

    /**
     * MDC 컨텍스트 필터를 시큐리티 필터 체인보다 먼저 등록한다.
     */
    @Bean
    public FilterRegistrationBean<MdcContextFilter> mdcContextFilter() {
        FilterRegistrationBean<MdcContextFilter> registration =
                new FilterRegistrationBean<>(new MdcContextFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
