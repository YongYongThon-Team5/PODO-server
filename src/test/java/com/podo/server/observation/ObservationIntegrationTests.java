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
        "podo.observation.auto-publish=false",
        "podo.observation.worker-enabled=false"
})
class ObservationIntegrationTests extends ObservationIntegrationTestSupport {

    @Test
    void threePhotosFormOneIdempotentObservationAndChangedPayloadIsRejected()
            throws Exception {
        String key = UUID.randomUUID().toString();

        State first = upload(key, 3);

        assertThat(first.images()).hasSize(3);

        assertThat(upload(key, 3).id())
                .isEqualTo(first.id());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.observations",
                Integer.class
        )).isEqualTo(1);

        mvc.perform(
                        multipart(path())
                                .file(photo())
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                                .header("Idempotency-Key", key)
                )
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("IDEMPOTENCY_KEY_REUSED")
                );
    }

    @Test
    void anotherAccountCannotReadTheBatchOrItsPhotos()
            throws Exception {
        State state = upload(
                UUID.randomUUID().toString(),
                1
        );

        var login = sessions.issueForKakaoSubject(
                "OTHER_" + UUID.randomUUID()
        );

        String other = sessions.rotateRefresh(
                login.refreshToken()
        ).accessToken();

        mvc.perform(
                get(path() + "/" + state.id())
                        .header(
                                "Authorization",
                                "Bearer " + other
                        )
        ).andExpect(status().isNotFound());

        mvc.perform(
                get(state.images().getFirst().contentUrl())
                        .header(
                                "Authorization",
                                "Bearer " + other
                        )
        ).andExpect(status().isNotFound());
    }

    @Test
    void generationIsStoredAsDraftThenExistingQuizApisWorkAfterReview()
            throws Exception {
        State state = upload(
                UUID.randomUUID().toString(),
                3
        );

        Lease lease = transactions.claim().orElseThrow();

        var snapshot = transactions.snapshot(lease);
        Input input = input(snapshot);

        transactions.complete(
                lease,
                snapshot,
                input,
                output(input)
        );

        State generated = service.get(
                accountId,
                visitId,
                state.id()
        );

        assertThat(generated.generationStatus())
                .isEqualTo("READY");

        assertThat(generated.startAvailable()).isFalse();

        mvc.perform(
                post("/api/visits/" + visitId + "/report")
                        .header(
                                "Authorization",
                                "Bearer " + token
                        )
        ).andExpect(status().isConflict());

        jdbc.update("""
                UPDATE app.quiz_sets
                SET status = 'PUBLISHED',
                    reviewed_by = 'test-reviewer',
                    reviewed_at = now(),
                    published_at = now()
                WHERE observation_id = ?
                """, state.id());

        mvc.perform(
                post("/api/visits/" + visitId + "/report")
                        .header(
                                "Authorization",
                                "Bearer " + token
                        )
        ).andExpect(status().isOk());

        var response = mvc.perform(
                        get("/api/visits/" + visitId + "/quiz")
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        var progress = mapper.readTree(
                response.getContentAsByteArray()
        );

        assertThat(progress.get("questions").size())
                .isEqualTo(3);

        for (var question : progress.get("questions")) {
            assertThat(question.get("feedback").isNull())
                    .isTrue();

            assertThat(question.has("correctAnswer"))
                    .isFalse();
        }

        for (var question : progress.get("questions")) {
            String questionId = question.get("questionId")
                    .asText();

            mvc.perform(
                    put(
                            "/api/visits/" + visitId
                                    + "/answers/" + questionId
                    )
                            .header(
                                    "Authorization",
                                    "Bearer " + token
                            )
                            .contentType("application/json")
                            .content("{\"answer\":\"O\"}")
            ).andExpect(status().isOk());
        }

        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.visits WHERE id = ?",
                String.class,
                visitId
        )).isEqualTo("COMPLETED");
    }

    @Test
    void expiredWorkerCannotPublishOverANewerAttempt()
            throws Exception {
        State state = upload(
                UUID.randomUUID().toString(),
                1
        );

        Lease old = transactions.claim().orElseThrow();

        var snapshot = transactions.snapshot(old);
        Input input = input(snapshot);

        jdbc.update("""
                UPDATE app.observations
                SET lease_expires_at = now() - interval '1 second'
                WHERE id = ?
                """, state.id());

        assertThat(transactions.claim()).isEmpty();

        service.retry(
                accountId,
                visitId,
                state.id(),
                1
        );

        Lease current = transactions.claim().orElseThrow();

        assertThatThrownBy(
                () -> transactions.complete(
                        old,
                        snapshot,
                        input,
                        output(input)
                )
        ).isInstanceOf(GenerationException.class);

        transactions.failed(
                old,
                FailureCode.WORKER_INTERRUPTED
        );

        assertThat(
                service.retry(
                        accountId,
                        visitId,
                        state.id(),
                        1
                ).generationStatus()
        ).isEqualTo("GENERATING");

        transactions.complete(
                current,
                transactions.snapshot(current),
                input,
                output(input)
        );

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM app.quiz_sets
                WHERE observation_id = ?
                """,
                Integer.class,
                state.id()
        )).isEqualTo(1);
    }

}
