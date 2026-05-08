package com.sapari.adminapp.config;

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
                        .title("Sapari - 어드민 API")
                        .version("v1.0.0")
                        .description("어드민/판매자용 API"));
    }

    @Bean
    public GroupedOpenApi adminUserApi() {
        return GroupedOpenApi.builder()
                .group("admin-user")
                .displayName("회원 관리")
                .pathsToMatch("/admin/v1/users/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminProductApi() {
        return GroupedOpenApi.builder()
                .group("admin-product")
                .displayName("상품 관리")
                .pathsToMatch("/admin/v1/products/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminOrderApi() {
        return GroupedOpenApi.builder()
                .group("admin-order")
                .displayName("주문 관리")
                .pathsToMatch("/admin/v1/orders/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminLiveApi() {
        return GroupedOpenApi.builder()
                .group("admin-live")
                .displayName("라이브 관리")
                .pathsToMatch("/admin/v1/lives/**")
                .build();
    }
}