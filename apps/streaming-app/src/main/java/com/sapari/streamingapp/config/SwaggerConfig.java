package com.sapari.streamingapp.config;

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
                        .title("Sapari - 스트리밍 API")
                        .version("v1.0.0")
                        .description("라이브 송출/시청 REST API"));
    }

    @Override
    public List<OpenApiDetails> apiDetails() {
        return List.of(
                new OpenApiDetails("stream", "스트리밍 API", List.of("/api/v1/streams/**"))
        );
    }

    @Bean
    public List<GroupedOpenApi> groupedOpenApis() {
        return groupApis();
    }
}