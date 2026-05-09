package com.sapari.adminapp.config;

import com.sapari.common.web.swagger.OpenApiDetails;
import com.sapari.common.web.swagger.SwaggerConfigurer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig implements SwaggerConfigurer {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Sapari - 관리자 API")
                        .version("v1.0.0")
                        .description("관리자용 API"));
    }

    @Override
    public List<OpenApiDetails> apiDetails() {
        return List.of(
                new OpenApiDetails("admin-user",    "회원 관리",  List.of("/admin/v1/users/**")),
                new OpenApiDetails("admin-product", "상품 관리",  List.of("/admin/v1/products/**")),
                new OpenApiDetails("admin-order",   "주문 관리",  List.of("/admin/v1/orders/**")),
                new OpenApiDetails("admin-live",    "라이브 관리", List.of("/admin/v1/lives/**"))
        );
    }

    @Bean
    public List<GroupedOpenApi> groupedOpenApis() {
        return groupApis();
    }
}