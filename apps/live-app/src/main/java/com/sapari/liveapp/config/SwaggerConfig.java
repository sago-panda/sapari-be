package com.sapari.liveapp.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import java.util.List;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.common.web.swagger.OpenApiDetails;
import com.sapari.common.web.swagger.SwaggerConfigurer;

@Configuration
public class SwaggerConfig implements SwaggerConfigurer {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Sapari - 라이브 API")
                        .version("v1.0.0")
                        .description("라이브 커머스 방송 API"));
    }

    @Override
    public List<OpenApiDetails> apiDetails() {
        return List.of(
                new OpenApiDetails("live", "라이브 API", List.of("/api/v1/lives/**"))
        );
    }

    @Bean
    public List<GroupedOpenApi> groupedOpenApis() {
        return groupApis();
    }

}
