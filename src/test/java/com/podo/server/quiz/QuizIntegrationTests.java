package com.podo.server.quiz;

import com.podo.server.auth.service.AuthSessionService;
import com.podo.server.quiz.domain.QuizContent;
import com.podo.server.quiz.domain.QuizSet;
import com.podo.server.quiz.repository.QuizRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class QuizIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthSessionService sessions;
    @Autowired QuizRepository quizRepository;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    private Actor owner;
    private UUID place;
    private UUID visit;
    private long photoSequence;

    @BeforeEach void reset() throws Exception {
        jdbc.execute("TRUNCATE app.accounts, app.places CASCADE");
        owner = actor();
        place = place();
        visit = visit(owner, place);
        photoSequence = 0;
    }

    @Test void allFiveOperationsRequireAuthentication() throws Exception {
        for (var request : List.of(post(path("/report")), get(path("/quiz")),
                answer(UUID.randomUUID(), "O"), hint(UUID.randomUUID(), 1), post(path("/quiz/restart")))) {
            error(mvc.perform(request).andReturn().getResponse(), 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test void anUnpreparedQuizDoesNotChangeThePlannedVisit() throws Exception {
        error(send(post(path("/report")), owner), 409, "QUIZ_NOT_READY");
        assertPlanned();
        error(send(get(path("/quiz")), owner), 409, "INVALID_VISIT_STATE");
        error(send(answer(UUID.randomUUID(), "O"), owner), 409, "INVALID_VISIT_STATE");
        error(send(hint(UUID.randomUUID(), 1), owner), 409, "INVALID_VISIT_STATE");
        error(send(post(path("/quiz/restart")), owner), 409, "INVALID_VISIT_STATE");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void exactlyThreeQuestionsAreRequired(int count) throws Exception {
        quiz(place, null, 1, "PUBLISHED", count, true);
        error(send(post(path("/report")), owner), 409, "QUIZ_NOT_READY");
        assertPlanned();
    }

    @Test void reviewHintsAndSourcePlaceAreValidatedBeforeStart() throws Exception {
        var quiz = quiz(place, null, 1, "PUBLISHED", 3, false);
        error(send(post(path("/report")), owner), 409, "QUIZ_NOT_READY");
        jdbc.update("UPDATE app.quiz_sets SET reviewed_by = 'test', reviewed_at = now(), published_at = now() WHERE id = ?", quiz.id());
        jdbc.update("UPDATE app.quiz_questions SET hint_2_text = NULL WHERE id = ?", quiz.questions().getFirst());
        error(send(post(path("/report")), owner), 409, "QUIZ_NOT_READY");
        jdbc.update("UPDATE app.quiz_questions SET hint_2_text = '비공개힌트2' WHERE id = ?", quiz.questions().getFirst());
        UUID wrongSource = source(place());
        jdbc.update("UPDATE app.quiz_questions SET source_id = ? WHERE id = ?", wrongSource, quiz.questions().getFirst());
        error(send(post(path("/report")), owner), 409, "QUIZ_NOT_READY");
        assertPlanned();
        jdbc.update("UPDATE app.quiz_questions SET source_id = ? WHERE id = ?", source(place), quiz.questions().getFirst());
        assertThat(ok(post(path("/report")), owner).get("status").asText()).isEqualTo("QUIZ_IN_PROGRESS");
    }

    @Test void initialReadsHideAnswersExplanationsAndHintBodies() throws Exception {
        var quiz = prepared();
        JsonNode started = ok(post(path("/report")), owner);
        JsonNode progress = ok(get(path("/quiz")), owner);
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/quiz-progress-example.json"), progress.toPrettyString());
        assertThat(progress.get("quizSetId").asText()).isEqualTo(quiz.id().toString());
        assertThat(progress.get("introduction").asText()).isEqualTo("아이 눈높이 소개 1");
        assertThat(progress.get("questions").size()).isEqualTo(3);
        assertThat(progress.toString()).doesNotContain("비공개힌트", "정답해설");
        for (JsonNode question : progress.get("questions")) {
            assertThat(question.get("submittedAnswer").isNull()).isTrue();
            assertThat(question.get("feedback").isNull()).isTrue();
            assertThat(question.has("correctAnswer")).isFalse();
            assertThat(question.get("viewedHintSteps").isEmpty()).isTrue();
        }
        JsonNode result = ok(answer(quiz.questions().getFirst(), "O"), owner);
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/quiz-answer-example.json"), result.toPrettyString());
        assertThat(result.at("/feedback/isCorrect").asBoolean()).isTrue();
        assertThat(result.at("/feedback/explanationSource").asText()).isEqualTo("STORED_CONTENT");
        assertThat(result.at("/feedback/sources").size()).isEqualTo(1);
        progress = ok(get(path("/quiz")), owner);
        assertThat(progress.at("/questions/0/feedback/correctAnswer").asText()).isEqualTo("O");
        assertThat(progress.at("/questions/1/feedback").isNull()).isTrue();
        assertThat(progress.at("/questions/2/feedback").isNull()).isTrue();
        assertThat(ok(post(path("/report")), owner).get("reportedAt")).isEqualTo(started.get("reportedAt"));
        assertThat(ok(get(path("/quiz")), owner).get("answeredCount").asInt()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"O", "X"})
    void allThreeAnswersCompleteTheVisitRegardlessOfScore(String selected) throws Exception {
        var quiz = prepared();
        ok(post(path("/report")), owner);
        JsonNode result = null;
        for (UUID question : quiz.questions()) result = ok(answer(question, selected), owner);
        assertThat(result.get("visitStatus").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("correctCount").asInt()).isEqualTo(selected.equals("O") ? 3 : 0);
        assertThat(result.get("answeredCount").asInt()).isEqualTo(3);
        assertThat(result.get("completedAt").isNull()).isFalse();
        assertThat(result.get("completionPlaceId").asText()).isEqualTo(place.toString());
        JsonNode replay = ok(answer(quiz.questions().getFirst(), selected), owner);
        assertThat(replay.get("answeredCount").asInt()).isEqualTo(3);
        assertThat(replay.get("completedAt")).isEqualTo(result.get("completedAt"));
        error(send(answer(quiz.questions().getFirst(), selected.equals("O") ? "X" : "O"), owner),
                409, "ANSWER_ALREADY_SUBMITTED");
        assertThat(ok(post(path("/report")), owner).get("status").asText()).isEqualTo("COMPLETED");
        JsonNode detail = ok(get("/api/visits/" + visit), owner);
        assertThat(detail.get("answeredCount").asInt()).isEqualTo(3);
        assertThat(detail.get("correctCount")).isEqualTo(result.get("correctCount"));
    }

    @Test void hintsAreIdempotentAndCompletionOnlyAllowsPreviouslyViewedSteps() throws Exception {
        var quiz = prepared();
        ok(post(path("/report")), owner);
        UUID question = quiz.questions().getFirst();
        JsonNode first = ok(hint(question, 1), owner);
        Instant viewedAt = jdbc.queryForObject("SELECT first_viewed_at FROM app.quiz_hint_views", Timestamp.class).toInstant();
        assertThat(first.get("text").asText()).isEqualTo("비공개힌트1");
        assertThat(ok(hint(question, 1), owner)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT first_viewed_at FROM app.quiz_hint_views", Timestamp.class).toInstant()).isEqualTo(viewedAt);
        assertThat(ok(get(path("/quiz")), owner).get("answeredCount").asInt()).isZero();
        for (UUID id : quiz.questions()) ok(answer(id, "O"), owner);
        assertThat(ok(hint(question, 1), owner)).isEqualTo(first);
        error(send(hint(question, 2), owner), 409, "INVALID_VISIT_STATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.quiz_hint_views", Integer.class)).isEqualTo(1);
    }

    @Test void foreignAccountsAndForeignQuestionsAreRejected() throws Exception {
        var quiz = prepared();
        ok(post(path("/report")), owner);
        Actor other = actor();
        for (var request : List.of(post(path("/report")), get(path("/quiz")), answer(quiz.questions().getFirst(), "O"),
                hint(quiz.questions().getFirst(), 1), post(path("/quiz/restart")))) {
            error(send(request, other), 404, "RESOURCE_NOT_FOUND");
        }
        var otherQuiz = quiz(place(), null, 1, "PUBLISHED", 3, true);
        error(send(answer(otherQuiz.questions().getFirst(), "O"), owner), 404, "RESOURCE_NOT_FOUND");
        error(send(hint(otherQuiz.questions().getFirst(), 1), owner), 404, "RESOURCE_NOT_FOUND");
    }

    @Test void retiredSetsContinueWithoutSwitchingToANewerPublishedSet() throws Exception {
        var old = prepared();
        ok(post(path("/report")), owner);
        jdbc.update("UPDATE app.quiz_sets SET status = 'RETIRED' WHERE id = ?", old.id());
        var next = quiz(place, null, 2, "PUBLISHED", 3, true);
        assertThat(ok(get(path("/quiz")), owner).get("quizAvailability").asText()).isEqualTo("READY");
        ok(answer(old.questions().getFirst(), "O"), owner);
        assertThat(ok(post(path("/quiz/restart")), owner).get("quizSetId").asText()).isEqualTo(old.id().toString());
        error(send(answer(next.questions().getFirst(), "O"), owner), 404, "RESOURCE_NOT_FOUND");
    }

    @Test void invalidationHidesFeedbackAndStopsNewAnswersAndAllHintReads() throws Exception {
        var quiz = prepared();
        ok(post(path("/report")), owner);
        ok(answer(quiz.questions().getFirst(), "O"), owner);
        ok(hint(quiz.questions().getFirst(), 1), owner);
        invalidate(quiz.id());
        JsonNode progress = ok(get(path("/quiz")), owner);
        assertThat(progress.get("quizAvailability").asText()).isEqualTo("CORRECTION_REQUIRED");
        assertThat(progress.get("correctCount").isNull()).isTrue();
        assertThat(progress.at("/questions/0/feedback").isNull()).isTrue();
        assertThat(progress.get("restartAvailable").asBoolean()).isFalse();
        error(send(answer(quiz.questions().get(1), "O"), owner), 409, "QUIZ_CONTENT_CHANGED");
        error(send(hint(quiz.questions().getFirst(), 1), owner), 409, "QUIZ_CONTENT_CHANGED");
        JsonNode replay = ok(answer(quiz.questions().getFirst(), "O"), owner);
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/quiz-invalidated-answer-example.json"), replay.toPrettyString());
        assertThat(replay.get("feedback").isNull()).isTrue();
        assertThat(replay.get("correctCount").isNull()).isTrue();
        error(send(answer(quiz.questions().getFirst(), "X"), owner), 409, "ANSWER_ALREADY_SUBMITTED");
        error(send(post(path("/quiz/restart")), owner), 409, "QUIZ_NOT_READY");
        assertThat(ok(get("/api/visits/" + visit), owner).get("answeredCount").asInt()).isEqualTo(1);
    }

    @Test void invalidatingACompletedQuizPreservesCompletion() throws Exception {
        var quiz = prepared();
        ok(post(path("/report")), owner);
        for (UUID id : quiz.questions()) ok(answer(id, "X"), owner);
        JsonNode before = ok(get("/api/visits/" + visit), owner);
        invalidate(quiz.id());
        JsonNode progress = ok(get(path("/quiz")), owner);
        assertThat(progress.get("visitStatus").asText()).isEqualTo("COMPLETED");
        assertThat(progress.get("correctCount").isNull()).isTrue();
        assertThat(ok(get("/api/visits/" + visit), owner).get("completedAt")).isEqualTo(before.get("completedAt"));
        error(send(post(path("/quiz/restart")), owner), 409, "INVALID_VISIT_STATE");
    }

    @Test void hiddenPlacesBlockNewWritesButAllowStoredAnswerReplayAndHistory() throws Exception {
        var quiz = prepared();
        JsonNode started = ok(post(path("/report")), owner);
        ok(answer(quiz.questions().getFirst(), "O"), owner);
        ok(hint(quiz.questions().getFirst(), 1), owner);
        jdbc.update("UPDATE app.places SET status = 'HIDDEN' WHERE id = ?", place);
        error(send(answer(quiz.questions().get(1), "O"), owner), 409, "PLACE_UNAVAILABLE");
        error(send(hint(quiz.questions().getFirst(), 1), owner), 409, "PLACE_UNAVAILABLE");
        error(send(post(path("/quiz/restart")), owner), 409, "PLACE_UNAVAILABLE");
        assertThat(ok(answer(quiz.questions().getFirst(), "O"), owner).get("answeredCount").asInt()).isEqualTo(1);
        assertThat(ok(get(path("/quiz")), owner).get("answeredCount").asInt()).isEqualTo(1);
        assertThat(ok(post(path("/report")), owner).get("reportedAt")).isEqualTo(started.get("reportedAt"));
    }

    @Test void correctionRestartPreservesHistoryAndFirstReportTime() throws Exception {
        var old = prepared();
        JsonNode started = ok(post(path("/report")), owner);
        ok(answer(old.questions().getFirst(), "O"), owner);
        ok(hint(old.questions().getFirst(), 1), owner);
        invalidate(old.id());
        var next = quiz(place, null, 2, "PUBLISHED", 3, true);
        assertThat(ok(get(path("/quiz")), owner).get("restartAvailable").asBoolean()).isTrue();
        JsonNode restarted = ok(post(path("/quiz/restart")), owner);
        assertThat(restarted.get("quizSetId").asText()).isEqualTo(next.id().toString());
        assertThat(restarted.get("answeredCount").asInt()).isZero();
        assertThat(restarted.get("hintUsageCount").asInt()).isZero();
        assertThat(restarted.get("introduction").asText()).isEqualTo("아이 눈높이 소개 2");
        assertThat(ok(get("/api/visits/" + visit), owner).get("reportedAt")).isEqualTo(started.get("reportedAt"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.quiz_answers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.quiz_hint_views", Integer.class)).isEqualTo(1);
        error(send(answer(old.questions().getFirst(), "O"), owner), 404, "RESOURCE_NOT_FOUND");
        error(send(hint(old.questions().getFirst(), 1), owner), 404, "RESOURCE_NOT_FOUND");
        ok(answer(next.questions().getFirst(), "X"), owner);
        assertThat(ok(post(path("/quiz/restart")), owner).get("answeredCount").asInt()).isEqualTo(1);
    }

    @Test void photosDoNotFallBackToTheLegacySetWhenGenerationIsNotReady() throws Exception {
        prepared();
        UUID photo = observation(visit, "UPLOADED");
        error(send(post(path("/report")), owner), 409, "QUIZ_NOT_READY");
        jdbc.update("UPDATE app.observations SET generation_status = 'READY' WHERE id = ?", photo);
        error(send(post(path("/report")), owner), 409, "QUIZ_NOT_READY");
        var own = quiz(place, photo, 1, "PUBLISHED", 3, true);
        assertThat(ok(post(path("/report")), owner).get("quizSetId").asText()).isEqualTo(own.id().toString());
    }

    @Test void theNewestUnreadyPhotoCannotSilentlySelectAnOlderReadyPhoto() throws Exception {
        UUID older = observation(visit, "READY");
        quiz(place, older, 1, "PUBLISHED", 3, true);
        observation(visit, "FAILED");
        error(send(post(path("/report")), owner), 409, "QUIZ_NOT_READY");
        assertPlanned();
    }

    @Test void privatePhotoSetsCannotBeUsedByAnotherVisit() throws Exception {
        Actor other = actor();
        UUID otherVisit = visit(other, place);
        UUID otherPhoto = observation(otherVisit, "READY");
        var otherQuiz = quiz(place, otherPhoto, 1, "PUBLISHED", 3, true);
        error(send(post(path("/report")), owner), 409, "QUIZ_NOT_READY");
        jdbc.update("UPDATE app.visits SET quiz_set_id = ?, status = 'QUIZ_IN_PROGRESS', reported_at = now() WHERE id = ?",
                otherQuiz.id(), visit);
        error(send(get(path("/quiz")), owner), 409, "QUIZ_CONTENT_CHANGED");
        error(send(answer(otherQuiz.questions().getFirst(), "O"), owner), 409, "QUIZ_CONTENT_CHANGED");
    }

    @Test void photoCorrectionsStayWithinTheSameObservation() throws Exception {
        UUID ownPhoto = observation(visit, "READY");
        var own = quiz(place, ownPhoto, 1, "PUBLISHED", 3, true);
        ok(post(path("/report")), owner);
        invalidate(own.id());
        UUID otherVisit = visit(actor(), place);
        var other = quiz(place, observation(otherVisit, "READY"), 2, "PUBLISHED", 3, true);
        assertThat(ok(get(path("/quiz")), owner).get("restartAvailable").asBoolean()).isFalse();
        error(send(post(path("/quiz/restart")), owner), 409, "QUIZ_NOT_READY");
        var corrected = quiz(place, ownPhoto, 2, "PUBLISHED", 3, true);
        JsonNode restarted = ok(post(path("/quiz/restart")), owner);
        assertThat(restarted.get("quizSetId").asText()).isEqualTo(corrected.id().toString());
        assertThat(restarted.get("quizSetId").asText()).isNotEqualTo(other.id().toString());
    }

    @Test void lowerVersionsAreNotAcceptedAsCorrections() throws Exception {
        var old = quiz(place, null, 5, "PUBLISHED", 3, true);
        ok(post(path("/report")), owner);
        invalidate(old.id());
        quiz(place, null, 1, "PUBLISHED", 3, true);
        assertThat(ok(get(path("/quiz")), owner).get("restartAvailable").asBoolean()).isFalse();
        error(send(post(path("/quiz/restart")), owner), 409, "QUIZ_NOT_READY");
    }

    @Test void concurrentIdenticalAnswersAreStoredOnlyOnce() throws Exception {
        var quiz = prepared();
        ok(post(path("/report")), owner);
        var results = race(() -> send(answer(quiz.questions().getFirst(), "O"), owner),
                () -> send(answer(quiz.questions().getFirst(), "O"), owner));
        assertThat(results.stream().map(MockHttpServletResponse::getStatus)).containsExactly(200, 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.quiz_answers", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentDifferentAnswersDoNotReplaceTheFirstAnswer() throws Exception {
        var quiz = prepared();
        ok(post(path("/report")), owner);
        var results = race(() -> send(answer(quiz.questions().getFirst(), "O"), owner),
                () -> send(answer(quiz.questions().getFirst(), "X"), owner));
        assertThat(results.stream().map(MockHttpServletResponse::getStatus)).containsExactlyInAnyOrder(200, 409);
        error(results.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow(), 409, "ANSWER_ALREADY_SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.quiz_answers", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentFinalAnswersCompleteExactlyOnce() throws Exception {
        var quiz = prepared();
        ok(post(path("/report")), owner);
        ok(answer(quiz.questions().getFirst(), "O"), owner);
        var results = race(() -> send(answer(quiz.questions().get(1), "O"), owner),
                () -> send(answer(quiz.questions().get(2), "O"), owner));
        assertThat(results.stream().map(MockHttpServletResponse::getStatus)).containsExactly(200, 200);
        JsonNode detail = ok(get("/api/visits/" + visit), owner);
        assertThat(detail.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.get("answeredCount").asInt()).isEqualTo(3);
        assertThat(detail.get("completedAt").isNull()).isFalse();
    }

    @Test void concurrentHintRequestsCountOnce() throws Exception {
        var quiz = prepared();
        ok(post(path("/report")), owner);
        var results = race(() -> send(hint(quiz.questions().getFirst(), 1), owner),
                () -> send(hint(quiz.questions().getFirst(), 1), owner));
        assertThat(results.stream().map(MockHttpServletResponse::getStatus)).containsExactly(200, 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.quiz_hint_views", Integer.class)).isEqualTo(1);
    }

    @Test void reportingAndCancellationCannotBothSucceed() throws Exception {
        prepared();
        var results = race(() -> send(post(path("/report")), owner),
                () -> send(post("/api/visits/" + visit + "/cancel"), owner));
        assertThat(results.stream().map(MockHttpServletResponse::getStatus)).containsExactlyInAnyOrder(200, 409);
        error(results.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow(), 409, "INVALID_VISIT_STATE");
        assertThat(ok(get("/api/visits/" + visit), owner).get("status").asText())
                .isIn("CANCELLED", "QUIZ_IN_PROGRESS");
    }

    @Test void aFailedCompletionRollsBackTheThirdAnswerAsWell() throws Exception {
        var quiz = prepared();
        ok(post(path("/report")), owner);
        ok(answer(quiz.questions().getFirst(), "O"), owner);
        ok(answer(quiz.questions().get(1), "O"), owner);
        jdbc.execute("""
                CREATE FUNCTION app.test_reject_completion() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test completion failure'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_reject_completion BEFORE UPDATE OF status ON app.visits
                FOR EACH ROW WHEN (NEW.status = 'COMPLETED') EXECUTE FUNCTION app.test_reject_completion()
                """);
        try {
            error(send(answer(quiz.questions().get(2), "O"), owner), 500, "INTERNAL_ERROR");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app.quiz_answers", Integer.class)).isEqualTo(2);
            assertThat(ok(get("/api/visits/" + visit), owner).get("status").asText()).isEqualTo("QUIZ_IN_PROGRESS");
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_completion ON app.visits");
            jdbc.execute("DROP FUNCTION app.test_reject_completion()");
        }
        assertThat(ok(answer(quiz.questions().get(2), "O"), owner).get("visitStatus").asText()).isEqualTo("COMPLETED");
    }

    @Test void malformedBodiesQueriesAndPathsAreRejected() throws Exception {
        var quiz = prepared();
        ok(post(path("/report")), owner);
        UUID question = quiz.questions().getFirst();
        for (String body : List.of("{}", "[]", "null", "{\"answer\":null}", "{\"answer\":1}",
                "{\"answer\":\"o\"}", "{\"answer\":\"O\",\"isCorrect\":true}")) {
            error(send(put(path("/answers/" + question)).contentType("application/json").content(body), owner),
                    400, "INVALID_REQUEST");
        }
        for (var request : List.of(get(path("/quiz")).param("x", "1"), get(path("/quiz")).content("{}"),
                post(path("/report")).contentType("application/json").content("{}"),
                post(path("/quiz/restart")).content("{}"), hint(question, 1).content("{}"),
                hint(question, 0), hint(question, 3), answer(question, "O").param("x", "1"),
                put(path("/answers/1-1-1-1-1")).contentType("application/json").content("{\"answer\":\"O\"}"))) {
            error(send(request, owner), 400, "INVALID_REQUEST");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.quiz_answers", Integer.class)).isZero();
    }

    @Test void swaggerContainsAllFiveOperationsAndTheStrictAnswerSchema() throws Exception {
        JsonNode docs = mapper.readTree(mvc.perform(get("/v3/api-docs/podo")).andReturn().getResponse().getContentAsByteArray());
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/quiz-openapi.json"), docs.toPrettyString());
        String prefix = "/api/visits/{visitId}";
        for (String path : List.of("/report", "/quiz", "/answers/{questionId}",
                "/hint-views/{questionId}/{step}", "/quiz/restart")) {
            assertThat(docs.get("paths").has(prefix + path)).isTrue();
        }
        assertThat(docs.at("/components/schemas/SubmitQuizAnswer/additionalProperties").asBoolean()).isFalse();
        assertThat(docs.at("/components/schemas/SubmitQuizAnswer/properties/answer/enum").toString()).contains("O", "X");
        assertThat(docs.at("/components/schemas/QuizQuestion/properties/feedback/$ref").asText())
                .isEqualTo("#/components/schemas/NullableQuizFeedback");
        assertThat(docs.at("/components/schemas/NullableQuizFeedback/anyOf").toString())
                .contains("#/components/schemas/QuizFeedback", "#/components/schemas/QuizNull");
        assertThat(docs.at("/components/schemas/QuizNull/type").asText()).isEqualTo("null");
    }

    @Test void jpaDoesNotOverwriteAnExistingAnswerOnDuplicateInsert() throws Exception {
        var quiz = prepared();
        ok(post(path("/report")), owner);
        UUID question = quiz.questions().getFirst();
        ok(answer(question, "O"), owner);
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                quizRepository.answer(visit, question, "X", false, Instant.now())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(ok(get(path("/quiz")), owner).at("/questions/0/submittedAnswer").asText()).isEqualTo("O");
        assertThat(ok(get(path("/quiz")), owner).get("correctCount").asInt()).isEqualTo(1);
    }

    @Test void setProjectionReadsTheDatabaseInsteadOfAStaleManagedEntity() {
        var quiz = prepared();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            QuizSet cached = entityManager.find(QuizSet.class, quiz.id());
            assertThat(cached.getStatus()).isEqualTo(QuizContent.Status.PUBLISHED);
            jdbc.update("UPDATE app.quiz_sets SET status = 'INVALIDATED' WHERE id = ?", quiz.id());
            assertThat(cached.getStatus()).isEqualTo(QuizContent.Status.PUBLISHED);
            assertThat(quizRepository.findSet(quiz.id()).orElseThrow().status())
                    .isEqualTo(QuizContent.Status.INVALIDATED);
        });
    }

    private record Actor(UUID id, String token) {}
    private record Fixture(UUID id, List<UUID> questions) {}
    private Actor actor() {
        var login = sessions.issueForKakaoSubject("QUIZ_TEST_" + UUID.randomUUID());
        var tokens = sessions.rotateRefresh(login.refreshToken());
        return new Actor(tokens.accountId(), tokens.accessToken());
    }
    private UUID place() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.places (id, name, address, latitude, longitude, main_activities, environment, status, updated_at) VALUES (?, '시험 장소', '용인', 37.2, 127.2, ARRAY['관찰'], 'INDOOR', 'PUBLISHED', now())", id);
        return id;
    }
    private UUID visit(Actor actor, UUID place) throws Exception {
        var response = ok(post("/api/visits").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").content("{\"placeId\":\"" + place + "\",\"plannedDate\":\"2026-11-07\",\"source\":\"MAP\"}"), actor, 201);
        return UUID.fromString(response.get("id").asText());
    }
    private UUID source(UUID place) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.place_sources (id, place_id, source_type, source_url, fact_text, checked_at, status) VALUES (?, ?, 'OFFICIAL', 'https://example.test/history', '확인한 역사 자료', now(), 'ACTIVE')", id, place);
        return id;
    }
    private Fixture prepared() { return quiz(place, null, 1, "PUBLISHED", 3, true); }
    private Fixture quiz(UUID place, UUID observation, int version, String status, int count, boolean reviewed) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO app.quiz_sets (id, place_id, observation_id, introduction, version, status, reviewed_by, reviewed_at, published_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, place, observation, "아이 눈높이 소개 " + version, version, status,
                reviewed ? "test-reviewer" : null, reviewed ? now : null, reviewed ? now : null);
        UUID source = source(place);
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            UUID question = UUID.randomUUID(); ids.add(question);
            jdbc.update("INSERT INTO app.quiz_questions (id, quiz_set_id, position, question_text, correct_answer, explanation, source_id, hint_1_text, hint_2_text) VALUES (?, ?, ?, ?, 'O', ?, ?, '비공개힌트1', '비공개힌트2')", question, id, i, "관찰 문제 " + i, "정답해설 " + i, source);
        }
        return new Fixture(id, List.copyOf(ids));
    }
    private UUID observation(UUID visit, String status) {
        UUID id = UUID.randomUUID();
        Timestamp time = Timestamp.from(Instant.parse("2026-11-06T00:00:00Z").plusSeconds(++photoSequence));
        jdbc.update("INSERT INTO app.observations (id, visit_id, storage_key, mime_type, size_bytes, generation_status, created_at, updated_at) VALUES (?, ?, ?, 'image/jpeg', 10, ?, ?, ?)", id, visit, id.toString(), status, time, time);
        return id;
    }
    private void invalidate(UUID id) { jdbc.update("UPDATE app.quiz_sets SET status = 'INVALIDATED', invalidated_at = now() WHERE id = ?", id); }
    private void assertPlanned() throws Exception {
        JsonNode detail = ok(get("/api/visits/" + visit), owner);
        assertThat(detail.get("status").asText()).isEqualTo("PLANNED");
        assertThat(detail.get("quizSetId").isNull()).isTrue();
        assertThat(detail.get("reportedAt").isNull()).isTrue();
    }
    private String path(String suffix) { return "/api/visits/" + visit + suffix; }
    private MockHttpServletRequestBuilder answer(UUID id, String answer) {
        return put(path("/answers/" + id)).contentType("application/json").content("{\"answer\":\"" + answer + "\"}");
    }
    private MockHttpServletRequestBuilder hint(UUID id, int step) { return put(path("/hint-views/" + id + "/" + step)); }
    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, Actor actor) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + actor.token())).andReturn().getResponse();
    }
    private JsonNode ok(MockHttpServletRequestBuilder request, Actor actor) throws Exception { return ok(request, actor, 200); }
    private JsonNode ok(MockHttpServletRequestBuilder request, Actor actor, int status) throws Exception {
        var result = send(request, actor);
        assertThat(result.getStatus()).withFailMessage(result.getContentAsString()).isEqualTo(status);
        assertThat(result.getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getHeader("X-Request-Id")).isNotBlank();
        return mapper.readTree(result.getContentAsByteArray());
    }
    private void error(MockHttpServletResponse result, int status, String code) throws Exception {
        assertThat(result.getStatus()).withFailMessage(result.getContentAsString()).isEqualTo(status);
        assertThat(mapper.readTree(result.getContentAsByteArray()).at("/error/code").asText()).isEqualTo(code);
    }
    private List<MockHttpServletResponse> race(Callable<MockHttpServletResponse> a, Callable<MockHttpServletResponse> b) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return a.call(); });
            var second = executor.submit(() -> { start.await(); return b.call(); });
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }
}
