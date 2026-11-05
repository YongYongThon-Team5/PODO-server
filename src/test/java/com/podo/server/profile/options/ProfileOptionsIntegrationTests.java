package com.podo.server.profile.options;

import com.podo.server.profile.options.ProfileOptionsProperties.ColorOption;
import com.podo.server.profile.options.ProfileOptionsProperties.Option;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "podo.auth.api-origin=https://podo.test",
        "podo.auth.success-redirect-uri=https://podo.test/auth/success",
        "podo.auth.failure-redirect-uri=https://podo.test/auth/error",
        "podo.auth.csrf-signing-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
@Testcontainers
class ProfileOptionsIntegrationTests {
    private static final String PATH = "/api/profile-options";
    private static final ColorOption TEST_COLOR = new ColorOption("TEST_COLOR", "시험 색상", "#3D8bA8", true);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ProfileOptionsProperties properties;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE app.tags");
        when(properties.ageBands()).thenReturn(List.of());
        when(properties.colors()).thenReturn(List.of());
    }

    @Test
    void unconfiguredCatalogReturns503WithoutCreatingTags() throws Exception {
        expectNotConfigured();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.tags", Long.class)).isZero();
    }

    @Test
    void publicResponseIncludesOrderedTagsInactiveOptionsAndFixedPoliciesWithoutWriting() throws Exception {
        UUID interestZ = tag("INTEREST", "TEST_INTEREST_Z", "시험 관심사 Z", true);
        UUID interestA = tag("INTEREST", "TEST_INTEREST_A", "시험 관심사 A", false);
        UUID bookZ = tag("BOOK_GENRE", "TEST_BOOK_Z", "시험 도서 Z", false);
        UUID bookA = tag("BOOK_GENRE", "TEST_BOOK_A", "시험 도서 A", true);
        tag("PURPOSE", "TEST_PURPOSE", "시험 방문 목적", true);
        when(properties.ageBands()).thenReturn(List.of(new Option("TEST_AGE", "시험 연령", false)));
        when(properties.colors()).thenReturn(List.of(TEST_COLOR,
                new ColorOption("TEST_RETIRED_COLOR", "시험 이전 색상", "#ffffff", false)));
        var tagsBefore = jdbc.queryForList("SELECT * FROM app.tags ORDER BY id");
        var countsBefore = rowCounts();

        var response = mvc.perform(get(PATH)).andExpect(status().isOk()).andReturn().getResponse();

        assertHeaders(response);
        assertThat(json(response)).isEqualTo(mapper.readTree("""
                {
                  "interestCategories": [
                    {"id":"%s","code":"TEST_INTEREST_A","label":"시험 관심사 A","active":false},
                    {"id":"%s","code":"TEST_INTEREST_Z","label":"시험 관심사 Z","active":true}
                  ],
                  "bookGenres": [
                    {"id":"%s","code":"TEST_BOOK_A","label":"시험 도서 A","active":true},
                    {"id":"%s","code":"TEST_BOOK_Z","label":"시험 도서 Z","active":false}
                  ],
                  "ageBands": [{"code":"TEST_AGE","label":"시험 연령","active":false}],
                  "colors": [
                    {"code":"TEST_COLOR","label":"시험 색상","hex":"#3D8bA8","active":true},
                    {"code":"TEST_RETIRED_COLOR","label":"시험 이전 색상","hex":"#ffffff","active":false}
                  ],
                  "colorPolicy": {"allowDuplicates":false},
                  "photoUploadPolicy": {
                    "allowedMimeTypes":["image/jpeg","image/png","image/webp"],
                    "maxBytes":5242880,
                    "maxPixels":16777216
                  }
                }
                """.formatted(interestA, interestZ, bookA, bookZ)));
        assertThat(jdbc.queryForList("SELECT * FROM app.tags ORDER BY id")).isEqualTo(tagsBefore);
        assertThat(rowCounts()).isEqualTo(countsBefore);
    }

    @Test
    void optionalAgeAndTagCataloguesCanBeEmptyArrays() throws Exception {
        when(properties.colors()).thenReturn(List.of(TEST_COLOR));

        var response = mvc.perform(get(PATH)).andExpect(status().isOk()).andReturn().getResponse();

        assertHeaders(response);
        JsonNode body = json(response);
        for (String field : List.of("interestCategories", "bookGenres", "ageBands")) {
            assertThat(body.get(field).isArray()).isTrue();
            assertThat(body.get(field).isEmpty()).isTrue();
        }
        assertThat(body.get("colors").size()).isEqualTo(1);
    }

    @Test
    void malformedOptionCodesAndLabelsReturn503AtRequestTime() throws Exception {
        when(properties.colors()).thenReturn(List.of(TEST_COLOR));
        for (Option invalid : List.of(
                new Option("", "시험 연령", true),
                new Option(" ", "시험 연령", true),
                new Option("TEST_" + "A".repeat(60), "시험 연령", true),
                new Option("TEST_AGE", " ", true))) {
            when(properties.ageBands()).thenReturn(List.of(invalid));
            expectNotConfigured();
        }
    }

    @Test
    void invalidColorAndDuplicateOptionCodesReturn503AtRequestTime() throws Exception {
        for (ColorOption invalid : List.of(
                new ColorOption("TEST_COLOR", "시험 색상", "#abc", true),
                new ColorOption("TEST_COLOR", "시험 색상", "3D8BA8", true),
                new ColorOption("TEST_COLOR", "시험 색상", "#GGGGGG", true),
                new ColorOption(" ", "시험 색상", "#123456", true),
                new ColorOption("TEST_COLOR", " ", "#123456", true))) {
            when(properties.colors()).thenReturn(List.of(invalid));
            expectNotConfigured();
        }
        when(properties.colors()).thenReturn(List.of(TEST_COLOR, TEST_COLOR));
        expectNotConfigured();
        when(properties.colors()).thenReturn(List.of(TEST_COLOR));
        when(properties.ageBands()).thenReturn(List.of(
                new Option("TEST_AGE", "시험 연령 A", true),
                new Option("TEST_AGE", "시험 연령 B", false)));
        expectNotConfigured();
    }

    @Test
    void malformedStoredTagsReturn503WithoutChangingStoredRows() throws Exception {
        when(properties.colors()).thenReturn(List.of(TEST_COLOR));
        for (String kind : List.of("INTEREST", "BOOK_GENRE")) {
            for (boolean blankCode : List.of(true, false)) {
                UUID id = tag(kind, blankCode ? " " : "TEST_TAG", blankCode ? "시험 태그" : " ", false);
                var before = jdbc.queryForList("SELECT * FROM app.tags ORDER BY id");
                expectNotConfigured();
                assertThat(jdbc.queryForList("SELECT * FROM app.tags ORDER BY id")).isEqualTo(before);
                jdbc.update("DELETE FROM app.tags WHERE id = ?", id);
            }
        }
    }

    @Test
    void unexpectedQueriesAndAnyNonemptyGetBodyReturn400() throws Exception {
        when(properties.colors()).thenReturn(List.of(TEST_COLOR));
        mvc.perform(get(PATH).param("unexpected", "x")).andExpect(status().isBadRequest());
        for (String body : List.of("{}", " ", "&")) {
            mvc.perform(get(PATH).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(PATH).contentType("application/x-www-form-urlencoded").content("&"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.tags", Long.class)).isZero();
    }

    @Test
    void invalidBearerIsRejectedOnThePublicEndpoint() throws Exception {
        when(properties.colors()).thenReturn(List.of(TEST_COLOR));
        var response = mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertHeaders(response);
        assertThat(json(response).get("error").get("code").asText()).isEqualTo("INVALID_TOKEN");
        assertThat(rowCounts()).containsExactly(0L, 0L, 0L);
    }

    private UUID tag(String kind, String code, String label, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.tags (id, kind, code, label, active) VALUES (?, ?, ?, ?, ?)",
                id, kind, code, label, active);
        return id;
    }

    private List<Long> rowCounts() {
        return List.of(
                jdbc.queryForObject("SELECT count(*) FROM app.tags", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM app.accounts", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM app.child_profiles", Long.class));
    }

    private void expectNotConfigured() throws Exception {
        var response = mvc.perform(get(PATH)).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertHeaders(response);
        assertThat(json(response).get("error").get("code").asText()).isEqualTo("OPTIONS_NOT_CONFIGURED");
    }

    private void assertHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsString());
    }
}
