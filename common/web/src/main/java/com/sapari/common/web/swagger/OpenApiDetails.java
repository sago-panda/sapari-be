package com.sapari.common.web.swagger;

import java.util.List;

public record OpenApiDetails(
        String group,
        String displayName,
        List<String> paths
) {}