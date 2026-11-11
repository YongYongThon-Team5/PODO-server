package com.podo.server.observation.repository;

import com.podo.server.observation.ai.ObservationAi;
import com.podo.server.observation.domain.ObservationData.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ObservationJobRepository {

    private final JdbcClient jdbc;

    public Optional<Row> find(UUID id, boolean lock) {
        return jdbc.sql(
                        "SELECT * FROM app.observations WHERE id = :id"
                                + (lock ? " FOR UPDATE" : "")
                )
                .param("id", id)
                .query(this::row)
                .optional();
    }

    public Optional<Row> byKey(UUID visitId, String key) {
        return jdbc.sql("""
                        SELECT *
                        FROM app.observations
                        WHERE visit_id = :visit
                          AND request_key = :key
                        """)
                .param("visit", visitId)
                .param("key", key)
                .query(this::row)
                .optional();
    }

    public Optional<Row> latest(UUID visitId) {
        return jdbc.sql("""
                        SELECT *
                        FROM app.observations
                        WHERE visit_id = :id
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """)
                .param("id", visitId)
                .query(this::row)
                .optional();
    }

    public long count(UUID visitId) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM app.observations
                        WHERE visit_id = :id
                        """)
                .param("id", visitId)
                .query(Long.class)
                .single();
    }

    public List<Image> images(UUID observationId) {
        return jdbc.sql("""
                        SELECT *
                        FROM app.observation_images
                        WHERE observation_id = :id
                        ORDER BY position
                        """)
                .param("id", observationId)
                .query((rs, n) -> new Image(
                        rs.getObject("id", UUID.class),
                        rs.getInt("position"),
                        rs.getString("storage_key"),
                        rs.getString("mime_type"),
                        rs.getLong("size_bytes")
                ))
                .list();
    }

    public List<ObservationAi.Source> sources(UUID placeId) {
        return jdbc.sql("""
                        SELECT id, fact_text
                        FROM app.place_sources
                        WHERE place_id = :id
                          AND status = 'ACTIVE'
                          AND checked_at IS NOT NULL
                          AND length(btrim(fact_text)) BETWEEN 8 AND 6000
                        ORDER BY id
                        LIMIT 12
                        """)
                .param("id", placeId)
                .query((rs, n) -> new ObservationAi.Source(
                        rs.getObject("id", UUID.class),
                        rs.getString("fact_text")
                ))
                .list();
    }

    public void lockSources(List<UUID> ids) {
        jdbc.sql("""
                        SELECT id
                        FROM app.place_sources
                        WHERE id IN (:ids)
                        ORDER BY id
                        FOR SHARE
                        """)
                .param("ids", ids)
                .query(UUID.class)
                .list();
    }

    public void insert(
            UUID id,
            UUID visit,
            String key,
            String hash,
            Image first,
            Instant now
    ) {
        jdbc.sql("""
                        INSERT INTO app.observations (
                            id,
                            visit_id,
                            storage_key,
                            mime_type,
                            size_bytes,
                            generation_status,
                            request_key,
                            request_hash,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            :id,
                            :visit,
                            :storage,
                            :mime,
                            :size,
                            'UPLOADED',
                            :key,
                            :hash,
                            :now,
                            :now
                        )
                        """)
                .param("id", id)
                .param("visit", visit)
                .param("storage", first.storageKey())
                .param("mime", first.mimeType())
                .param("size", first.sizeBytes())
                .param("key", key)
                .param("hash", hash)
                .param("now", utc(now))
                .update();
    }

    public void insertImage(
            UUID observationId,
            Image image,
            Instant now
    ) {
        jdbc.sql("""
                        INSERT INTO app.observation_images (
                            id,
                            observation_id,
                            position,
                            storage_key,
                            mime_type,
                            size_bytes,
                            created_at
                        )
                        VALUES (
                            :id,
                            :observation,
                            :position,
                            :key,
                            :mime,
                            :size,
                            :now
                        )
                        """)
                .param("id", image.id())
                .param("observation", observationId)
                .param("position", image.position())
                .param("key", image.storageKey())
                .param("mime", image.mimeType())
                .param("size", image.sizeBytes())
                .param("now", utc(now))
                .update();
    }

    public Optional<Lease> claim(
            Instant now,
            Instant expires,
            String model,
            String version,
            int maxAttempts
    ) {
        UUID token = UUID.randomUUID();

        return jdbc.sql("""
                        WITH next_job AS (
                            SELECT o.id
                            FROM app.observations o
                            WHERE o.generation_status = 'UPLOADED'
                              AND o.request_key IS NOT NULL
                              AND o.attempt_count < :maxAttempts
                            ORDER BY o.created_at, o.id
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE app.observations o
                        SET generation_status = 'GENERATING',
                            attempt_count = attempt_count + 1,
                            lease_token = :token,
                            lease_expires_at = :expires,
                            ai_model = :model,
                            prompt_version = :version,
                            failure_code = NULL,
                            updated_at = :now
                        FROM next_job
                        WHERE o.id = next_job.id
                        RETURNING o.id
                        """)
                .param("maxAttempts", maxAttempts)
                .param("token", token)
                .param("expires", utc(expires))
                .param("model", model)
                .param("version", version)
                .param("now", utc(now))
                .query(UUID.class)
                .optional()
                .map(id -> new Lease(id, token));
    }

    public void expire(Instant now) {
        jdbc.sql("""
                        UPDATE app.observations
                        SET generation_status = 'FAILED',
                            failure_code = 'WORKER_INTERRUPTED',
                            lease_token = NULL,
                            lease_expires_at = NULL,
                            updated_at = :now
                        WHERE generation_status = 'GENERATING'
                          AND lease_expires_at <= :now
                        """)
                .param("now", utc(now))
                .update();
    }

    public void fail(
            Lease lease,
            String code,
            Instant now
    ) {
        jdbc.sql("""
                        UPDATE app.observations
                        SET generation_status = 'FAILED',
                            failure_code = :code,
                            lease_token = NULL,
                            lease_expires_at = NULL,
                            updated_at = :now
                        WHERE id = :id
                          AND generation_status = 'GENERATING'
                          AND lease_token = :token
                        """)
                .param("code", code)
                .param("now", utc(now))
                .param("id", lease.observationId())
                .param("token", lease.token())
                .update();
    }

    public void retry(UUID id, Instant now) {
        jdbc.sql("""
                        UPDATE app.observations
                        SET generation_status = 'UPLOADED',
                            failure_code = NULL,
                            lease_token = NULL,
                            lease_expires_at = NULL,
                            updated_at = :now
                        WHERE id = :id
                        """)
                .param("now", utc(now))
                .param("id", id)
                .update();
    }

    public void ready(UUID id, Instant now) {
        jdbc.sql("""
                        UPDATE app.observations
                        SET generation_status = 'READY',
                            failure_code = NULL,
                            lease_token = NULL,
                            lease_expires_at = NULL,
                            generated_at = :now,
                            updated_at = :now
                        WHERE id = :id
                        """)
                .param("now", utc(now))
                .param("id", id)
                .update();
    }

    public record QuizInfo(
            String introduction,
            boolean published
    ) {
    }

    public QuizInfo quizInfo(UUID observationId) {
        return jdbc.sql("""
                        SELECT introduction, status
                        FROM app.quiz_sets
                        WHERE observation_id = :id
                        ORDER BY
                            CASE
                                WHEN status = 'PUBLISHED' THEN 0
                                ELSE 1
                            END,
                            version DESC
                        LIMIT 1
                        """)
                .param("id", observationId)
                .query((rs, n) -> new QuizInfo(
                        rs.getString("introduction"),
                        "PUBLISHED".equals(rs.getString("status"))
                ))
                .optional()
                .orElse(new QuizInfo(null, false));
    }

    public void insertQuiz(
            UUID setId,
            UUID placeId,
            UUID observationId,
            ObservationAi.Output output,
            List<Image> images,
            boolean publish,
            Instant now
    ) {
        jdbc.sql("""
                        INSERT INTO app.quiz_sets (
                            id,
                            place_id,
                            observation_id,
                            introduction,
                            version,
                            status,
                            reviewed_by,
                            reviewed_at,
                            published_at,
                            review_method
                        )
                        VALUES (
                            :id,
                            :place,
                            :observation,
                            :intro,
                            1,
                            :status,
                            :reviewer,
                            :reviewed,
                            :published,
                            :method
                        )
                        """)
                .param("id", setId)
                .param("place", placeId)
                .param("observation", observationId)
                .param("intro", output.introduction())
                .param("status", publish ? "PUBLISHED" : "DRAFT")
                .param(
                        "reviewer",
                        publish ? "SYSTEM:SOURCE_AND_SCHEMA_V1" : null,
                        java.sql.Types.VARCHAR
                )
                .param(
                        "reviewed",
                        publish ? utc(now) : null,
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE
                )
                .param(
                        "published",
                        publish ? utc(now) : null,
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE
                )
                .param(
                        "method",
                        publish ? "AUTOMATED" : "MANUAL"
                )
                .update();

        int position = 0;

        for (var question : output.questions()) {
            Image image = images.get(
                    question.imagePosition() - 1
            );

            jdbc.sql("""
                            INSERT INTO app.quiz_questions (
                                id,
                                quiz_set_id,
                                position,
                                question_text,
                                correct_answer,
                                explanation,
                                source_id,
                                source_quote,
                                observation_image_id,
                                photo_evidence_text,
                                hint_1_text,
                                hint_2_text,
                                observation_guide,
                                reference_image_url,
                                ai_model,
                                prompt_version,
                                generated_at
                            )
                            SELECT
                                :id,
                                :set,
                                :position,
                                :text,
                                :answer,
                                :explanation,
                                :source,
                                :quote,
                                :image,
                                :evidence,
                                :hint1,
                                :hint2,
                                :guide,
                                '/api/visits/' || o.visit_id::text
                                    || '/observations/' || o.id::text
                                    || '/images/' || CAST(:image AS text),
                                o.ai_model,
                                o.prompt_version,
                                :now
                            FROM app.observations o
                            WHERE o.id = :observation
                            """)
                    .param("id", UUID.randomUUID())
                    .param("set", setId)
                    .param("position", ++position)
                    .param("text", question.text())
                    .param("answer", question.answer())
                    .param("explanation", question.explanation())
                    .param("source", question.sourceId())
                    .param("quote", question.sourceQuote())
                    .param("image", image.id())
                    .param("evidence", question.photoEvidenceText())
                    .param("hint1", question.hint1())
                    .param("hint2", question.hint2())
                    .param("guide", question.observationGuide())
                    .param("now", utc(now))
                    .param("observation", observationId)
                    .update();
        }
    }

    public boolean referencesFile(String key) {
        return jdbc.sql("""
                        SELECT
                            EXISTS (
                                SELECT 1
                                FROM app.observation_images
                                WHERE storage_key = :key
                            )
                            OR EXISTS (
                                SELECT 1
                                FROM app.observations
                                WHERE storage_key = :key
                            )
                        """)
                .param("key", key)
                .query(Boolean.class)
                .single();
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        var expires = rs.getTimestamp("lease_expires_at");

        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("visit_id", UUID.class),
                rs.getString("generation_status"),
                rs.getInt("attempt_count"),
                rs.getString("failure_code"),
                rs.getString("request_hash"),
                rs.getObject("lease_token", UUID.class),
                expires == null ? null : expires.toInstant(),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}