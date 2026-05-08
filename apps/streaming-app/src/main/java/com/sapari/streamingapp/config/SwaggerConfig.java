package com.sapari.streamingapp.config;

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
                        .title("Sapari - 스트리밍 API")
                        .version("v1.0.0")
                        .description("라이브 송출/시청 REST API"));
    }

    @Bean
    public GroupedOpenApi streamApi() {
        return GroupedOpenApi.builder()
                .group("stream")
                .displayName("스트리밍 API")
                .pathsToMatch("/api/v1/streams/**")
                .build();
    }
}