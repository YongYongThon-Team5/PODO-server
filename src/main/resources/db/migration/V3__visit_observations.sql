-- V1/V2를 수정하지 않는다. 기존 방문·퀴즈·장소 데이터를 유지한다.
-- 생성 상태는 방문 상태와 별개다.
-- 사진 업로드/AI API는 후속 브랜치에서 구현한다.

CREATE TABLE app.observations (
                                  id uuid PRIMARY KEY,
                                  visit_id uuid NOT NULL REFERENCES app.visits(id),
                                  storage_key text NOT NULL UNIQUE,
                                  mime_type text NOT NULL,
                                  size_bytes bigint NOT NULL CHECK (size_bytes > 0),
                                  generation_status text NOT NULL DEFAULT 'UPLOADED',
                                  ai_model text,
                                  prompt_version text,
                                  failure_code text,
                                  generated_at timestamptz,
                                  created_at timestamptz NOT NULL,
                                  updated_at timestamptz NOT NULL,

                                  CONSTRAINT ck_observations_generation_status
                                      CHECK (
                                          generation_status IN (
                                                                'UPLOADED', 'GENERATING', 'READY', 'FAILED'
                                              )
                                          ),

                                  CONSTRAINT ck_observations_storage_key
                                      CHECK (length(btrim(storage_key)) > 0),

                                  CONSTRAINT ck_observations_mime_type
                                      CHECK (
                                          mime_type IN (
                                                        'image/jpeg', 'image/png', 'image/webp'
                                              )
                                          )
);

CREATE INDEX idx_observations_visit_created
    ON app.observations(visit_id, created_at DESC, id DESC);

ALTER TABLE app.quiz_sets
    ADD COLUMN observation_id uuid REFERENCES app.observations(id),
    ADD COLUMN introduction text;

-- NULL은 기존 장소별 세트.
-- 값이 있으면 해당 관찰 사진에서 생성한 세트다.
DROP INDEX app.uq_quiz_sets_place_version;
DROP INDEX app.uq_quiz_sets_one_published_per_place;

CREATE UNIQUE INDEX uq_quiz_sets_place_version
    ON app.quiz_sets(place_id, version)
    WHERE observation_id IS NULL;

CREATE UNIQUE INDEX uq_quiz_sets_one_published_per_place
    ON app.quiz_sets(place_id)
    WHERE observation_id IS NULL AND status = 'PUBLISHED';

CREATE UNIQUE INDEX uq_quiz_sets_observation_version
    ON app.quiz_sets(observation_id, version)
    WHERE observation_id IS NOT NULL;

CREATE UNIQUE INDEX uq_quiz_sets_one_published_per_observation
    ON app.quiz_sets(observation_id)
    WHERE observation_id IS NOT NULL AND status = 'PUBLISHED';

-- 역사·이야기 퀴즈는 기존 source_id NOT NULL을 유지한다.
-- 사진 자체의 관찰 근거는 보조 설명으로 보관할 수 있다.
ALTER TABLE app.quiz_questions
    ADD COLUMN photo_evidence_text text;