package com.podo.server.visit;

import com.podo.server.auth.service.AuthSessionService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest(properties = {
        "podo.auth.api-origin=https://podo.test",
        "podo.auth.success-redirect-uri=https://podo.test/auth/success",
        "podo.auth.failure-redirect-uri=https://podo.test/auth/error",
        "podo.auth.csrf-signing-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
@Testcontainers
class CompletedPlaceIntegrationTests {
    private static final String PATH = "/api/me/completed-places";

    private static final Instant BASE =
            Instant.now()
                    .minus(7, ChronoUnit.DAYS)
                    .truncatedTo(ChronoUnit.MICROS);

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11");

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

    private Actor owner;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE app.accounts, app.places CASCADE");
        owner = actor();
    }

    @Test
    void authenticationIsRequired() throws Exception {
        error(
                mvc.perform(get(PATH)).andReturn().getResponse(),
                401,
                "AUTHENTICATION_REQUIRED"
        );

        error(
                mvc.perform(
                        get(PATH).header("Authorization", "Bearer invalid")
                ).andReturn().getResponse(),
                401,
                "INVALID_TOKEN"
        );
    }

    @Test
    void emptyAccountReturnsAnEmptySummaryAndPrivateHeaders()
            throws Exception {
        JsonNode page = ok(get(PATH), owner);

        assertThat(page.get("items").isEmpty()).isTrue();
        assertThat(page.get("nextCursor").isNull()).isTrue();

        assertThat(
                page.at("/summary/completedPlaceCount").asLong()
        ).isZero();

        assertThat(
                page.at("/summary/districts").isEmpty()
        ).isTrue();

        assertThat(
                Instant.parse(page.at("/summary/asOf").asText())
        ).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void revisitsAreGroupedAndAllChildrenUseTheirCurrentColors()
            throws Exception {
        UUID place = place(UUID.randomUUID(), "CHEOIN");
        UUID first = child(owner, "RED");
        UUID second = child(owner, "BLUE");

        completed(owner, place, first, BASE);
        completed(owner, place, first, BASE.plusSeconds(1));
        completed(owner, place, second, BASE.plusSeconds(2));
        completed(owner, place, null, BASE.plusSeconds(3));

        jdbc.update(
                "UPDATE app.child_profiles SET color_code = 'GREEN' WHERE id = ?",
                first
        );

        JsonNode page = ok(get(PATH), owner);
        JsonNode item = page.at("/items/0");

        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(item.get("completedVisitCount").asLong()).isEqualTo(4);

        assertThat(
                Instant.parse(item.get("firstCompletedAt").asText())
        ).isEqualTo(BASE);

        assertThat(
                Instant.parse(item.get("lastCompletedAt").asText())
        ).isEqualTo(BASE.plusSeconds(3));

        assertThat(item.get("hasUnassignedVisit").asBoolean()).isTrue();
        assertThat(item.get("placeAvailable").asBoolean()).isTrue();
        assertThat(item.get("latitude").asDouble()).isEqualTo(37.2);
        assertThat(item.get("longitude").asDouble()).isEqualTo(127.2);

        List<String> colors = new ArrayList<>();

        item.get("children").forEach(value ->
                colors.add(value.get("colorCode").asText())
        );

        assertThat(colors).containsExactlyInAnyOrder("GREEN", "BLUE");

        assertThat(
                page.at("/summary/completedPlaceCount").asLong()
        ).isEqualTo(1);
    }

    @Test
    void otherAccountsAndUncompletedVisitsAreExcluded()
            throws Exception {
        UUID mine = place(UUID.randomUUID(), "SUJI");
        UUID other = place(UUID.randomUUID(), "GIHEUNG");

        completed(owner, mine, null, BASE);
        completed(actor(), other, null, BASE.plusSeconds(1));

        UUID unfinished = completed(
                owner,
                other,
                null,
                BASE.plusSeconds(2)
        );

        jdbc.update("""
                UPDATE app.visits
                SET status = 'QUIZ_IN_PROGRESS', completed_at = NULL
                WHERE id = ?
                """, unfinished);

        JsonNode page = ok(get(PATH), owner);

        assertThat(page.get("items").size()).isEqualTo(1);

        assertThat(
                page.at("/items/0/placeId").asText()
        ).isEqualTo(mine.toString());

        assertThat(
                page.at("/summary/completedPlaceCount").asLong()
        ).isEqualTo(1);

        assertThat(
                page.at("/summary/districts/0/districtCode").asText()
        ).isEqualTo("SUJI");
    }

    @Test
    void hiddenPlacesAndInvalidatedQuizzesKeepCompletedHistory()
            throws Exception {
        UUID place = place(UUID.randomUUID(), null);
        completed(owner, place, null, BASE);

        jdbc.update("""
                UPDATE app.places
                SET status = 'HIDDEN', latitude = NULL, longitude = NULL
                WHERE id = ?
                """, place);

        jdbc.update("""
                UPDATE app.quiz_sets
                SET status = 'INVALIDATED'
                WHERE place_id = ?
                """, place);

        JsonNode page = ok(get(PATH), owner);
        JsonNode item = page.at("/items/0");

        assertThat(item.get("placeAvailable").asBoolean()).isFalse();
        assertThat(item.get("latitude").isNull()).isTrue();
        assertThat(item.get("longitude").isNull()).isTrue();
        assertThat(item.get("districtCode").isNull()).isTrue();

        assertThat(
                page.at("/summary/completedPlaceCount").asLong()
        ).isEqualTo(1);

        assertThat(
                page.at("/summary/districts/0/districtCode").isNull()
        ).isTrue();
    }

    @Test
    void aForeignProfileIsNeverExposedEvenInAnInconsistentLegacyRow()
            throws Exception {
        UUID place = place(UUID.randomUUID(), "CHEOIN");

        completed(
                owner,
                place,
                child(actor(), "SECRET"),
                BASE
        );

        JsonNode item = ok(get(PATH), owner).at("/items/0");

        assertThat(item.get("children").isEmpty()).isTrue();
        assertThat(item.get("hasUnassignedVisit").asBoolean()).isTrue();
        assertThat(item.toString()).doesNotContain("SECRET");
    }

    @Test
    void paginationUsesCompletionTimeAndPostgresUuidOrderWithWholeAccountSummary()
            throws Exception {
        List<UUID> expected = new ArrayList<>();

        for (String id : List.of(
                "ffffffff-ffff-4fff-8fff-ffffffffffff",
                "80000000-0000-4000-8000-000000000000",
                "70000000-0000-4000-8000-000000000000"
        )) {
            UUID place = place(UUID.fromString(id), "CHEOIN");

            completed(owner, place, null, BASE.plusSeconds(1));
            expected.add(place);
        }

        UUID older = place(UUID.randomUUID(), "GIHEUNG");
        completed(owner, older, null, BASE);
        expected.add(older);

        List<String> actual = new ArrayList<>();
        String cursor = null;
        String asOf = null;

        for (int index = 0; index < 4; index++) {
            var request = get(PATH).param("limit", "1");

            if (cursor != null) {
                request.param("cursor", cursor);
            }

            JsonNode page = ok(request, owner);

            actual.add(page.at("/items/0/placeId").asText());

            assertThat(
                    page.at("/summary/completedPlaceCount").asLong()
            ).isEqualTo(4);

            assertThat(
                    page.at("/summary/districts/0/completedPlaceCount").asLong()
            ).isEqualTo(3);

            if (asOf == null) {
                asOf = page.at("/summary/asOf").asText();
            }

            assertThat(
                    page.at("/summary/asOf").asText()
            ).isEqualTo(asOf);

            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").asText();

            assertThat(cursor == null).isEqualTo(index == 3);
        }

        assertThat(actual).containsExactlyElementsOf(
                expected.stream().map(UUID::toString).toList()
        );
    }

    @Test
    void laterCompletionsDoNotMovePlacesOrChangeCountsWithinTheCursorWindow()
            throws Exception {
        UUID first = place(UUID.randomUUID(), "CHEOIN");
        UUID second = place(UUID.randomUUID(), "SUJI");

        completed(owner, first, null, BASE.plusSeconds(1));
        completed(owner, second, null, BASE);

        JsonNode initial = ok(get(PATH).param("limit", "1"), owner);

        Instant asOf = Instant.parse(
                initial.at("/summary/asOf").asText()
        );

        completed(
                owner,
                second,
                child(owner, "NEW"),
                asOf.plusSeconds(1)
        );

        completed(
                owner,
                place(UUID.randomUUID(), "GIHEUNG"),
                null,
                asOf.plusSeconds(2)
        );

        JsonNode next = ok(
                get(PATH).param(
                        "cursor",
                        initial.get("nextCursor").asText()
                ),
                owner
        );

        assertThat(next.get("items").size()).isEqualTo(1);

        assertThat(
                next.at("/items/0/placeId").asText()
        ).isEqualTo(second.toString());

        assertThat(
                next.at("/items/0/completedVisitCount").asLong()
        ).isEqualTo(1);

        assertThat(
                next.at("/items/0/children").isEmpty()
        ).isTrue();

        assertThat(
                next.at("/summary/completedPlaceCount").asLong()
        ).isEqualTo(2);
    }

    @Test
    void defaultLimitIsTwentyAndMaximumLimitIsAccepted()
            throws Exception {
        for (int i = 0; i < 21; i++) {
            completed(
                    owner,
                    place(UUID.randomUUID(), "CHEOIN"),
                    null,
                    BASE.plusSeconds(i)
            );
        }

        JsonNode page = ok(get(PATH), owner);

        assertThat(page.get("items").size()).isEqualTo(20);
        assertThat(page.get("nextCursor").isString()).isTrue();

        assertThat(
                page.at("/summary/completedPlaceCount").asLong()
        ).isEqualTo(21);

        JsonNode all = ok(get(PATH).param("limit", "100"), owner);

        assertThat(all.get("items").size()).isEqualTo(21);
        assertThat(all.get("nextCursor").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "0", "101", "-1", "+1",
            "1.0", " 1", "1 ", "999999999999", "abc"
    })
    void invalidLimitsAreRejected(String value) throws Exception {
        error(
                send(get(PATH).param("limit", value), owner),
                400,
                "INVALID_REQUEST"
        );
    }

    @Test
    void unknownRepeatedParametersAndGetBodiesAreRejected()
            throws Exception {
        for (var request : List.of(
                get(PATH).param(
                        "childProfileId",
                        UUID.randomUUID().toString()
                ),
                get(PATH).param("limit", "1", "2"),
                get(PATH).param("cursor", "a", "b"),
                get(PATH).contentType("application/json").content("{}")
        )) {
            error(
                    send(request, owner),
                    400,
                    "INVALID_REQUEST"
            );
        }
    }

    @Test
    void cursorsAreBoundToTheAccountAndEndpoint() throws Exception {
        completed(
                owner,
                place(UUID.randomUUID(), "CHEOIN"),
                null,
                BASE
        );

        completed(
                owner,
                place(UUID.randomUUID(), "CHEOIN"),
                null,
                BASE.plusSeconds(1)
        );

        String cursor = ok(get(PATH).param("limit", "1"), owner)
                .get("nextCursor")
                .asText();

        error(
                send(get(PATH).param("cursor", cursor), actor()),
                400,
                "INVALID_CURSOR"
        );

        String payload = new String(
                Base64.getUrlDecoder().decode(cursor),
                StandardCharsets.UTF_8
        );

        for (String changed : List.of(
                payload.replace("completed-places", "visits"),
                payload.replace("\n1\n", "\n2\n"),
                payload + "\nextra"
        )) {
            error(
                    send(
                            get(PATH).param("cursor", encoded(changed)),
                            owner
                    ),
                    400,
                    "INVALID_CURSOR"
            );
        }

        for (String malformed : List.of(
                "",
                "!",
                "x".repeat(513),
                cursor + "="
        )) {
            error(
                    send(get(PATH).param("cursor", malformed), owner),
                    400,
                    "INVALID_CURSOR"
            );
        }
    }

    @Test
    void outOfRangeFutureAndSubMicrosecondCursorTimesAreRejected()
            throws Exception {
        String id = UUID.randomUUID().toString();

        for (String times : List.of(
                "9999-12-31T23:59:59Z\n" + BASE,
                "0000-01-01T00:00:00Z\n" + BASE,
                BASE + "\n" + BASE.plusSeconds(1),
                BASE.plusNanos(1) + "\n" + BASE,
                "not-a-time\n" + BASE
        )) {
            String cursor = encoded(
                    "completed-places\n1\n"
                            + owner.id() + "\n"
                            + times + "\n"
                            + id
            );

            error(
                    send(get(PATH).param("cursor", cursor), owner),
                    400,
                    "INVALID_CURSOR"
            );
        }
    }

    @Test
    void threeSubmittedAnswersAppearOnTheMapEvenWhenEveryAnswerIsWrong()
            throws Exception {
        UUID place = place(UUID.randomUUID(), "CHEOIN");
        UUID visit = completed(owner, place, null, BASE);

        jdbc.update("""
                UPDATE app.visits
                SET status = 'QUIZ_IN_PROGRESS', completed_at = NULL
                WHERE id = ?
                """, visit);

        UUID set = jdbc.queryForObject(
                "SELECT quiz_set_id FROM app.visits WHERE id = ?",
                UUID.class,
                visit
        );

        UUID source = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO app.place_sources
                    (id, place_id, source_type, fact_text, status)
                VALUES (?, ?, 'OFFICIAL', '검증한 사실', 'ACTIVE')
                """, source, place);

        List<UUID> questions = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            UUID question = UUID.randomUUID();
            questions.add(question);

            jdbc.update("""
                    INSERT INTO app.quiz_questions
                        (id, quiz_set_id, position, question_text,
                         correct_answer, explanation, source_id,
                         hint_1_text, hint_2_text)
                    VALUES (?, ?, ?, '문제', 'O', '설명', ?, '힌트1', '힌트2')
                    """, question, set, i, source);
        }

        for (int i = 0; i < questions.size(); i++) {
            ok(
                    put(
                            "/api/visits/" + visit
                                    + "/answers/" + questions.get(i)
                    )
                            .contentType("application/json")
                            .content("{\"answer\":\"X\"}"),
                    owner
            );

            if (i < 2) {
                assertThat(
                        ok(get(PATH), owner).get("items").isEmpty()
                ).isTrue();
            }
        }

        JsonNode page = ok(get(PATH), owner);

        assertThat(
                page.at("/items/0/completedVisitCount").asLong()
        ).isEqualTo(1);

        assertThat(
                page.at("/summary/completedPlaceCount").asLong()
        ).isEqualTo(1);
    }

    @Test
    void swaggerDocumentsTheActualRouteAndNullableHistoryCoordinates()
            throws Exception {
        JsonNode docs = mapper.readTree(
                mvc.perform(get("/v3/api-docs/podo"))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray()
        );

        JsonNode operation = docs.get("paths").get(PATH).get("get");

        assertThat(
                operation.get("operationId").asText()
        ).isEqualTo("getCompletedPlaces");

        assertThat(operation.get("parameters").size()).isEqualTo(2);

        assertThat(operation.toString())
                .contains("BearerAuth", "CompletedPlacePage");

        assertThat(
                docs.at(
                        "/components/schemas/CompletedPlace/properties/latitude"
                ).toString()
        ).contains("number", "null");
    }

    private record Actor(UUID id, String token) {}

    private Actor actor() {
        var login = sessions.issueForKakaoSubject(
                "COMPLETED_TEST_" + UUID.randomUUID()
        );

        var tokens = sessions.rotateRefresh(login.refreshToken());

        return new Actor(
                tokens.accountId(),
                tokens.accessToken()
        );
    }

    private UUID place(UUID id, String district) {
        jdbc.update("""
                INSERT INTO app.places
                    (id, name, address, latitude, longitude, district_code,
                     main_activities, environment, status, updated_at)
                VALUES (
                    ?, '시험 장소', '용인', 37.2, 127.2, ?,
                    ARRAY['관찰'], 'INDOOR', 'PUBLISHED', now()
                )
                """, id, district);

        return id;
    }

    private UUID child(Actor actor, String color) {
        UUID id = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO app.child_profiles
                    (id, account_id, nickname, color_code, created_at, updated_at)
                VALUES (?, ?, '아이', ?, now(), now())
                """, id, actor.id(), color);

        return id;
    }

    private UUID completed(
            Actor actor,
            UUID place,
            UUID child,
            Instant at
    ) {
        UUID set = jdbc.query(
                        "SELECT id FROM app.quiz_sets WHERE place_id = ?",
                        (rs, row) -> rs.getObject(1, UUID.class),
                        place
                )
                .stream()
                .findFirst()
                .orElseGet(() -> {
                    UUID id = UUID.randomUUID();

                    jdbc.update("""
                            INSERT INTO app.quiz_sets
                                (id, place_id, version, status,
                                 reviewed_by, reviewed_at, published_at)
                            VALUES (
                                ?, ?, 1, 'PUBLISHED',
                                'test', now(), now()
                            )
                            """, id, place);

                    return id;
                });

        UUID id = UUID.randomUUID();
        Timestamp time = Timestamp.from(at);

        jdbc.update("""
                INSERT INTO app.visits
                    (id, account_id, place_id, child_profile_id,
                     planned_date, source, status, quiz_set_id,
                     create_request_key, create_request_hash,
                     reported_at, completed_at, created_at, updated_at)
                VALUES (
                    ?, ?, ?, ?, '2026-11-06', 'MAP', 'COMPLETED',
                    ?, ?, 'test', ?, ?, ?, ?
                )
                """,
                id,
                actor.id(),
                place,
                child,
                set,
                id.toString(),
                time,
                time,
                time,
                time
        );

        return id;
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private MockHttpServletResponse send(
            MockHttpServletRequestBuilder request,
            Actor actor
    ) throws Exception {
        return mvc.perform(
                        request.header(
                                "Authorization",
                                "Bearer " + actor.token()
                        )
                )
                .andReturn()
                .getResponse();
    }

    private JsonNode ok(
            MockHttpServletRequestBuilder request,
            Actor actor
    ) throws Exception {
        var response = send(request, actor);

        assertThat(response.getStatus())
                .withFailMessage(response.getContentAsString())
                .isEqualTo(200);

        assertThat(response.getHeader("Cache-Control"))
                .contains("private", "no-store");

        assertThat(response.getHeader("X-Request-Id")).isNotBlank();

        return mapper.readTree(response.getContentAsByteArray());
    }

    private void error(
            MockHttpServletResponse response,
            int status,
            String code
    ) throws Exception {
        assertThat(response.getStatus())
                .withFailMessage(response.getContentAsString())
                .isEqualTo(status);

        assertThat(
                mapper.readTree(response.getContentAsByteArray())
                        .at("/error/code")
                        .asText()
        ).isEqualTo(code);
    }
}