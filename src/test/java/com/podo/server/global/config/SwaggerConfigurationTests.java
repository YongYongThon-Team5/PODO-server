package com.podo.server.global.config;

import com.podo.server.auth.security.SecurityErrorWriter;
import com.podo.server.auth.service.AuthSessionService;
import com.podo.server.observation.service.ObservationService;
import com.podo.server.observation.web.ObservationController;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
    private ObservationService observationService;

    @Test
    void observationUploadDocumentsExclusiveBinaryArraysAndRequiredIdempotency() throws Exception {
        String upload = "$.paths['/api/visits/{visitId}/observations'].post";
        mockMvc.perform(get("/v3/api-docs/podo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(upload + ".security[0].BearerAuth").isArray())
                .andExpect(jsonPath(upload + ".parameters[?(@.name == 'Idempotency-Key')].required", hasItem(true)))
                .andExpect(jsonPath(upload + ".requestBody.required").value(true))
                .andExpect(jsonPath(upload + ".requestBody.content['multipart/form-data'].schema.oneOf.length()").value(2))
                .andExpect(jsonPath("$.components.schemas.FilesUpload.required[0]").value("files"))
                .andExpect(jsonPath("$.components.schemas.FilesUpload.additionalProperties").value(false))
                .andExpect(jsonPath("$.components.schemas.FilesUpload.properties.files.type").value("array"))
                .andExpect(jsonPath("$.components.schemas.FilesUpload.properties.files.minItems").value(1))
                .andExpect(jsonPath("$.components.schemas.FilesUpload.properties.files.maxItems").value(3))
                .andExpect(jsonPath("$.components.schemas.FilesUpload.properties.files.items.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.FilesUpload.properties.files.items.format").value("binary"))
                .andExpect(jsonPath("$.components.schemas.ImagesUpload.required[0]").value("images"))
                .andExpect(jsonPath("$.components.schemas.ImagesUpload.additionalProperties").value(false))
                .andExpect(jsonPath("$.components.schemas.ImagesUpload.properties.images.type").value("array"))
                .andExpect(jsonPath("$.components.schemas.ImagesUpload.properties.images.minItems").value(1))
                .andExpect(jsonPath("$.components.schemas.ImagesUpload.properties.images.maxItems").value(3))
                .andExpect(jsonPath("$.components.schemas.ImagesUpload.properties.images.items.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.ImagesUpload.properties.images.items.format").value("binary"));
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
    @Import({OpenApiConfig.class, SecurityConfig.class, SecurityErrorWriter.class, SampleController.class, ObservationController.class})
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
