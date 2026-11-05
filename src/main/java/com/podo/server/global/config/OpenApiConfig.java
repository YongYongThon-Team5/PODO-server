package com.podo.server.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public GroupedOpenApi podoApi() {
        return GroupedOpenApi.builder()
                .group("podo")
                .pathsToMatch("/api/**")
                .build();
    }

    @Bean
    public OpenAPI podoOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PODO API")
                        .description("PODO 백엔드 API 문서")
                        .version("1.0.0"))
                .addServersItem(new Server().url("/"))
                .components(new Components()
                        .addSecuritySchemes(
                                "BearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("opaque")
                                        .description("Opaque access token. Authorize에 'Bearer ' 접두사 없이 토큰 값만 입력하세요.")
                        )
                        .addSecuritySchemes(
                                "RefreshCookie",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.COOKIE)
                                        .name("__Host-refresh")
                                        .description("HttpOnly refresh cookie. Swagger에서 직접 설정할 수 없으며 정상 로그인으로 브라우저에 저장된 쿠키를 사용합니다.")
                        ));
    }
}
