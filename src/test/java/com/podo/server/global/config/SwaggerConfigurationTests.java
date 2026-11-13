package com.podo.server.global.config;

import com.podo.server.auth.config.AuthProperties;
import com.podo.server.auth.csrf.CsrfService;
import com.podo.server.auth.kakao.KakaoClient;
import com.podo.server.auth.security.SecurityErrorWriter;
import com.podo.server.auth.service.AuthSessionService;
import com.podo.server.auth.service.OAuthLoginStateService;
import com.podo.server.auth.web.AuthController;
import com.podo.server.auth.withdrawal.WithdrawalService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = SwaggerConfigurationTests.TestConfiguration.class)
@AutoConfigureMockMvc
class SwaggerConfigurationTests {

    @Autowired
    private MockMvc mockMvc;

    // Authentication persistence is covered by AuthIntegrationTests; this context only exercises documentation.
    @MockitoBean
    private AuthSessionService authSessionService;

    @Test
    void groupedDocumentContainsOnlyApiPathsAndExplicitSecurityMetadata() throws Exception {
        mockMvc.perform(get("/v3/api-docs/podo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("PODO API"))
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.bearerFormat").value("opaque"))
                .andExpect(jsonPath("$.components.securitySchemes.RefreshCookie.type").value("apiKey"))
                .andExpect(jsonPath("$.components.securitySchemes.RefreshCookie.in").value("cookie"))
                .andExpect(jsonPath("$.components.securitySchemes.RefreshCookie.name").value("__Host-refresh"))
                .andExpect(jsonPath("$.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/docs-test'].get.security[0].BearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/profile-options'].get").exists())
                .andExpect(jsonPath("$.paths['/api/profile-options'].get.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/internal/docs-test']").doesNotExist());
    }

    @MockitoBean
    private AuthProperties authProperties;
    @MockitoBean
    private OAuthLoginStateService states;
    @MockitoBean
    private KakaoClient kakao;
    @MockitoBean
    private CsrfService csrf;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private WithdrawalService withdrawals;

    @Test
    void withdrawalDocumentsAcceptanceSecurityAndUnavailableConfiguration() throws Exception {
        mockMvc.perform(get("/v3/api-docs/podo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/me'].delete.security[0].BearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/me'].delete.responses['202'].description").value("탈퇴 접수 (처리 중)"))
                .andExpect(jsonPath("$.paths['/api/me'].delete.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/me'].delete.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/me'].delete.responses['503']").exists())
                .andExpect(jsonPath("$.paths['/api/me'].delete.responses['202'].content['application/json'].schema['$ref']")
                        .value("#/components/schemas/WithdrawalResponse"))
                .andExpect(jsonPath("$.components.schemas.WithdrawalResponse.properties.status.enum[0]").value("PROCESSING"));
    }

    @Test
    void swaggerUiAndConfigurationArePublic() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urls[*].url", hasItem("/v3/api-docs/podo")))
                .andExpect(jsonPath("$.persistAuthorization").value(false));

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void exposingDocumentationPreservesApiAuthenticationRules() throws Exception {
        mockMvc.perform(get("/api/docs-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));

        mockMvc.perform(get("/api/profile-options"))
                .andExpect(status().isOk());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
    })
    @Import({OpenApiConfig.class, SecurityConfig.class, SecurityErrorWriter.class, AuthController.class, SampleController.class})
    static class TestConfiguration {
    }

    @RestController
    static class SampleController {

        @GetMapping("/api/docs-test")
        @SecurityRequirement(name = "BearerAuth")
        String privateApi() {
            return "private";
        }

        @GetMapping("/api/profile-options")
        String publicApi() {
            return "public";
        }

        @GetMapping("/internal/docs-test")
        String internalHandler() {
            return "internal";
        }
    }
}
