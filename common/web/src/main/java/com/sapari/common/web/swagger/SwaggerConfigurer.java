package com.sapari.common.web.swagger;

import org.springdoc.core.models.GroupedOpenApi;

import java.util.List;

public interface SwaggerConfigurer {

    List<OpenApiDetails> apiDetails();

    default List<GroupedOpenApi> groupApis() {
        return apiDetails().stream()
                .map(details -> GroupedOpenApi.builder()
                        .group(details.group())
                        .displayName(details.displayName())
                        .pathsToMatch(details.paths().toArray(String[]::new))
                        .build())
                .toList();
    }
}