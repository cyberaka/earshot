-- Earshot schema (PRD §9.2).
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE import (
    id              BIGSERIAL PRIMARY KEY,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    time_zone       TEXT NOT NULL,
    clock_offset_s  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE recording (
    id                BIGSERIAL PRIMARY KEY,
    import_id         BIGINT REFERENCES import(id) ON DELETE SET NULL,
    source_type       TEXT NOT NULL CHECK (source_type IN ('recorder', 'meeting')),
    original_name     TEXT,
    path              TEXT,
    work_path         TEXT,
    sha256            TEXT UNIQUE,
    started_at        TIMESTAMPTZ NOT NULL,
    time_zone         TEXT NOT NULL,
    started_at_source TEXT NOT NULL,
    file_modified_at  TIMESTAMPTZ,
    clock_offset_s    INTEGER NOT NULL DEFAULT 0,
    duration_s        DOUBLE PRECISION,
    language          TEXT NOT NULL DEFAULT 'en',
    speech_regions    JSONB,
    status            TEXT NOT NULL DEFAULT 'imported',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE meeting (
    id              BIGSERIAL PRIMARY KEY,
    recording_id    BIGINT NOT NULL REFERENCES recording(id) ON DELETE CASCADE,
    title           TEXT,
    pre_context     TEXT,
    audio_mode      TEXT NOT NULL CHECK (audio_mode IN ('mic', 'mic_system', 'system')),
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    rolling_summary JSONB,
    summary_at      TIMESTAMPTZ,
    summary_segments INTEGER NOT NULL DEFAULT 0,
    capture_files   JSONB,
    status          TEXT NOT NULL DEFAULT 'live'
);

CREATE TABLE conversation (
    id                      BIGSERIAL PRIMARY KEY,
    recording_id            BIGINT NOT NULL REFERENCES recording(id) ON DELETE CASCADE,
    meeting_id              BIGINT REFERENCES meeting(id) ON DELETE CASCADE,
    start_offset_s          DOUBLE PRECISION NOT NULL,
    end_offset_s            DOUBLE PRECISION NOT NULL,
    started_at              TIMESTAMPTZ NOT NULL,
    local_date              DATE NOT NULL,
    title                   TEXT,
    summary                 JSONB,
    topics                  JSONB,
    is_ambient              BOOLEAN NOT NULL DEFAULT false,
    duplicate_of_meeting_id BIGINT REFERENCES meeting(id) ON DELETE SET NULL,
    -- Diarization turns kept between pipeline stages, so a resumed job need not re-diarize.
    turns_json              JSONB,
    stage                   TEXT NOT NULL DEFAULT 'segmented',
    status                  TEXT NOT NULL DEFAULT 'queued',
    error                   TEXT
);
CREATE INDEX conversation_day ON conversation (local_date, started_at);

CREATE TABLE person (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    aliases     TEXT[] NOT NULL DEFAULT '{}',
    is_me       BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX person_one_me ON person (is_me) WHERE is_me;

CREATE TABLE speaker_cluster (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    label           TEXT NOT NULL,
    channel         TEXT NOT NULL DEFAULT 'mixed',
    embedding       vector,
    speech_s        DOUBLE PRECISION NOT NULL DEFAULT 0,
    person_id       BIGINT REFERENCES person(id) ON DELETE SET NULL,
    match_score     DOUBLE PRECISION,
    manual          BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE voiceprint (
    id          BIGSERIAL PRIMARY KEY,
    person_id   BIGINT NOT NULL REFERENCES person(id) ON DELETE CASCADE,
    embedding   vector NOT NULL,
    source      TEXT NOT NULL CHECK (source IN ('recorder', 'laptop_mic', 'upload', 'call')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE segment (
    id                 BIGSERIAL PRIMARY KEY,
    conversation_id    BIGINT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    speaker_cluster_id BIGINT REFERENCES speaker_cluster(id) ON DELETE SET NULL,
    channel            TEXT NOT NULL DEFAULT 'mixed' CHECK (channel IN ('mixed', 'mic', 'system')),
    live               BOOLEAN NOT NULL DEFAULT false,
    start_s            DOUBLE PRECISION NOT NULL,
    end_s              DOUBLE PRECISION NOT NULL,
    text               TEXT NOT NULL,
    words_json         JSONB,
    confidence         DOUBLE PRECISION,
    tsv                tsvector GENERATED ALWAYS AS (to_tsvector('simple', text)) STORED
);
CREATE INDEX segment_conversation ON segment (conversation_id, start_s);
CREATE INDEX segment_tsv ON segment USING gin (tsv);
CREATE INDEX segment_trgm ON segment USING gin (text gin_trgm_ops);

-- Untyped so the embedding model can change; one partial HNSW index per model (PRD §9.2).
CREATE TABLE segment_vec (
    segment_id  BIGINT NOT NULL REFERENCES segment(id) ON DELETE CASCADE,
    model       TEXT NOT NULL,
    embedding   vector NOT NULL,
    PRIMARY KEY (segment_id, model)
);
CREATE INDEX segment_vec_nomic ON segment_vec USING hnsw ((embedding::vector(768)) vector_cosine_ops)
    WHERE model = 'text-embedding-nomic-embed-text-v1.5';

CREATE TABLE action_item (
    id               BIGSERIAL PRIMARY KEY,
    conversation_id  BIGINT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    text             TEXT NOT NULL,
    owner_cluster_id BIGINT REFERENCES speaker_cluster(id) ON DELETE SET NULL,
    done             BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE qa (
    id             BIGSERIAL PRIMARY KEY,
    scope_type     TEXT NOT NULL CHECK (scope_type IN ('meeting', 'day', 'range')),
    scope_ref      TEXT NOT NULL,
    question       TEXT NOT NULL,
    answer         TEXT,
    citations_json JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE job (
    id              BIGSERIAL PRIMARY KEY,
    type            TEXT NOT NULL,
    recording_id    BIGINT REFERENCES recording(id) ON DELETE CASCADE,
    conversation_id BIGINT REFERENCES conversation(id) ON DELETE CASCADE,
    meeting_id      BIGINT REFERENCES meeting(id) ON DELETE CASCADE,
    stage           TEXT,
    status          TEXT NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'done', 'failed')),
    attempts        INTEGER NOT NULL DEFAULT 0,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX job_queue ON job (status, id);

CREATE TABLE setting (
    key        TEXT PRIMARY KEY,
    value_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
