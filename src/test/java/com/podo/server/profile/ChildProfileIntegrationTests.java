package com.podo.server.profile;

import com.podo.server.auth.service.AuthSessionService;
import com.podo.server.profile.options.ProfileOptionsProperties;
import com.podo.server.profile.options.ProfileOptionsProperties.ColorOption;
import com.podo.server.profile.options.ProfileOptionsProperties.Option;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "podo.auth.api-origin=https://podo.test",
        "podo.auth.success-redirect-uri=https://podo.test/auth/success",
        "podo.auth.failure-redirect-uri=https://podo.test/auth/error",
        "podo.auth.csrf-signing-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
@Testcontainers
class ChildProfileIntegrationTests {
    private static final String PATH = "/api/child-profiles";
    private static final List<ColorOption> COLORS = List.of(
            new ColorOption("TEST_RED", "시험 빨강", "#ff0000", true),
            new ColorOption("TEST_BLUE", "시험 파랑", "#0000ff", true),
            new ColorOption("TEST_GREEN", "시험 초록", "#00ff00", true));

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
    @Autowired AuthSessionService sessions;
    @MockitoBean ProfileOptionsProperties properties;

    private Actor owner;
    private UUID interest;
    private UUID book;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE app.child_profiles, app.profile_images, app.tags, app.oauth_login_requests, "
                + "app.auth_tokens, app.auth_sessions, app.accounts CASCADE");
        when(properties.colors()).thenReturn(COLORS);
        when(properties.ageBands()).thenReturn(List.of(new Option("TEST_AGE", "시험 연령", true)));
        owner = actor();
        interest = tag("INTEREST", true);
        book = tag("BOOK_GENRE", true);
    }

    @Test
    void authenticationIsRequiredAndEmptyListHasAnExplicitNullCursor() throws Exception {
        error(mvc.perform(get(PATH)).andReturn().getResponse(), 401, "AUTHENTICATION_REQUIRED");
        error(mvc.perform(post(PATH).contentType("application/json").content(body("TEST_RED")))
                .andReturn().getResponse(), 401, "AUTHENTICATION_REQUIRED");
        error(mvc.perform(patch(PATH + "/" + UUID.randomUUID()).contentType("application/json")
                .content("{\"nickname\":\"시험\"}")).andReturn().getResponse(), 401, "AUTHENTICATION_REQUIRED");
        error(mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
                .andReturn().getResponse(), 401, "INVALID_TOKEN");
        JsonNode page = success(get(PATH), owner, null, 200);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.get("items").isArray()).isTrue();
        assertThat(page.get("items").isEmpty()).isTrue();
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void createAcceptsOmittedOrNullOptionalFieldsAndReturnsTheExactPublicShape() throws Exception {
        JsonNode omitted = create(owner, body("TEST_RED"));
        JsonNode explicitNull = create(owner, body("TEST_BLUE", "ageBandCode", null, "profileImageId", null));
        for (JsonNode profile : List.of(omitted, explicitNull)) {
            assertProfileShape(profile);
            assertThat(profile.get("ageBandCode").isNull()).isTrue();
            assertThat(profile.get("profileImageId").isNull()).isTrue();
            assertThat(profile.get("interestCategoryIds").isEmpty()).isTrue();
            assertThat(profile.get("bookGenreIds").isEmpty()).isTrue();
        }
        JsonNode page = success(get(PATH), owner, null, 200);
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void nicknameAndColorLengthsCountUnicodeCharacters() throws Exception {
        String color = "🎨".repeat(64);
        String nickname = "🙂".repeat(30);
        when(properties.colors()).thenReturn(List.of(new ColorOption(color, "시험 색상", "#123456", true)));
        JsonNode profile = create(owner, body(color, "nickname", nickname));
        assertThat(profile.get("nickname").asText()).isEqualTo(nickname);
        assertThat(profile.get("colorCode").asText()).isEqualTo(color);
        assertThat(jdbc.queryForObject("SELECT char_length(nickname) FROM app.child_profiles WHERE id = ?",
                Integer.class, UUID.fromString(profile.get("id").asText()))).isEqualTo(30);
    }

    @Test
    void createPersistsSelectedOptionsAndPatchReplacesArraysWithoutChangingIdentity() throws Exception {
        JsonNode created = create(owner, body("TEST_RED", "ageBandCode", "TEST_AGE",
                "interestCategoryIds", List.of(interest), "bookGenreIds", List.of(book)));
        assertThat(created.get("interestCategoryIds").get(0).asText()).isEqualTo(interest.toString());
        assertThat(created.get("bookGenreIds").get(0).asText()).isEqualTo(book.toString());
        UUID replacement = tag("INTEREST", true);
        JsonNode updated = success(patch(profilePath(created)), owner,
                mapper.writeValueAsString(Map.of("nickname", "수정한 아이", "colorCode", "TEST_GREEN",
                        "interestCategoryIds", List.of(replacement), "bookGenreIds", List.of())), 200);
        assertProfileShape(updated);
        assertThat(updated.get("id")).isEqualTo(created.get("id"));
        assertThat(updated.get("createdAt")).isEqualTo(created.get("createdAt"));
        assertThat(Instant.parse(updated.get("updatedAt").asText()))
                .isAfterOrEqualTo(Instant.parse(created.get("updatedAt").asText()));
        assertThat(updated.get("nickname").asText()).isEqualTo("수정한 아이");
        assertThat(updated.get("colorCode").asText()).isEqualTo("TEST_GREEN");
        assertThat(updated.get("ageBandCode").asText()).isEqualTo("TEST_AGE");
        assertThat(updated.get("interestCategoryIds")).isEqualTo(mapper.valueToTree(List.of(replacement)));
        assertThat(updated.get("bookGenreIds").isEmpty()).isTrue();
    }

    @Test
    void createRejectsMissingFieldsWrongTypesDuplicatesAndServerManagedFields() throws Exception {
        for (String required : List.of("nickname", "colorCode", "interestCategoryIds", "bookGenreIds")) {
            ObjectNode missing = (ObjectNode) mapper.readTree(body("TEST_RED"));
            missing.remove(required);
            error(post(PATH), owner, mapper.writeValueAsString(missing), 400, "INVALID_REQUEST");
        }
        for (String invalid : List.of(
                body("TEST_RED", "nickname", null), body("TEST_RED", "nickname", 123),
                body("TEST_RED", "nickname", ""), body("TEST_RED", "nickname", "가".repeat(31)),
                body("TEST_RED", "colorCode", null), body("TEST_RED", "colorCode", true),
                body("TEST_RED", "interestCategoryIds", null), body("TEST_RED", "bookGenreIds", null),
                body("TEST_RED", "interestCategoryIds", List.of(interest, interest)),
                body("TEST_RED", "bookGenreIds", List.of(1)),
                body("TEST_RED", "ageBandCode", 7), body("TEST_RED", "profileImageId", "bad-uuid"),
                body("TEST_RED", "id", UUID.randomUUID()), body("TEST_RED", "accountId", owner.id()))) {
            error(post(PATH), owner, invalid, 400, "INVALID_REQUEST");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.child_profiles", Long.class)).isZero();
    }

    @Test
    void patchRequiresKnownFieldsAndRejectsNullRequiredValuesAndWrongTypes() throws Exception {
        JsonNode created = create(owner, body("TEST_RED"));
        for (String invalid : List.of("{}", "[]", "null", "{\"nickname\":null}",
                "{\"colorCode\":null}", "{\"nickname\":false}", "{\"ageBandCode\":[]}",
                "{\"profileImageId\":17}", "{\"interestCategoryIds\":null}", "{\"bookGenreIds\":null}",
                "{\"nickname\":\"시험\",\"createdAt\":\"2026-11-05T00:00:00Z\"}",
                "{\"bookGenreIds\":[\"%s\",\"%s\"]}".formatted(book, book))) {
            error(patch(profilePath(created)), owner, invalid, 400, "INVALID_REQUEST");
        }
        assertThat(success(get(PATH), owner, null, 200).get("items").get(0)).isEqualTo(created);
    }

    @Test
    void selectionsMustExistBeActiveAndMatchTheirTagKind() throws Exception {
        UUID inactiveInterest = tag("INTEREST", false);
        UUID inactiveBook = tag("BOOK_GENRE", false);
        UUID purpose = tag("PURPOSE", true);
        for (String invalid : List.of(body("TEST_UNKNOWN"),
                body("TEST_RED", "ageBandCode", "TEST_UNKNOWN"),
                body("TEST_RED", "interestCategoryIds", List.of(UUID.randomUUID())),
                body("TEST_RED", "interestCategoryIds", List.of(inactiveInterest)),
                body("TEST_RED", "bookGenreIds", List.of(inactiveBook)))) {
            error(post(PATH), owner, invalid, 400, "INVALID_OPTION");
        }
        for (String invalid : List.of(body("TEST_RED", "interestCategoryIds", List.of(book)),
                body("TEST_RED", "bookGenreIds", List.of(interest)),
                body("TEST_RED", "interestCategoryIds", List.of(purpose)))) {
            error(post(PATH), owner, invalid, 400, "INVALID_TAG_KIND");
        }
    }

    @Test
    void colorsAreUniqueWithinAnAccountButReusableByAnotherAccount() throws Exception {
        JsonNode red = create(owner, body("TEST_RED"));
        JsonNode blue = create(owner, body("TEST_BLUE"));
        error(post(PATH), owner, body("TEST_RED"), 409, "COLOR_ALREADY_USED");
        error(patch(profilePath(blue)), owner, "{\"colorCode\":\"TEST_RED\"}", 409, "COLOR_ALREADY_USED");
        assertThat(success(patch(profilePath(red)), owner, "{\"colorCode\":\"TEST_RED\"}", 200)
                .get("colorCode").asText()).isEqualTo("TEST_RED");
        Actor other = actor();
        create(other, body("TEST_RED"));
        assertThat(success(get(PATH), owner, null, 200).get("items").size()).isEqualTo(2);
        assertThat(success(get(PATH), other, null, 200).get("items").size()).isEqualTo(1);
    }

    @Test
    void inactiveSelectionsAreRetainedButCannotBeNewlyAdded() throws Exception {
        JsonNode created = create(owner, body("TEST_RED", "ageBandCode", "TEST_AGE",
                "interestCategoryIds", List.of(interest), "bookGenreIds", List.of(book)));
        jdbc.update("UPDATE app.tags SET active = false");
        when(properties.colors()).thenReturn(List.of(new ColorOption("TEST_RED", "이전 빨강", "#ff0000", false),
                COLORS.get(1), new ColorOption("TEST_GREEN", "이전 초록", "#00ff00", false)));
        when(properties.ageBands()).thenReturn(List.of(new Option("TEST_AGE", "이전 연령", false),
                new Option("TEST_RETIRED_AGE", "다른 이전 연령", false)));
        JsonNode retained = success(patch(profilePath(created)), owner, body("TEST_RED", "ageBandCode", "TEST_AGE",
                "interestCategoryIds", List.of(interest), "bookGenreIds", List.of(book)), 200);
        assertThat(retained.get("interestCategoryIds")).isEqualTo(created.get("interestCategoryIds"));
        for (String invalid : List.of("{\"colorCode\":\"TEST_GREEN\"}",
                "{\"ageBandCode\":\"TEST_RETIRED_AGE\"}",
                "{\"interestCategoryIds\":[\"%s\",\"%s\"]}".formatted(interest, tag("INTEREST", false)))) {
            error(patch(profilePath(created)), owner, invalid, 400, "INVALID_OPTION");
        }
        error(post(PATH), actor(), body("TEST_BLUE", "ageBandCode", "TEST_AGE"), 400, "INVALID_OPTION");
        error(post(PATH), actor(), body("TEST_RED"), 400, "INVALID_OPTION");
    }

    @Test
    void missingCataloguesDoNotBlockListingOrPatchesThatNeedNoNewSelection() throws Exception {
        JsonNode created = create(owner, body("TEST_RED", "ageBandCode", "TEST_AGE",
                "interestCategoryIds", List.of(interest)));
        when(properties.colors()).thenReturn(List.of());
        when(properties.ageBands()).thenReturn(List.of());
        jdbc.update("UPDATE app.tags SET active = false");
        assertThat(success(get(PATH), owner, null, 200).get("items").get(0)).isEqualTo(created);
        success(patch(profilePath(created)), owner, "{\"nickname\":\"변경한 이름\"}", 200);
        success(patch(profilePath(created)), owner, "{\"colorCode\":\"TEST_RED\",\"ageBandCode\":\"TEST_AGE\"}", 200);
        success(patch(profilePath(created)), owner, "{\"ageBandCode\":null,\"interestCategoryIds\":[]}", 200);
        error(post(PATH), owner, body("TEST_BLUE"), 503, "OPTIONS_NOT_CONFIGURED");
        error(patch(profilePath(created)), owner, "{\"colorCode\":\"TEST_BLUE\"}", 503, "OPTIONS_NOT_CONFIGURED");
    }

    @Test
    void patchOmissionsKeepValuesAndExplicitNullAndEmptyArraysClearThem() throws Exception {
        UUID image = image(owner);
        JsonNode created = create(owner, body("TEST_RED", "ageBandCode", "TEST_AGE", "profileImageId", image,
                "interestCategoryIds", List.of(interest), "bookGenreIds", List.of(book)));
        JsonNode renamed = success(patch(profilePath(created)), owner, "{\"nickname\":\"새 이름\"}", 200);
        for (String retained : List.of("ageBandCode", "profileImageId", "interestCategoryIds", "bookGenreIds", "colorCode")) {
            assertThat(renamed.get(retained)).isEqualTo(created.get(retained));
        }
        JsonNode cleared = success(patch(profilePath(created)), owner,
                "{\"ageBandCode\":null,\"profileImageId\":null,\"interestCategoryIds\":[],\"bookGenreIds\":[]}", 200);
        assertThat(cleared.get("ageBandCode").isNull()).isTrue();
        assertThat(cleared.get("profileImageId").isNull()).isTrue();
        assertThat(cleared.get("interestCategoryIds").isEmpty()).isTrue();
        assertThat(cleared.get("bookGenreIds").isEmpty()).isTrue();
    }

    @Test
    void anotherAccountsProfileAndAnUnknownProfileAreIndistinguishable() throws Exception {
        JsonNode created = create(owner, body("TEST_RED"));
        Actor other = actor();
        error(patch(profilePath(created)), other, "{\"nickname\":\"침범\"}", 404, "RESOURCE_NOT_FOUND");
        error(patch(PATH + "/" + UUID.randomUUID()), other, "{\"nickname\":\"없음\"}", 404, "RESOURCE_NOT_FOUND");
        assertThat(success(get(PATH), other, null, 200).get("items").isEmpty()).isTrue();
        assertThat(success(get(PATH), owner, null, 200).get("items").get(0)).isEqualTo(created);
    }

    @Test
    void onlyExistingImagesOwnedByTheAccountCanBeLinked() throws Exception {
        UUID foreignImage = image(actor());
        JsonNode created = create(owner, body("TEST_RED"));
        for (UUID unavailable : List.of(foreignImage, UUID.randomUUID())) {
            error(post(PATH), owner, body("TEST_BLUE", "profileImageId", unavailable), 404, "RESOURCE_NOT_FOUND");
            error(patch(profilePath(created)), owner,
                    "{\"profileImageId\":\"%s\"}".formatted(unavailable), 404, "RESOURCE_NOT_FOUND");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.child_profiles", Long.class)).isEqualTo(1L);
        assertThat(orphanedAt(foreignImage)).isNotNull();
    }

    @Test
    void imagesCanBeSharedAndOnlyTheLastUnlinkStartsTheOrphanClock() throws Exception {
        UUID image = image(owner);
        UUID replacement = image(owner);
        JsonNode first = create(owner, body("TEST_RED", "profileImageId", image));
        JsonNode second = create(owner, body("TEST_BLUE", "profileImageId", image));
        assertThat(orphanedAt(image)).isNull();
        success(patch(profilePath(first)), owner, "{\"profileImageId\":null}", 200);
        assertThat(orphanedAt(image)).isNull();
        Instant before = Instant.now().minusSeconds(1);
        success(patch(profilePath(second)), owner, "{\"profileImageId\":\"%s\"}".formatted(replacement), 200);
        assertThat(orphanedAt(image).toInstant()).isBetween(before, Instant.now().plusSeconds(1));
        assertThat(orphanedAt(replacement)).isNull();
        success(patch(profilePath(first)), owner, "{\"profileImageId\":\"%s\"}".formatted(image), 200);
        assertThat(orphanedAt(image)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.profile_images", Long.class)).isEqualTo(2L);
    }

    @Test
    void failedPatchRollsBackNicknameColorPhotoAndOrphanStateTogether() throws Exception {
        UUID originalImage = image(owner);
        UUID nextImage = image(owner);
        JsonNode created = create(owner, body("TEST_RED", "profileImageId", originalImage));
        create(owner, body("TEST_BLUE"));
        Timestamp nextOrphanBefore = orphanedAt(nextImage);
        error(patch(profilePath(created)), owner,
                mapper.writeValueAsString(Map.of("nickname", "실패한 이름", "colorCode", "TEST_BLUE",
                        "profileImageId", nextImage)), 409, "COLOR_ALREADY_USED");
        error(patch(profilePath(created)), owner,
                mapper.writeValueAsString(Map.of("nickname", "실패한 이름", "colorCode", "TEST_GREEN",
                        "profileImageId", nextImage, "interestCategoryIds", List.of(UUID.randomUUID()))),
                400, "INVALID_OPTION");
        assertThat(success(get(PATH), owner, null, 200).get("items").get(0)).isEqualTo(created);
        assertThat(orphanedAt(originalImage)).isNull();
        assertThat(orphanedAt(nextImage)).isEqualTo(nextOrphanBefore);
    }

    @Test
    void paginationUsesCreatedAtThenUuidWithoutDuplicatesOrAccountLeaks() throws Exception {
        for (String color : List.of("TEST_RED", "TEST_BLUE", "TEST_GREEN")) {
            create(owner, body(color));
        }
        Actor other = actor();
        create(other, body("TEST_RED"));
        jdbc.update("UPDATE app.child_profiles SET created_at = ?", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        List<String> expected = jdbc.queryForList("SELECT id::text FROM app.child_profiles WHERE account_id = ? ORDER BY created_at, id",
                String.class, owner.id());
        List<String> actual = new ArrayList<>();
        String cursor = null;
        for (int pageNumber = 0; pageNumber < expected.size(); pageNumber++) {
            var request = get(PATH).param("limit", "1");
            if (cursor != null) request.param("cursor", cursor);
            JsonNode page = success(request, owner, null, 200);
            assertThat(page.get("items").size()).isEqualTo(1);
            actual.add(page.get("items").get(0).get("id").asText());
            if (pageNumber < expected.size() - 1) {
                cursor = page.get("nextCursor").asText();
                assertThat(cursor).isNotBlank().isNotEqualTo(actual.getLast());
                error(get(PATH).param("cursor", cursor), other, null, 400, "INVALID_CURSOR");
            } else {
                assertThat(page.get("nextCursor").isNull()).isTrue();
            }
        }
        assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    @Test
    void listDefaultsToTwentyAndAcceptsTheMaximumLimitWithoutRequiringCatalogues() throws Exception {
        for (int index = 0; index < 21; index++) {
            jdbc.update("INSERT INTO app.child_profiles (id, account_id, nickname, color_code, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), owner.id(), "시험 아이 " + index,
                    "TEST_SEEDED_" + index, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                    Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        }
        when(properties.colors()).thenReturn(List.of());
        when(properties.ageBands()).thenReturn(List.of());
        JsonNode first = success(get(PATH), owner, null, 200);
        assertThat(first.get("items").size()).isEqualTo(20);
        assertThat(first.get("nextCursor").asText()).isNotBlank();
        JsonNode last = success(get(PATH).param("cursor", first.get("nextCursor").asText()), owner, null, 200);
        assertThat(last.get("items").size()).isEqualTo(1);
        assertThat(last.get("nextCursor").isNull()).isTrue();
        JsonNode maximum = success(get(PATH).param("limit", "100"), owner, null, 200);
        assertThat(maximum.get("items").size()).isEqualTo(21);
        assertThat(maximum.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void listRejectsInvalidLimitsUnexpectedOrRepeatedQueriesAndAnyBody() throws Exception {
        for (String invalid : List.of("0", "-1", "101", "1.0", "bad", "", "2147483648")) {
            error(get(PATH).param("limit", invalid), owner, null, 400, "INVALID_REQUEST");
        }
        error(get(PATH).param("extra", "x"), owner, null, 400, "INVALID_REQUEST");
        error(get(PATH).param("limit", "1", "2"), owner, null, 400, "INVALID_REQUEST");
        error(get(PATH).param("cursor", "a", "b"), owner, null, 400, "INVALID_REQUEST");
        error(get(PATH).param("cursor", "invalid"), owner, null, 400, "INVALID_CURSOR");
        for (String content : List.of("{}", " ", "&")) {
            error(get(PATH), owner, content, 400, "INVALID_REQUEST");
        }
    }

    @Test
    void simultaneousCreatesForOneAccountAndColorReturnOneCreatedAndOneConflict() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String payload = body("TEST_RED");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var call = (java.util.concurrent.Callable<MockHttpServletResponse>) () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return perform(post(PATH), owner, payload);
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 409);
            for (var response : responses) {
                assertHeaders(response);
                if (response.getStatus() == 409) error(response, 409, "COLOR_ALREADY_USED");
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.child_profiles WHERE account_id = ?", Long.class, owner.id()))
                .isEqualTo(1L);
    }

    @Test
    void generatedOpenApiDocumentsTypedRequestsValidationAndBearerProtectedPagination() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(200);
        JsonNode document = mapper.readTree(response.getContentAsString());
        JsonNode collection = document.at("/paths/~1api~1child-profiles");
        JsonNode create = collection.path("post");
        JsonNode patch = document.at("/paths/~1api~1child-profiles~1{profileId}/patch");
        JsonNode list = collection.path("get");
        assertThat(create.path("responses").has("201")).isTrue();
        String createRef = create.at("/requestBody/content/application~1json/schema/$ref").asText();
        String patchRef = patch.at("/requestBody/content/application~1json/schema/$ref").asText();
        assertThat(createRef).startsWith("#/components/schemas/");
        assertThat(patchRef).startsWith("#/components/schemas/").isNotEqualTo(createRef);
        JsonNode createSchema = document.at(createRef.substring(1));
        JsonNode patchSchema = document.at(patchRef.substring(1));
        String responseRef = create.at("/responses/201/content/application~1json/schema/$ref").asText();
        String pageRef = list.at("/responses/200/content/application~1json/schema/$ref").asText();
        assertThat(responseRef).startsWith("#/components/schemas/");
        assertThat(pageRef).startsWith("#/components/schemas/");
        JsonNode responseSchema = document.at(responseRef.substring(1));
        JsonNode pageSchema = document.at(pageRef.substring(1));
        List<String> responseRequired = new ArrayList<>();
        responseSchema.path("required").forEach(name -> responseRequired.add(name.asText()));
        assertThat(responseRequired).containsExactlyInAnyOrder("id", "nickname", "colorCode", "ageBandCode",
                "profileImageId", "interestCategoryIds", "bookGenreIds", "createdAt", "updatedAt");
        List<String> pageRequired = new ArrayList<>();
        pageSchema.path("required").forEach(name -> pageRequired.add(name.asText()));
        assertThat(pageRequired).containsExactlyInAnyOrder("items", "nextCursor");
        assertAllowsNull(document, pageSchema.at("/properties/nextCursor"));
        List<String> required = new ArrayList<>();
        for (JsonNode name : createSchema.path("required")) required.add(name.asText());
        assertThat(required).containsExactlyInAnyOrder("nickname", "colorCode", "interestCategoryIds", "bookGenreIds");
        assertThat(createSchema.path("additionalProperties").isBoolean()).isTrue();
        assertThat(createSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(patchSchema.path("additionalProperties").isBoolean()).isTrue();
        assertThat(patchSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(patchSchema.path("minProperties").asInt()).isEqualTo(1);
        assertThat(patchSchema.path("required").size()).isZero();
        for (JsonNode schema : List.of(createSchema, patchSchema, responseSchema)) {
            for (String name : List.of("interestCategoryIds", "bookGenreIds")) {
                assertThat(schema.path("properties").path(name).path("uniqueItems").asBoolean())
                        .as("%s uniqueItems", name).isTrue();
            }
            assertThat(schema.at("/properties/ageBandCode/minLength").asInt()).isEqualTo(1);
            assertThat(schema.at("/properties/ageBandCode/maxLength").asInt()).isEqualTo(64);
            for (String name : List.of("ageBandCode", "profileImageId")) {
                assertAllowsNull(document, schema.path("properties").path(name));
            }
        }
        for (JsonNode operation : List.of(create, patch, list)) {
            boolean bearer = false;
            for (JsonNode security : operation.path("security")) bearer |= security.has("BearerAuth");
            assertThat(bearer).as("operation BearerAuth requirement").isTrue();
        }
        List<String> queries = new ArrayList<>();
        for (JsonNode parameter : list.path("parameters")) {
            if (!"query".equals(parameter.path("in").asText())) continue;
            queries.add(parameter.path("name").asText());
            assertThat(parameter.path("required").asBoolean()).isFalse();
            if ("limit".equals(parameter.path("name").asText())) {
                assertThat(parameter.at("/schema/type").asText()).isEqualTo("integer");
                assertThat(parameter.at("/schema/minimum").asInt()).isEqualTo(1);
                assertThat(parameter.at("/schema/maximum").asInt()).isEqualTo(100);
                assertThat(parameter.at("/schema/default").asInt()).isEqualTo(20);
            }
        }
        assertThat(queries).containsExactlyInAnyOrder("limit", "cursor");
    }

    private void assertAllowsNull(JsonNode document, JsonNode property) {
        List<JsonNode> alternatives = new ArrayList<>(List.of(property));
        property.path("anyOf").forEach(alternatives::add);
        property.path("oneOf").forEach(alternatives::add);
        boolean allowsNull = false;
        for (JsonNode alternative : alternatives) {
            if (alternative.has("$ref")) alternative = document.at(alternative.get("$ref").asText().substring(1));
            allowsNull |= alternative.path("nullable").asBoolean()
                    || (alternative.path("type").isString()
                    && "null".equals(alternative.path("type").stringValue()));
            if (alternative.path("type").isArray()) {
                for (JsonNode type : alternative.get("type")) allowsNull |= "null".equals(type.asText());
            }
        }
        assertThat(allowsNull).as("property must document explicit null: %s", property).isTrue();
    }

    private Actor actor() {
        var login = sessions.issueForKakaoSubject("TEST_" + UUID.randomUUID());
        var tokens = sessions.rotateRefresh(login.refreshToken());
        return new Actor(tokens.accountId(), tokens.accessToken());
    }

    private UUID tag(String kind, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.tags (id, kind, code, label, active) VALUES (?, ?, ?, ?, ?)",
                id, kind, "TEST_" + id, "시험 태그", active);
        return id;
    }

    private UUID image(Actor actor) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.profile_images (id, account_id, storage_key, mime_type, size_bytes, orphaned_at, created_at) "
                        + "VALUES (?, ?, ?, 'image/png', 128, ?, ?)", id, actor.id(), "TEST_metadata_only_" + id,
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        return id;
    }

    private Timestamp orphanedAt(UUID image) {
        return jdbc.queryForObject("SELECT orphaned_at FROM app.profile_images WHERE id = ?", Timestamp.class, image);
    }

    private String body(String color, Object... fields) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("nickname", "시험 아이");
        values.put("colorCode", color);
        values.put("interestCategoryIds", List.of());
        values.put("bookGenreIds", List.of());
        for (int index = 0; index < fields.length; index += 2) {
            values.put((String) fields[index], fields[index + 1]);
        }
        return mapper.writeValueAsString(values);
    }

    private String profilePath(JsonNode profile) {
        return PATH + "/" + profile.get("id").asText();
    }

    private JsonNode create(Actor actor, String payload) throws Exception {
        return success(post(PATH), actor, payload, 201);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, Actor actor, String payload) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.token());
        if (payload != null) request.contentType("application/json").content(payload);
        return mvc.perform(request).andReturn().getResponse();
    }

    private JsonNode success(MockHttpServletRequestBuilder request, Actor actor, String payload, int expected) throws Exception {
        var response = perform(request, actor, payload);
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(expected);
        assertHeaders(response);
        return mapper.readTree(response.getContentAsString());
    }

    private void error(MockHttpServletRequestBuilder request, Actor actor, String payload, int expected, String code) throws Exception {
        error(perform(request, actor, payload), expected, code);
    }

    private void error(MockHttpServletResponse response, int expected, String code) throws Exception {
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(expected);
        assertHeaders(response);
        assertThat(mapper.readTree(response.getContentAsString()).get("error").get("code").asText()).isEqualTo(code);
    }

    private void assertHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
    }

    private void assertProfileShape(JsonNode profile) {
        assertThat(profile.size()).isEqualTo(9);
        for (String field : List.of("id", "nickname", "colorCode", "ageBandCode", "profileImageId",
                "interestCategoryIds", "bookGenreIds", "createdAt", "updatedAt")) {
            assertThat(profile.has(field)).as(field).isTrue();
        }
        UUID.fromString(profile.get("id").asText());
        Instant.parse(profile.get("createdAt").asText());
        Instant.parse(profile.get("updatedAt").asText());
        for (String array : List.of("interestCategoryIds", "bookGenreIds")) {
            assertThat(profile.get(array).isArray()).isTrue();
            for (JsonNode id : profile.get(array)) UUID.fromString(id.asText());
        }
    }

    private record Actor(UUID id, String token) { }
}
