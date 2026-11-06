package com.podo.server.visit;

import com.podo.server.auth.service.AuthSessionService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {
        "podo.auth.api-origin=https://podo.test",
        "podo.auth.success-redirect-uri=https://podo.test/auth/success",
        "podo.auth.failure-redirect-uri=https://podo.test/auth/error",
        "podo.auth.csrf-signing-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
@Testcontainers
class VisitIntegrationTests {

    private static final String PATH = "/api/visits";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    AuthSessionService sessions;

    private Actor owner;
    private UUID place;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE app.accounts, app.places CASCADE");
        owner = actor();
        place = place("PUBLISHED");
    }

    @Test
    void allFiveApisRequireAuthentication() throws Exception {
        String id = UUID.randomUUID().toString();

        for (var request : List.of(
                post(PATH)
                        .contentType("application/json")
                        .content(body()),
                get(PATH),
                get(PATH + "/" + id),
                patch(PATH + "/" + id)
                        .contentType("application/json")
                        .content("{}"),
                post(PATH + "/" + id + "/cancel")
        )) {
            error(
                    mvc.perform(request).andReturn().getResponse(),
                    401,
                    "AUTHENTICATION_REQUIRED"
            );
        }
    }

    @Test
    void createsWithoutAProfileOrPreparedQuizAndDocumentsTheContract()
            throws Exception {
        JsonNode visit = ok(
                createRequest("request-0001", body()),
                owner,
                201
        );

        assertThat(visit.get("status").asText()).isEqualTo("PLANNED");
        assertThat(visit.get("childProfileId").isNull()).isTrue();
        assertThat(visit.get("quizSetId").isNull()).isTrue();
        assertThat(visit.get("answeredCount").asInt()).isZero();
        assertThat(visit.get("correctCount").asInt()).isZero();
        assertThat(visit.has("quizAvailability")).isFalse();
        assertThat(visit.has("createRequestHash")).isFalse();

        JsonNode docs = mapper.readTree(
                mvc.perform(get("/v3/api-docs/podo"))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray()
        );

        for (String path : List.of(
                PATH,
                PATH + "/{visitId}",
                PATH + "/{visitId}/cancel"
        )) {
            assertThat(docs.get("paths").has(path)).isTrue();
        }

        assertThat(
                docs.at(
                        "/components/schemas/Visit/properties/quizAvailability"
                ).isMissingNode()
        ).isTrue();

        assertThat(
                docs.at(
                        "/components/schemas/CreateVisit/properties/plannedDate"
                ).isMissingNode()
        ).isFalse();
    }

    @Test
    void idempotencyUsesTheOriginalRequestAndReturnsCurrentState()
            throws Exception {
        String request = body();

        JsonNode created = ok(
                createRequest("request-0001", request),
                owner,
                201
        );

        String path = path(created);
        String withNull = request.substring(0, request.length() - 1)
                + ",\"childProfileId\":null}";

        assertThat(
                ok(
                        createRequest("request-0001", withNull),
                        owner,
                        200
                ).get("id")
        ).isEqualTo(created.get("id"));

        ok(
                patch(path)
                        .contentType("application/json")
                        .content("{\"plannedDate\":\"2026-12-10\"}"),
                owner,
                200
        );

        assertThat(
                ok(
                        createRequest("request-0001", request),
                        owner,
                        200
                ).get("plannedDate").asText()
        ).isEqualTo("2026-12-10");

        error(
                send(
                        createRequest(
                                "request-0001",
                                request.replace("2026-11-07", "2026-11-08")
                        ),
                        owner
                ),
                409,
                "IDEMPOTENCY_KEY_REUSED"
        );

        error(
                send(createRequest("request-0002", request), owner),
                409,
                "ACTIVE_VISIT_EXISTS"
        );

        ok(post(path + "/cancel"), owner, 200);
        ok(createRequest("request-0002", request), owner, 201);

        assertThat(
                ok(
                        createRequest("request-0001", request),
                        owner,
                        200
                ).get("status").asText()
        ).isEqualTo("CANCELLED");

        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM app.visits",
                        Integer.class
                )
        ).isEqualTo(2);
    }

    @Test
    void concurrentSameKeyCreatesExactlyOnce() throws Exception {
        String request = body();

        var results = race(
                () -> send(createRequest("request-0001", request), owner),
                () -> send(createRequest("request-0001", request), owner)
        );

        assertThat(
                results.stream().map(MockHttpServletResponse::getStatus)
        ).containsExactlyInAnyOrder(201, 200);

        assertThat(
                mapper.readTree(
                        results.getFirst().getContentAsByteArray()
                ).get("id")
        ).isEqualTo(
                mapper.readTree(
                        results.getLast().getContentAsByteArray()
                ).get("id")
        );

        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM app.visits",
                        Integer.class
                )
        ).isEqualTo(1);
    }

    @Test
    void concurrentDifferentKeysStillAllowOnlyOneActiveVisit()
            throws Exception {
        String request = body();

        var results = race(
                () -> send(createRequest("request-0001", request), owner),
                () -> send(createRequest("request-0002", request), owner)
        );

        assertThat(
                results.stream().map(MockHttpServletResponse::getStatus)
        ).containsExactlyInAnyOrder(201, 409);

        error(
                results.stream()
                        .filter(response -> response.getStatus() == 409)
                        .findFirst()
                        .orElseThrow(),
                409,
                "ACTIVE_VISIT_EXISTS"
        );

        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM app.visits",
                        Integer.class
                )
        ).isEqualTo(1);
    }

    @Test
    void rejectsUnavailablePlacesAndForeignProfilesWithoutInserting()
            throws Exception {
        UUID hidden = place("HIDDEN");

        error(
                send(
                        createRequest(
                                "request-0001",
                                body().replace(
                                        place.toString(),
                                        hidden.toString()
                                )
                        ),
                        owner
                ),
                409,
                "PLACE_UNAVAILABLE"
        );

        error(
                send(
                        createRequest(
                                "request-0001",
                                body().replace(
                                        place.toString(),
                                        UUID.randomUUID().toString()
                                )
                        ),
                        owner
                ),
                404,
                "RESOURCE_NOT_FOUND"
        );

        UUID foreign = profile(actor());
        String request = body();

        request = request.substring(0, request.length() - 1)
                + ",\"childProfileId\":\"" + foreign + "\"}";

        error(
                send(createRequest("request-0001", request), owner),
                404,
                "RESOURCE_NOT_FOUND"
        );

        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM app.visits",
                        Integer.class
                )
        ).isZero();

        UUID own = profile(owner);
        String ownRequest = body();

        ownRequest = ownRequest.substring(0, ownRequest.length() - 1)
                + ",\"childProfileId\":\"" + own + "\"}";

        assertThat(
                ok(
                        createRequest("request-0001", ownRequest),
                        owner,
                        201
                ).get("childProfileId").asText()
        ).isEqualTo(own.toString());
    }

    @Test
    void inputValidationRejectsCoercionsUnknownFieldsAndBadKeys()
            throws Exception {
        for (String request : List.of(
                "{}",
                "[]",
                "null",
                body().replace("\"MAP\"", "1"),
                body().replace("\"MAP\"", "\"UNKNOWN\""),
                body().replace("\"2026-11-07\"", "123"),
                body().replace(place.toString(), "1-1-1-1-1"),
                body().substring(0, body().length() - 1)
                        + ",\"accountId\":\"" + owner.id() + "\"}"
        )) {
            error(
                    send(createRequest("request-0001", request), owner),
                    400,
                    "INVALID_REQUEST"
            );
        }

        for (String date : List.of(
                "2026-02-30",
                "2026-1-01",
                "0000-01-01",
                "+10000-01-01"
        )) {
            error(
                    send(
                            createRequest(
                                    "request-0001",
                                    body().replace("2026-11-07", date)
                            ),
                            owner
                    ),
                    400,
                    "INVALID_DATE"
            );
        }

        for (String key : List.of("short", "x".repeat(129))) {
            error(
                    send(createRequest(key, body()), owner),
                    400,
                    "INVALID_REQUEST"
            );
        }

        error(
                send(
                        post(PATH)
                                .contentType("application/json")
                                .content(body()),
                        owner
                ),
                400,
                "INVALID_REQUEST"
        );

        error(
                send(
                        createRequest("request-0001", body())
                                .header("Idempotency-Key", "request-0002"),
                        owner
                ),
                400,
                "INVALID_REQUEST"
        );

        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM app.visits",
                        Integer.class
                )
        ).isZero();
    }

    @Test
    void readsAndMutationsDoNotExposeOtherAccounts() throws Exception {
        JsonNode visit = ok(
                createRequest("request-0001", body()),
                owner,
                201
        );

        Actor other = actor();

        for (var request : List.of(
                get(path(visit)),
                patch(path(visit))
                        .contentType("application/json")
                        .content("{\"plannedDate\":\"2026-11-08\"}"),
                post(path(visit) + "/cancel")
        )) {
            error(send(request, other), 404, "RESOURCE_NOT_FOUND");
        }

        assertThat(
                ok(get(PATH), other, 200).get("items").isEmpty()
        ).isTrue();

        assertThat(
                ok(get(path(visit)), owner, 200)
        ).isEqualTo(visit);
    }

    @Test
    void patchesOnlyTheDateAndCancellationIsIdempotent()
            throws Exception {
        JsonNode visit = ok(
                createRequest("request-0001", body()),
                owner,
                201
        );

        for (String body : List.of(
                "{}",
                "{\"plannedDate\":null}",
                "{\"plannedDate\":1}",
                "{\"plannedDate\":\"2026-11-08\",\"source\":\"POPULAR\"}"
        )) {
            error(
                    send(
                            patch(path(visit))
                                    .contentType("application/json")
                                    .content(body),
                            owner
                    ),
                    400,
                    "INVALID_REQUEST"
            );
        }

        JsonNode changed = ok(
                patch(path(visit))
                        .contentType("application/json")
                        .content("{\"plannedDate\":\"2026-11-08\"}"),
                owner,
                200
        );

        assertThat(changed.get("createdAt"))
                .isEqualTo(visit.get("createdAt"));

        JsonNode same = ok(
                patch(path(visit))
                        .contentType("application/json")
                        .content("{\"plannedDate\":\"2026-11-08\"}"),
                owner,
                200
        );

        assertThat(same).isEqualTo(changed);

        JsonNode cancelled = ok(
                post(path(visit) + "/cancel"),
                owner,
                200
        );

        assertThat(
                ok(post(path(visit) + "/cancel"), owner, 200)
        ).isEqualTo(cancelled);

        error(
                send(
                        patch(path(visit))
                                .contentType("application/json")
                                .content("{\"plannedDate\":\"2026-11-09\"}"),
                        owner
                ),
                409,
                "INVALID_VISIT_STATE"
        );

        assertThat(cancelled.get("cancelledAt").isNull()).isFalse();
    }

    @Test
    void inProgressAndCompletedVisitsCannotBeChangedOrCancelled()
            throws Exception {
        for (String state : List.of("QUIZ_IN_PROGRESS", "COMPLETED")) {
            Actor actor = actor();

            JsonNode visit = ok(
                    createRequest("request-0001", body()),
                    actor,
                    201
            );

            UUID set = quiz(
                    place,
                    null,
                    state.equals("COMPLETED") ? 2 : 1,
                    "DRAFT"
            );

            jdbc.update(
                    "UPDATE app.visits "
                            + "SET status = ?, "
                            + "quiz_set_id = ?, "
                            + "reported_at = CURRENT_TIMESTAMP, "
                            + "completed_at = CASE "
                            + "WHEN ? = 'COMPLETED' "
                            + "THEN CURRENT_TIMESTAMP ELSE NULL END "
                            + "WHERE id = ?",
                    state,
                    set,
                    state,
                    UUID.fromString(visit.get("id").asText())
            );

            error(
                    send(post(path(visit) + "/cancel"), actor),
                    409,
                    "INVALID_VISIT_STATE"
            );

            error(
                    send(
                            patch(path(visit))
                                    .contentType("application/json")
                                    .content("{\"plannedDate\":\"2026-11-08\"}"),
                            actor
                    ),
                    409,
                    "INVALID_VISIT_STATE"
            );
        }
    }

    @Test
    void hiddenPlacesRemainInOwnedHistoryAndReplays()
            throws Exception {
        JsonNode visit = ok(
                createRequest("request-0001", body()),
                owner,
                201
        );

        jdbc.update(
                "UPDATE app.places SET status = 'HIDDEN' WHERE id = ?",
                place
        );

        assertThat(
                ok(get(path(visit)), owner, 200)
                        .get("placeAvailable")
                        .asBoolean()
        ).isFalse();

        assertThat(
                ok(get(PATH), owner, 200).get("items").size()
        ).isEqualTo(1);

        assertThat(
                ok(
                        createRequest("request-0001", body()),
                        owner,
                        200
                ).get("placeAvailable").asBoolean()
        ).isFalse();

        error(
                send(createRequest("request-0001", body()), actor()),
                409,
                "PLACE_UNAVAILABLE"
        );
    }

    @Test
    void paginationIsStableForEqualTimestampsAndFiltersAreBoundToTheCursor()
            throws Exception {
        for (int i = 0; i < 5; i++) {
            cancelledFixture(owner, place);
        }

        var expected = jdbc.queryForList(
                "SELECT id FROM app.visits ORDER BY created_at DESC, id DESC",
                UUID.class
        );

        List<UUID> ids = new ArrayList<>();
        String cursor = null;

        do {
            var request = get(PATH)
                    .param("limit", "2")
                    .param("status", "CANCELLED");

            if (cursor != null) {
                request.param("cursor", cursor);
            }

            JsonNode page = ok(request, owner, 200);

            page.get("items").forEach(
                    visit -> ids.add(
                            UUID.fromString(visit.get("id").asText())
                    )
            );

            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").asText();
        } while (cursor != null);

        assertThat(ids).containsExactlyElementsOf(expected);

        String first = ok(
                get(PATH)
                        .param("limit", "2")
                        .param("status", "CANCELLED"),
                owner,
                200
        ).get("nextCursor").asText();

        error(
                send(get(PATH).param("cursor", first), owner),
                400,
                "INVALID_CURSOR"
        );

        error(
                send(
                        get(PATH)
                                .param("status", "CANCELLED")
                                .param("cursor", first),
                        actor()
                ),
                400,
                "INVALID_CURSOR"
        );

        error(
                send(
                        get(PATH)
                                .param("status", "CANCELLED")
                                .param("placeId", place.toString())
                                .param("cursor", first),
                        owner
                ),
                400,
                "INVALID_CURSOR"
        );

        assertThat(
                ok(
                        get(PATH).param("status", "PLANNED"),
                        owner,
                        200
                ).get("items").isEmpty()
        ).isTrue();

        assertThat(
                ok(
                        get(PATH)
                                .param("placeId", UUID.randomUUID().toString()),
                        owner,
                        200
                ).get("items").isEmpty()
        ).isTrue();

        assertThat(
                ok(
                        get(PATH)
                                .param("status", "CANCELLED")
                                .param("placeId", place.toString()),
                        owner,
                        200
                ).get("items").size()
        ).isEqualTo(5);
    }

    @Test
    void malformedQueriesBodiesAndExtremeCursorsReturn400()
            throws Exception {
        for (var request : List.of(
                get(PATH).param("limit", "0"),
                get(PATH).param("limit", "101"),
                get(PATH).param("limit", "2", "3"),
                get(PATH).param("unknown", "x"),
                get(PATH).param("status", "PLANNED,PLANNED"),
                get(PATH).param("status", ""),
                get(PATH).param("placeId", "bad"),
                get(PATH).content("{}")
        )) {
            error(send(request, owner), 400, "INVALID_REQUEST");
        }

        String hash = com.podo.server.visit.input.VisitFilter
                .parse(null, null, null)
                .hash();

        for (String time : List.of(
                Instant.MAX.toString(),
                "2026-11-06T12:00:00.123456789Z"
        )) {
            String payload = "visits\n1\n"
                    + owner.id() + "\n"
                    + hash + "\n"
                    + time + "\n"
                    + UUID.randomUUID();

            String cursor = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            payload.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8
                            )
                    );

            error(
                    send(get(PATH).param("cursor", cursor), owner),
                    400,
                    "INVALID_CURSOR"
            );
        }
    }

    @Test
    void progressCountsOnlyCurrentSetAndInvalidationHidesTheScore()
            throws Exception {
        JsonNode visit = ok(
                createRequest("request-0001", body()),
                owner,
                201
        );

        UUID visitId = UUID.fromString(visit.get("id").asText());
        UUID source = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO app.place_sources "
                        + "(id, place_id, source_type, fact_text, status) "
                        + "VALUES (?, ?, 'FIELD_NOTE', 'test', 'ACTIVE')",
                source,
                place
        );

        UUID oldSet = quiz(place, null, 1, "RETIRED");
        UUID current = quiz(place, null, 2, "PUBLISHED");

        for (UUID set : List.of(oldSet, current)) {
            UUID question = UUID.randomUUID();

            jdbc.update(
                    "INSERT INTO app.quiz_questions "
                            + "(id, quiz_set_id, position, question_text, "
                            + "correct_answer, explanation, source_id) "
                            + "VALUES (?, ?, 1, 'test', 'O', 'test', ?)",
                    question,
                    set,
                    source
            );

            jdbc.update(
                    "INSERT INTO app.quiz_answers "
                            + "VALUES (?, ?, 'O', true, CURRENT_TIMESTAMP)",
                    visitId,
                    question
            );

            jdbc.update(
                    "INSERT INTO app.quiz_hint_views "
                            + "VALUES (?, ?, 1, CURRENT_TIMESTAMP)",
                    visitId,
                    question
            );
        }

        jdbc.update(
                "UPDATE app.visits "
                        + "SET status = 'QUIZ_IN_PROGRESS', "
                        + "quiz_set_id = ?, reported_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ?",
                current,
                visitId
        );

        JsonNode progress = ok(get(path(visit)), owner, 200);

        assertThat(progress.get("answeredCount").asInt()).isEqualTo(1);
        assertThat(progress.get("correctCount").asInt()).isEqualTo(1);
        assertThat(progress.get("hintUsageCount").asInt()).isEqualTo(1);

        jdbc.update(
                "UPDATE app.quiz_sets SET status = 'INVALIDATED' WHERE id = ?",
                current
        );

        assertThat(
                ok(get(path(visit)), owner, 200)
                        .get("correctCount")
                        .isNull()
        ).isTrue();
    }

    @Test
    void schemaSeparatesLegacySetsAndPhotoSpecificVersions()
            throws Exception {
        JsonNode visit = ok(
                createRequest("request-0001", body()),
                owner,
                201
        );

        UUID first = observation(
                UUID.fromString(visit.get("id").asText())
        );

        UUID second = observation(
                UUID.fromString(visit.get("id").asText())
        );

        quiz(place, null, 1, "PUBLISHED");
        quiz(place, first, 1, "PUBLISHED");
        quiz(place, second, 1, "PUBLISHED");

        assertThatThrownBy(
                () -> quiz(place, first, 2, "PUBLISHED")
        ).isInstanceOf(
                org.springframework.dao.DataIntegrityViolationException.class
        );

        quiz(place, first, 2, "DRAFT");

        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM app.quiz_sets",
                        Integer.class
                )
        ).isEqualTo(4);
    }

    private record Actor(UUID id, String token) {}

    private Actor actor() {
        var login = sessions.issueForKakaoSubject(
                "VISIT_TEST_" + UUID.randomUUID()
        );

        var token = sessions.rotateRefresh(login.refreshToken());

        return new Actor(token.accountId(), token.accessToken());
    }

    private UUID place(String status) {
        UUID id = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO app.places "
                        + "(id, name, address, latitude, longitude, "
                        + "main_activities, environment, status, updated_at) "
                        + "VALUES (?, '시험 장소', '용인', 37.2, 127.2, "
                        + "ARRAY['관찰'], 'INDOOR', ?, CURRENT_TIMESTAMP)",
                id,
                status
        );

        return id;
    }

    private UUID profile(Actor actor) {
        UUID id = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO app.child_profiles "
                        + "(id, account_id, nickname, color_code, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, '아이', 'TEST', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id,
                actor.id()
        );

        return id;
    }

    private UUID quiz(
            UUID placeId,
            UUID observation,
            int version,
            String status
    ) {
        UUID id = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO app.quiz_sets "
                        + "(id, place_id, observation_id, version, status) "
                        + "VALUES (?, ?, ?, ?, ?)",
                id,
                placeId,
                observation,
                version,
                status
        );

        return id;
    }

    private UUID observation(UUID visit) {
        UUID id = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO app.observations "
                        + "(id, visit_id, storage_key, mime_type, "
                        + "size_bytes, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'image/jpeg', 10, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id,
                visit,
                id.toString()
        );

        return id;
    }

    private void cancelledFixture(Actor actor, UUID placeId) {
        UUID id = UUID.randomUUID();

        Timestamp time = Timestamp.from(
                Instant.parse("2026-11-06T01:00:00.123456Z")
        );

        jdbc.update(
                "INSERT INTO app.visits "
                        + "(id, account_id, place_id, planned_date, "
                        + "source, status, create_request_key, "
                        + "create_request_hash, cancelled_at, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, '2026-11-07', 'MAP', "
                        + "'CANCELLED', ?, 'fixture', ?, ?, ?)",
                id,
                actor.id(),
                placeId,
                id.toString(),
                time,
                time,
                time
        );
    }

    private String body() {
        return "{\"placeId\":\""
                + place
                + "\",\"plannedDate\":\"2026-11-07\",\"source\":\"MAP\"}";
    }

    private String path(JsonNode visit) {
        return PATH + "/" + visit.get("id").asText();
    }

    private MockHttpServletRequestBuilder createRequest(
            String key,
            String body
    ) {
        return post(PATH)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body);
    }

    private MockHttpServletResponse send(
            MockHttpServletRequestBuilder request,
            Actor actor
    ) throws Exception {
        return mvc.perform(
                request.header("Authorization", "Bearer " + actor.token())
        ).andReturn().getResponse();
    }

    private JsonNode ok(
            MockHttpServletRequestBuilder request,
            Actor actor,
            int status
    ) throws Exception {
        var response = send(request, actor);

        assertThat(response.getStatus())
                .withFailMessage(response.getContentAsString())
                .isEqualTo(status);

        assertThat(response.getHeader("Cache-Control"))
                .contains("no-store");

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

    private List<MockHttpServletResponse> race(
            Callable<MockHttpServletResponse> a,
            Callable<MockHttpServletResponse> b
    ) throws Exception {
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return a.call();
            });

            var second = executor.submit(() -> {
                start.await();
                return b.call();
            });

            start.countDown();

            return List.of(
                    first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS)
            );
        }
    }
}