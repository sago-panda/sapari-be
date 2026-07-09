package com.sapari.productapp.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.sapari.common.web.security.CurrentUserIdArgumentResolver;

/**
 * 웹 MVC 설정. 인증 주체의 userId를 컨트롤러 파라미터로 주입하는 리졸버를 등록한다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * {@link com.sapari.common.web.security.CurrentUserId} 파라미터 리졸버를 등록한다.
     *
     * @param resolvers 등록 대상 리졸버 목록
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserIdArgumentResolver());
    }
}
