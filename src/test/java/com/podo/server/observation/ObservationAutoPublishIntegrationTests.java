package com.podo.server.observation;

import com.podo.server.observation.ai.ObservationAi.*;
import com.podo.server.observation.domain.ObservationData.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "podo.auth.api-origin=https://podo.test",
        "podo.auth.success-redirect-uri=https://podo.test/auth/success",
        "podo.auth.failure-redirect-uri=https://podo.test/auth/error",
        "podo.auth.csrf-signing-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "podo.observation.enabled=true",
        "podo.observation.api-key=test-placeholder",
        "podo.observation.auto-publish=true",
        "podo.observation.worker-enabled=false"
})
class ObservationAutoPublishIntegrationTests extends ObservationIntegrationTestSupport {

    @Test
    void automaticPublicationStartsTheQuizAndPreservesItsCompletedRecord()
            throws Exception {
        State state = upload(UUID.randomUUID().toString(), 3);
        Lease lease = transactions.claim().orElseThrow();
        var snapshot = transactions.snapshot(lease);
        Input input = input(snapshot);
        transactions.complete(lease, snapshot, input, output(input));

        var response = mvc.perform(get(path() + "/latest")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse();
        State generated = mapper.readValue(response.getContentAsByteArray(), State.class);
        assertThat(generated.id()).isEqualTo(state.id());
        assertThat(generated.generationStatus()).isEqualTo("READY");
        assertThat(generated.startAvailable()).isTrue();
        assertThat(generated.retryAvailable()).isFalse();

        var audit = jdbc.queryForMap("""
                SELECT status, review_method, reviewed_by, reviewed_at, published_at
                FROM app.quiz_sets WHERE observation_id = ?
                """, state.id());
        assertThat(audit.get("status")).isEqualTo("PUBLISHED");
        assertThat(audit.get("review_method")).isEqualTo("AUTOMATED");
        assertThat(audit.get("reviewed_by")).isEqualTo("SYSTEM:SOURCE_AND_SCHEMA_V1");
        assertThat(audit.get("reviewed_at")).isNotNull();
        assertThat(audit.get("published_at")).isNotNull();

        mvc.perform(post(visitPath("/report")).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        var progressResponse = mvc.perform(get(visitPath("/quiz"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse();
        var progress = mapper.readTree(progressResponse.getContentAsByteArray());
        assertThat(progress.get("questions").size()).isEqualTo(3);
        for (var question : progress.get("questions")) {
            assertThat(question.get("feedback").isNull()).isTrue();
            assertThat(question.get("observationGuide").asText()).isEqualTo("안내판을 살펴봐요.");
            assertThat(question.get("viewedHintSteps").isEmpty()).isTrue();
            mvc.perform(put(visitPath("/answers/" + question.get("questionId").asText()))
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json").content("{\"answer\":\"X\"}"))
                    .andExpect(status().isOk());
        }

        var detailResponse = mvc.perform(get(visitPath(""))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse();
        var detail = mapper.readTree(detailResponse.getContentAsByteArray());
        assertThat(detail.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.get("answeredCount").asInt()).isEqualTo(3);
        assertThat(detail.get("correctCount").asInt()).isZero();
        assertThat(service.get(accountId, visitId, state.id()).startAvailable()).isFalse();

        var historyResponse = mvc.perform(get("/api/visits")
                        .param("status", "COMPLETED").param("sort", "COMPLETED_AT_DESC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(mapper.readTree(historyResponse.getContentAsByteArray()).at("/items/0/id").asText())
                .isEqualTo(visitId.toString());
        var replay = mvc.perform(get(visitPath("/quiz")).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse();
        var replayed = mapper.readTree(replay.getContentAsByteArray());
        assertThat(replayed.get("quizSetId")).isEqualTo(progress.get("quizSetId"));
        for (var question : replayed.get("questions")) {
            assertThat(question.get("submittedAnswer").asText()).isEqualTo("X");
        }
    }

    @Test
    void retryUsesTheObservedAttemptAndCannotQueueTheSameFailureTwice()
            throws Exception {
        State state = upload(UUID.randomUUID().toString(), 1);
        Lease first = transactions.claim().orElseThrow();
        transactions.failed(first, FailureCode.UPSTREAM_TIMEOUT);
        assertThat(service.get(accountId, visitId, state.id()).retryAvailable()).isTrue();

        for (int i = 0; i < 2; i++) {
            mvc.perform(post(path() + "/" + state.id() + "/retry")
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json").content("{\"expectedAttempt\":1}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.generationStatus").value("UPLOADED"))
                    .andExpect(jsonPath("$.attemptCount").value(1));
        }
        Lease second = transactions.claim().orElseThrow();
        assertThat(transactions.claim()).isEmpty();
        transactions.failed(second, FailureCode.UPSTREAM_TIMEOUT);

        // A delayed retry of attempt 1 must not consume the failure of attempt 2.
        mvc.perform(post(path() + "/" + state.id() + "/retry")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"expectedAttempt\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.generationStatus").value("FAILED"))
                .andExpect(jsonPath("$.attemptCount").value(2));
        assertThat(transactions.claim()).isEmpty();

        mvc.perform(post(path() + "/" + state.id() + "/retry")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"expectedAttempt\":2}"))
                .andExpect(status().isAccepted());
        Lease third = transactions.claim().orElseThrow();
        transactions.failed(third, FailureCode.UPSTREAM_TIMEOUT);
        assertThat(service.get(accountId, visitId, state.id()).retryAvailable()).isFalse();
        mvc.perform(post(path() + "/" + state.id() + "/retry")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"expectedAttempt\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_VISIT_STATE"));
    }

    @Test
    void insufficientEvidenceDoesNotPublishAndCannotBeRetried()
            throws Exception {
        State state = upload(UUID.randomUUID().toString(), 1);
        Lease lease = transactions.claim().orElseThrow();
        transactions.failed(lease, FailureCode.INSUFFICIENT_EVIDENCE);
        State failed = service.get(accountId, visitId, state.id());
        assertThat(failed.generationStatus()).isEqualTo("FAILED");
        assertThat(failed.retryAvailable()).isFalse();
        assertThat(failed.startAvailable()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.quiz_sets WHERE observation_id = ?",
                Integer.class, state.id())).isZero();
        mvc.perform(post(path() + "/" + state.id() + "/retry")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"expectedAttempt\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(post(visitPath("/report")).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QUIZ_NOT_READY"));
    }

    private String visitPath(String suffix) {
        return "/api/visits/" + visitId + suffix;
    }
    @Test
    void basicSourceQuizIsPublishedAndLabeledWithoutClaimingGeminiGeneratedIt() throws Exception {
        State state = upload(UUID.randomUUID().toString(), 1);
        Lease lease = transactions.claim().orElseThrow();
        var snapshot = transactions.snapshot(lease);
        Input input = input(snapshot);
        var basic = new com.podo.server.observation.ai.PlaceFactFallbackQuiz().generate(input);
        transactions.complete(lease, snapshot, input, basic);
        assertThat(service.get(accountId, visitId, state.id()).startAvailable()).isTrue();
        var models = jdbc.queryForList("SELECT ai_model FROM app.quiz_questions q JOIN app.quiz_sets s ON s.id = q.quiz_set_id WHERE s.observation_id = ?", String.class, state.id());
        assertThat(models).hasSize(3).containsOnly("rule-based-source-reading");
        mvc.perform(post(visitPath("/report")).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        var response = mvc.perform(get(visitPath("/quiz")).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse();
        var quiz = mapper.readTree(response.getContentAsByteArray());
        assertThat(quiz.get("introduction").asText()).contains("기본 퀴즈");
        assertThat(quiz.get("questions").size()).isEqualTo(3);
    }

}
