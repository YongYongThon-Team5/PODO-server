package com.podo.server.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI podoOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PODO API")
                        .description("PODO 백엔드 API 문서")
                        .version("1.0.0"))
                .components(new Components()
                        .addSecuritySchemes(
                                "BearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("opaque")
                        ));
    }
}