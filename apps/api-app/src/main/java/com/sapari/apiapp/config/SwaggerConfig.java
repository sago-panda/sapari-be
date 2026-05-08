package com.sapari.apiapp.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Sapari - 사용자 API")
                        .version("v1.0.0")
                        .description("사용자용 라이브 커머스 API"));
    }

    @Bean
    public GroupedOpenApi userApi() {
        return GroupedOpenApi.builder()
                .group("user")
                .displayName("회원 API")
                .pathsToMatch("/api/v1/users/**")
                .build();
    }

    @Bean
    public GroupedOpenApi productApi() {
        return GroupedOpenApi.builder()
                .group("product")
                .displayName("상품 API")
                .pathsToMatch("/api/v1/products/**")
                .build();
    }

    @Bean
    public GroupedOpenApi orderApi() {
        return GroupedOpenApi.builder()
                .group("order")
                .displayName("주문 API")
                .pathsToMatch("/api/v1/orders/**")
                .build();
    }

    @Bean
    public GroupedOpenApi liveApi() {
        return GroupedOpenApi.builder()
                .group("live")
                .displayName("라이브 API")
                .pathsToMatch("/api/v1/lives/**")
                .build();
    }
}