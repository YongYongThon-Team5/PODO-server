package com.podo.server.books;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PlaceBookIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean BookProperties properties;
    @MockitoBean YonginLibraryClient provider;
    private final UUID id = UUID.randomUUID();
    private final CuratedBook book = new CuratedBook("합성 책", "합성 저자", "합성 출판사", "2024");

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE app.accounts, app.places CASCADE");
        jdbc.update("INSERT INTO app.places (id,name,address,latitude,longitude,main_activities,environment,status,updated_at) VALUES (?,'합성 장소','용인',37.2,127.2,ARRAY['관찰'],'INDOOR','PUBLISHED',now())", id);
        when(properties.catalog(id)).thenReturn(List.of(book));
        when(properties.configured()).thenReturn(true);
    }

    @Test
    void anonymousPublishedPlaceReturnsProviderMetadataAndNoTransactionDuringHttp() throws Exception {
        when(provider.search(book)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            return new LibrarySearchResult(List.of(new LibrarySearchResult.Row(
                    book.title(), book.author(), book.publisher(), book.publicationYear(), "합성 도서관", null)), 1, false);
        });
        Instant before = Instant.now();
        var response = mvc.perform(get(path(id)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.items[0].title").value(book.title()))
                .andExpect(jsonPath("$.items[0].author").doesNotExist())
                .andExpect(jsonPath("$.items[0].publisher").doesNotExist())
                .andExpect(jsonPath("$.items[0].publicationYear").doesNotExist())
                .andExpect(jsonPath("$.items[0].libraries[0]").value("합성 도서관"))
                .andExpect(jsonPath("$.warnings").isEmpty())
                .andReturn().getResponse();
        UUID.fromString(response.getHeader("X-Request-Id"));
        var body = mapper.readTree(response.getContentAsString());
        assertThat(Instant.parse(body.get("checkedAt").asText())).isBetween(before, Instant.now());
        assertThat(body.get("items").get(0).propertyNames()).containsExactlyInAnyOrder(
                "title", "libraries");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HIDDEN", "DRAFT", "MISSING"})
    void privateAndAbsentPlacesReturn404BeforeConfigurationAndProvider(String status) throws Exception {
        if (status.equals("MISSING")) jdbc.update("DELETE FROM app.places WHERE id = ?", id);
        else jdbc.update("UPDATE app.places SET status = ? WHERE id = ?", status, id);
        clearInvocations(properties, provider);
        mvc.perform(get(path(id))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        verifyNoInteractions(properties, provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-id", "1-1-1-1-1", "00000000000000000000000000000000"})
    void invalidAndNonCanonicalUuidIs400(String raw) throws Exception {
        mvc.perform(get("/api/places/" + raw + "/books"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(provider);
    }

    @Test
    void queriesAndBodiesAreRejected() throws Exception {
        mvc.perform(get(path(id)).param("limit", "1")).andExpect(status().isBadRequest());
        mvc.perform(get(path(id)).param("unknown", "")).andExpect(status().isBadRequest());
        mvc.perform(get(path(id)).content("{}").contentType("application/json")).andExpect(status().isBadRequest());
        mvc.perform(get(path(id)).content(" ")).andExpect(status().isBadRequest());
        verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"&", "&&", "&&&"})
    void nonemptyRawQueryIsRejectedEvenWithoutDecodedParameters(String query) throws Exception {
        when(provider.search(book)).thenReturn(new LibrarySearchResult(List.of(), 0, false));
        clearInvocations(properties, provider);
        mvc.perform(get(URI.create(path(id) + "?" + query)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(properties, provider);
    }

    @Test
    void invalidBearerStillReturns401ForPublicEndpoint() throws Exception {
        mvc.perform(get(path(id)).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
        verifyNoInteractions(provider);
    }

    @Test
    void unconfiguredAndUncuratedAreSuccessfulExplicitEmptyResponsesWithoutProviderCalls() throws Exception {
        when(properties.configured()).thenReturn(false);
        mvc.perform(get(path(id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.warnings[0]").value("PROVIDER_NOT_CONFIGURED"));
        when(properties.catalog(id)).thenReturn(List.of());
        mvc.perform(get(path(id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.warnings[0]").value("NOT_CURATED"));
        verifyNoInteractions(provider);
    }

    private String path(UUID placeId) { return "/api/places/" + placeId + "/books"; }
}
