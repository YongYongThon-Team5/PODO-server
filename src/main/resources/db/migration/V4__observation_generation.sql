-- 기존 V1~V3와 장소/방문/답안/힌트 데이터를 유지한다.
ALTER TABLE app.observations
    ADD COLUMN request_key varchar(128),
    ADD COLUMN request_hash text,
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN lease_token uuid,
    ADD COLUMN lease_expires_at timestamptz;

ALTER TABLE app.observations
    ADD CONSTRAINT ck_observations_request CHECK (
        (request_key IS NULL AND request_hash IS NULL)
            OR (
            request_key IS NOT NULL
                AND request_hash IS NOT NULL
                AND char_length(request_key) BETWEEN 8 AND 128
                AND request_hash ~ '^[0-9a-f]{64}$'
            )
        ),
    ADD CONSTRAINT ck_observations_attempt_count
        CHECK (attempt_count BETWEEN 0 AND 3);

CREATE UNIQUE INDEX uq_observations_visit_request
    ON app.observations(visit_id, request_key)
    WHERE request_key IS NOT NULL;

CREATE INDEX idx_observations_pending
    ON app.observations(created_at, id)
    WHERE generation_status = 'UPLOADED'
      AND request_key IS NOT NULL;

CREATE INDEX idx_observations_expired_lease
    ON app.observations(lease_expires_at)
    WHERE generation_status = 'GENERATING';

CREATE TABLE app.observation_images (
                                        id uuid PRIMARY KEY,
                                        observation_id uuid NOT NULL REFERENCES app.observations(id),
                                        position smallint NOT NULL CHECK (position BETWEEN 1 AND 3),
                                        storage_key text NOT NULL UNIQUE
                                            CHECK (length(btrim(storage_key)) > 0),
                                        mime_type text NOT NULL
                                            CHECK (mime_type IN ('image/jpeg', 'image/png', 'image/webp')),
                                        size_bytes bigint NOT NULL CHECK (size_bytes > 0),
                                        created_at timestamptz NOT NULL,

                                        CONSTRAINT uq_observation_images_position
                                            UNIQUE (observation_id, position)
);

-- V3의 단일 사진을 새 사진 목록의 첫 사진으로 기록한다.
INSERT INTO app.observation_images (
    id,
    observation_id,
    position,
    storage_key,
    mime_type,
    size_bytes,
    created_at
)
SELECT
    id,
    id,
    1,
    storage_key,
    mime_type,
    size_bytes,
    created_at
FROM app.observations;

-- observations의 기존 파일 컬럼은 V3 호환을 위해
-- 첫 사진의 정보로 유지한다.
-- 새 기능의 사진 목록/조회/생성 입력은 observation_images를 기준으로 한다.
ALTER TABLE app.quiz_questions
    ADD COLUMN observation_image_id uuid
        REFERENCES app.observation_images(id),
    ADD COLUMN source_quote text;

CREATE INDEX idx_quiz_questions_observation_image
    ON app.quiz_questions(observation_image_id)
    WHERE observation_image_id IS NOT NULL;

-- source_id NOT NULL 및 V3의 퀴즈 범위별 부분 UNIQUE는 유지한다.
ALTER TABLE app.quiz_sets
    ADD COLUMN review_method text NOT NULL DEFAULT 'MANUAL',
    ADD CONSTRAINT ck_quiz_sets_review_method
        CHECK (review_method IN ('MANUAL', 'AUTOMATED')),
    ADD CONSTRAINT ck_quiz_sets_automated_scope
        CHECK (
            review_method <> 'AUTOMATED'
            OR observation_id IS NOT NULL
        );