-- Initial service schema. Flyway creates and manages the app schema.
CREATE TABLE "app"."accounts" (
  "id" uuid PRIMARY KEY NOT NULL,
  "kakao_subject" text UNIQUE NOT NULL,
  "status" text NOT NULL,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL
);

CREATE TABLE "app"."oauth_login_requests" (
  "state_hash" text PRIMARY KEY NOT NULL,
  "browser_nonce_hash" text NOT NULL,
  "account_id" uuid,
  "expires_at" timestamptz NOT NULL,
  "consumed_at" timestamptz
);

CREATE TABLE "app"."auth_sessions" (
  "id" uuid PRIMARY KEY NOT NULL,
  "account_id" uuid NOT NULL,
  "created_at" timestamptz NOT NULL,
  "expires_at" timestamptz NOT NULL,
  "revoked_at" timestamptz
);

CREATE TABLE "app"."auth_tokens" (
  "token_hash" text PRIMARY KEY NOT NULL,
  "session_id" uuid NOT NULL,
  "kind" text NOT NULL,
  "expires_at" timestamptz NOT NULL,
  "consumed_at" timestamptz,
  "revoked_at" timestamptz,
  CONSTRAINT "ck_auth_tokens_kind" CHECK (kind IN ('ACCESS', 'REFRESH'))
);

CREATE TABLE "app"."child_profiles" (
  "id" uuid PRIMARY KEY NOT NULL,
  "account_id" uuid NOT NULL,
  "nickname" varchar(30) NOT NULL,
  "age_band_code" varchar(64),
  "color_code" varchar(64) NOT NULL,
  "profile_image_id" uuid,
  "interest_category_ids" uuid[] NOT NULL DEFAULT ('{}'::uuid[]),
  "book_genre_ids" uuid[] NOT NULL DEFAULT ('{}'::uuid[]),
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL,
  CONSTRAINT "ck_child_profiles_nickname_length" CHECK (char_length(nickname) BETWEEN 1 AND 30)
);

CREATE TABLE "app"."profile_images" (
  "id" uuid PRIMARY KEY NOT NULL,
  "account_id" uuid NOT NULL,
  "storage_key" text NOT NULL,
  "mime_type" text NOT NULL,
  "size_bytes" bigint NOT NULL,
  "orphaned_at" timestamptz,
  "created_at" timestamptz NOT NULL
);

CREATE TABLE "app"."tags" (
  "id" uuid PRIMARY KEY NOT NULL,
  "kind" text NOT NULL,
  "code" varchar(64) NOT NULL,
  "label" text NOT NULL,
  "active" boolean NOT NULL,
  CONSTRAINT "ck_tags_kind" CHECK (kind IN ('INTEREST', 'BOOK_GENRE', 'PURPOSE'))
);

CREATE TABLE "app"."places" (
  "id" uuid PRIMARY KEY NOT NULL,
  "name" text NOT NULL,
  "address" text,
  "latitude" numeric,
  "longitude" numeric,
  "district_code" varchar(64),
  "description" text,
  "main_activities" text[],
  "environment" text NOT NULL,
  "indoor_only_available" boolean,
  "opening_hours" text,
  "fee_info" text,
  "target_age_info" text,
  "amenities" text[],
  "cautions" text[],
  "interest_category_ids" uuid[] NOT NULL DEFAULT ('{}'::uuid[]),
  "book_genre_ids" uuid[] NOT NULL DEFAULT ('{}'::uuid[]),
  "purpose_ids" uuid[] NOT NULL DEFAULT ('{}'::uuid[]),
  "images" jsonb NOT NULL DEFAULT ('[]'::jsonb),
  "map_place_url" text,
  "map_directions_url" text,
  "status" text NOT NULL,
  "updated_at" timestamptz NOT NULL,
  CONSTRAINT "ck_places_environment" CHECK (environment IN ('INDOOR', 'OUTDOOR', 'MIXED', 'UNKNOWN')),
  CONSTRAINT "ck_places_status" CHECK (status IN ('DRAFT', 'PUBLISHED', 'HIDDEN')),
  CONSTRAINT "ck_places_latitude" CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
  CONSTRAINT "ck_places_longitude" CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
  CONSTRAINT "ck_places_published_fields" CHECK (status <> 'PUBLISHED' OR (address IS NOT NULL AND latitude IS NOT NULL AND longitude IS NOT NULL AND main_activities IS NOT NULL)),
  CONSTRAINT "ck_places_images_array" CHECK (jsonb_typeof(images) = 'array')
);

CREATE TABLE "app"."place_sources" (
  "id" uuid PRIMARY KEY NOT NULL,
  "place_id" uuid NOT NULL,
  "source_type" text NOT NULL,
  "source_url" text,
  "fact_text" text NOT NULL,
  "checked_at" timestamptz,
  "status" text NOT NULL,
  CONSTRAINT "ck_place_sources_source_type" CHECK (source_type IN ('OFFICIAL', 'PUBLIC_DATA', 'FIELD_NOTE'))
);

CREATE TABLE "app"."visits" (
  "id" uuid PRIMARY KEY NOT NULL,
  "account_id" uuid NOT NULL,
  "place_id" uuid NOT NULL,
  "child_profile_id" uuid,
  "planned_date" date NOT NULL,
  "source" text NOT NULL,
  "status" text NOT NULL,
  "quiz_set_id" uuid,
  "create_request_key" varchar(128) NOT NULL,
  "create_request_hash" text NOT NULL,
  "reported_at" timestamptz,
  "completed_at" timestamptz,
  "cancelled_at" timestamptz,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL,
  CONSTRAINT "ck_visits_source" CHECK (source IN ('RECOMMENDATION', 'POPULAR', 'MAP')),
  CONSTRAINT "ck_visits_status" CHECK (status IN ('PLANNED', 'QUIZ_IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
  CONSTRAINT "ck_visits_request_key_length" CHECK (char_length(create_request_key) BETWEEN 8 AND 128),
  CONSTRAINT "ck_visits_status_fields" CHECK ((status = 'PLANNED' AND quiz_set_id IS NULL AND reported_at IS NULL AND completed_at IS NULL AND cancelled_at IS NULL) OR (status = 'QUIZ_IN_PROGRESS' AND quiz_set_id IS NOT NULL AND reported_at IS NOT NULL AND completed_at IS NULL AND cancelled_at IS NULL) OR (status = 'COMPLETED' AND quiz_set_id IS NOT NULL AND reported_at IS NOT NULL AND completed_at IS NOT NULL AND cancelled_at IS NULL) OR (status = 'CANCELLED' AND quiz_set_id IS NULL AND reported_at IS NULL AND completed_at IS NULL AND cancelled_at IS NOT NULL))
);

CREATE TABLE "app"."quiz_sets" (
  "id" uuid PRIMARY KEY NOT NULL,
  "place_id" uuid NOT NULL,
  "version" integer NOT NULL,
  "status" text NOT NULL,
  "reviewed_by" text,
  "reviewed_at" timestamptz,
  "published_at" timestamptz,
  "invalidated_at" timestamptz,
  CONSTRAINT "ck_quiz_sets_version" CHECK (version >= 1),
  CONSTRAINT "ck_quiz_sets_status" CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED', 'INVALIDATED'))
);

CREATE TABLE "app"."quiz_questions" (
  "id" uuid PRIMARY KEY NOT NULL,
  "quiz_set_id" uuid NOT NULL,
  "position" smallint NOT NULL,
  "question_text" text NOT NULL,
  "correct_answer" text NOT NULL,
  "explanation" text NOT NULL,
  "source_id" uuid NOT NULL,
  "hint_1_text" text,
  "hint_2_text" text,
  "observation_guide" text,
  "reference_image_url" text,
  "ai_model" text,
  "prompt_version" text,
  "generated_at" timestamptz,
  CONSTRAINT "ck_quiz_questions_position" CHECK (position BETWEEN 1 AND 3),
  CONSTRAINT "ck_quiz_questions_correct_answer" CHECK (correct_answer IN ('O', 'X'))
);

CREATE TABLE "app"."quiz_answers" (
  "visit_id" uuid NOT NULL,
  "question_id" uuid NOT NULL,
  "selected_answer" text NOT NULL,
  "is_correct" boolean NOT NULL,
  "answered_at" timestamptz NOT NULL,
  CONSTRAINT "ck_quiz_answers_selected_answer" CHECK (selected_answer IN ('O', 'X')),
  PRIMARY KEY ("visit_id", "question_id")
);

CREATE TABLE "app"."quiz_hint_views" (
  "visit_id" uuid NOT NULL,
  "question_id" uuid NOT NULL,
  "step" smallint NOT NULL,
  "first_viewed_at" timestamptz NOT NULL,
  CONSTRAINT "ck_quiz_hint_views_step" CHECK (step IN (1, 2)),
  PRIMARY KEY ("visit_id", "question_id", "step")
);

CREATE UNIQUE INDEX "uq_child_profiles_account_color" ON "app"."child_profiles" ("account_id", "color_code");

CREATE UNIQUE INDEX "uq_tags_kind_code" ON "app"."tags" ("kind", "code");

CREATE UNIQUE INDEX "uq_visits_account_request_key" ON "app"."visits" ("account_id", "create_request_key");

CREATE INDEX "idx_visits_account_created_id" ON "app"."visits" ("account_id", "created_at", "id");

CREATE UNIQUE INDEX "uq_quiz_sets_place_version" ON "app"."quiz_sets" ("place_id", "version");

CREATE UNIQUE INDEX "uq_quiz_questions_set_position" ON "app"."quiz_questions" ("quiz_set_id", "position");

-- Only one planned or in-progress visit may exist for an account.
CREATE UNIQUE INDEX "uq_visits_one_active_per_account"
  ON "app"."visits" ("account_id")
  WHERE "status" IN ('PLANNED', 'QUIZ_IN_PROGRESS');

-- Historical quiz sets remain available alongside the current published set.
CREATE UNIQUE INDEX "uq_quiz_sets_one_published_per_place"
  ON "app"."quiz_sets" ("place_id")
  WHERE "status" = 'PUBLISHED';

ALTER TABLE "app"."oauth_login_requests" ADD CONSTRAINT "fk_oauth_login_requests_account_id" FOREIGN KEY ("account_id") REFERENCES "app"."accounts" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."auth_sessions" ADD CONSTRAINT "fk_auth_sessions_account_id" FOREIGN KEY ("account_id") REFERENCES "app"."accounts" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."auth_tokens" ADD CONSTRAINT "fk_auth_tokens_session_id" FOREIGN KEY ("session_id") REFERENCES "app"."auth_sessions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."child_profiles" ADD CONSTRAINT "fk_child_profiles_account_id" FOREIGN KEY ("account_id") REFERENCES "app"."accounts" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."child_profiles" ADD CONSTRAINT "fk_child_profiles_profile_image_id" FOREIGN KEY ("profile_image_id") REFERENCES "app"."profile_images" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."profile_images" ADD CONSTRAINT "fk_profile_images_account_id" FOREIGN KEY ("account_id") REFERENCES "app"."accounts" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."place_sources" ADD CONSTRAINT "fk_place_sources_place_id" FOREIGN KEY ("place_id") REFERENCES "app"."places" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."visits" ADD CONSTRAINT "fk_visits_account_id" FOREIGN KEY ("account_id") REFERENCES "app"."accounts" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."visits" ADD CONSTRAINT "fk_visits_place_id" FOREIGN KEY ("place_id") REFERENCES "app"."places" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."visits" ADD CONSTRAINT "fk_visits_child_profile_id" FOREIGN KEY ("child_profile_id") REFERENCES "app"."child_profiles" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."visits" ADD CONSTRAINT "fk_visits_quiz_set_id" FOREIGN KEY ("quiz_set_id") REFERENCES "app"."quiz_sets" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."quiz_sets" ADD CONSTRAINT "fk_quiz_sets_place_id" FOREIGN KEY ("place_id") REFERENCES "app"."places" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."quiz_questions" ADD CONSTRAINT "fk_quiz_questions_quiz_set_id" FOREIGN KEY ("quiz_set_id") REFERENCES "app"."quiz_sets" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."quiz_questions" ADD CONSTRAINT "fk_quiz_questions_source_id" FOREIGN KEY ("source_id") REFERENCES "app"."place_sources" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."quiz_answers" ADD CONSTRAINT "fk_quiz_answers_visit_id" FOREIGN KEY ("visit_id") REFERENCES "app"."visits" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."quiz_answers" ADD CONSTRAINT "fk_quiz_answers_question_id" FOREIGN KEY ("question_id") REFERENCES "app"."quiz_questions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."quiz_hint_views" ADD CONSTRAINT "fk_quiz_hint_views_visit_id" FOREIGN KEY ("visit_id") REFERENCES "app"."visits" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "app"."quiz_hint_views" ADD CONSTRAINT "fk_quiz_hint_views_question_id" FOREIGN KEY ("question_id") REFERENCES "app"."quiz_questions" ("id") DEFERRABLE INITIALLY IMMEDIATE;
