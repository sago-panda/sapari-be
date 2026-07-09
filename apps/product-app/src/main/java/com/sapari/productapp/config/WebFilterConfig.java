package com.sapari.productapp.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.sapari.common.web.security.MdcContextFilter;

/**
 * 서블릿 필터 설정. 요청 추적용 MDC 컨텍스트 필터를 등록한다.
 */
@Configuration
public class WebFilterConfig {

    /**
     * MDC 컨텍스트 필터를 시큐리티 필터 체인보다 먼저 등록한다(requestId 등 로그 컨텍스트 주입·정리).
     *
     * @return MDC 필터 등록 빈
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
