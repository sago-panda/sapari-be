package com.sapari.apiapp.config;

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
                        .title("Sapari - 사용자 API")
                        .version("v1.0.0")
                        .description("사용자용 라이브 커머스 API"));
    }

    @Override
    public List<OpenApiDetails> apiDetails() {
        return List.of(
                new OpenApiDetails(
                        "user",
                        "회원 API",
                        List.of(
                                "/api/v1/customers/auth/**",
                                "/api/v1/sellers/auth/**"
                        )
                ),
                new OpenApiDetails("product", "상품 API",  List.of("/api/v1/products/**")),
                new OpenApiDetails("order",   "주문 API",  List.of("/api/v1/orders/**")),
                new OpenApiDetails("live",    "라이브 API", List.of("/api/v1/lives/**"))
        );
    }

    @Bean
    public List<GroupedOpenApi> groupedOpenApis() {
        return groupApis();
    }
}