package com.sapari.productapp.config;

import com.sapari.common.web.swagger.OpenApiDetails;
import com.sapari.common.web.swagger.SwaggerConfigurer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig implements SwaggerConfigurer {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Sapari - 상품 API")
                        .version("v1.0.0")
                        .description("상품 전시 REST API"));
    }

    @Override
    public List<OpenApiDetails> apiDetails() {
        return List.of(
                new OpenApiDetails("product", "상품 API", List.of("/api/v1/products/**"))
        );
    }

    @Bean
    public List<GroupedOpenApi> groupedOpenApis() {
        return groupApis();
    }
}