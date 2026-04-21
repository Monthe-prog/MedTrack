-- =============================================================================
-- MedTrack – PostgreSQL Schema
-- File    : db/init/01_schema.sql
-- Engine  : PostgreSQL 16
-- Runs automatically on first `docker-compose up` via the init-scripts volume.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Extensions
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "pgcrypto";    -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "pg_trgm";     -- fast ILIKE search on drug names

-- =============================================================================
-- SECTION 1 · IDENTITY & ACCESS CONTROL
-- =============================================================================

-- 1.1  Roles ──────────────────────────────────────────────────────────────────
CREATE TABLE roles (
    id          SMALLINT     PRIMARY KEY,
    name        VARCHAR(32)  NOT NULL UNIQUE,   -- 'DOCTOR' | 'PATIENT' | 'ADMIN'
    description TEXT
);

INSERT INTO roles (id, name, description) VALUES
    (1, 'ADMIN',   'Platform administrator with unrestricted access'),
    (2, 'DOCTOR',  'Licensed physician; full PHI access within own patients'),
    (3, 'PATIENT', 'End user; sees only own records, names anonymised in shared panels');

-- 1.2  Users ──────────────────────────────────────────────────────────────────
-- `firebase_uid` is the authoritative identity token from Firebase Auth.
CREATE TABLE users (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    firebase_uid    VARCHAR(128) NOT NULL UNIQUE,
    role_id         SMALLINT     NOT NULL REFERENCES roles(id),

    -- PHI fields (full data — visible ONLY to DOCTOR / ADMIN)
    full_name       VARCHAR(256) NOT NULL,
    email           VARCHAR(256) NOT NULL UNIQUE,
    phone           VARCHAR(32),
    date_of_birth   DATE,
    gender          VARCHAR(16)  CHECK (gender IN ('MALE','FEMALE','OTHER','PREFER_NOT_TO_SAY')),

    -- Anonymisation token: a stable, non-reversible alias shown in shared panels
    -- Format: e.g. "PT-7F3A" — generated once, never changes
    anon_alias      VARCHAR(16)  NOT NULL UNIQUE DEFAULT ('PT-' || upper(substr(md5(gen_random_uuid()::text), 1, 4))),

    -- Profile picture stored in MinIO; full URL reconstructed at runtime
    avatar_object_key  VARCHAR(512),

    -- Doctor-specific
    medical_license_no VARCHAR(64),
    specialty          VARCHAR(128),

    -- Audit
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_firebase_uid ON users (firebase_uid);
CREATE INDEX idx_users_role_id      ON users (role_id);
CREATE INDEX idx_users_anon_alias   ON users (anon_alias);

-- =============================================================================
-- SECTION 2 · DOCTOR–PATIENT RELATIONSHIP
-- =============================================================================

-- Which patients are assigned to which doctor.
-- A patient may appear under multiple doctors (multi-physician care).
CREATE TABLE doctor_patient_links (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    doctor_id   UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    patient_id  UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    linked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    UNIQUE (doctor_id, patient_id)
);

CREATE INDEX idx_dpl_doctor  ON doctor_patient_links (doctor_id);
CREATE INDEX idx_dpl_patient ON doctor_patient_links (patient_id);

-- =============================================================================
-- SECTION 3 · MEDICATIONS & PRESCRIPTIONS
-- =============================================================================

-- 3.1  Medication catalog (seeded from openFDA; cached locally) ───────────────
CREATE TABLE medications (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    openfda_id      VARCHAR(256) UNIQUE,        -- openFDA application_number
    brand_name      VARCHAR(256),
    generic_name    VARCHAR(256) NOT NULL,
    manufacturer    VARCHAR(256),
    dosage_form     VARCHAR(128),               -- tablet, capsule, liquid …
    strength        VARCHAR(64),                -- "500 mg"
    route           VARCHAR(64),                -- oral, intravenous …
    raw_fda_json    JSONB,                      -- full FDA label payload
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_medications_generic_trgm ON medications USING gin (generic_name gin_trgm_ops);
CREATE INDEX idx_medications_brand_trgm   ON medications USING gin (brand_name   gin_trgm_ops);

-- 3.2  Prescriptions ──────────────────────────────────────────────────────────
CREATE TABLE prescriptions (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    doctor_id       UUID         NOT NULL REFERENCES users(id),
    patient_id      UUID         NOT NULL REFERENCES users(id),
    medication_id   UUID         NOT NULL REFERENCES medications(id),

    -- Dosage details
    dosage_amount   NUMERIC(8,2) NOT NULL,      -- e.g. 1.5
    dosage_unit     VARCHAR(32)  NOT NULL,      -- "tablet" | "ml" | "mg"
    frequency_per_day   SMALLINT NOT NULL,      -- times per day
    duration_days   SMALLINT,                   -- null = ongoing
    instructions    TEXT,                       -- "Take with food", etc.

    -- Schedule: JSON array of time strings, e.g. ["08:00","14:00","20:00"]
    schedule_times  JSONB        NOT NULL DEFAULT '[]',

    -- Lifecycle
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE','PAUSED','COMPLETED','CANCELLED')),
    start_date      DATE         NOT NULL DEFAULT CURRENT_DATE,
    end_date        DATE,

    -- Generated PDF stored in MinIO
    pdf_object_key  VARCHAR(512),

    -- Audit
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_rx_doctor    ON prescriptions (doctor_id);
CREATE INDEX idx_rx_patient   ON prescriptions (patient_id);
CREATE INDEX idx_rx_status    ON prescriptions (status);

-- =============================================================================
-- SECTION 4 · INTAKE LOGS (Compliance Tracking)
-- =============================================================================

-- Each row records whether the patient took a dose at the scheduled time.
CREATE TABLE intake_logs (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    prescription_id     UUID         NOT NULL REFERENCES prescriptions(id) ON DELETE CASCADE,
    patient_id          UUID         NOT NULL REFERENCES users(id),

    scheduled_time      TIMESTAMPTZ  NOT NULL,   -- exact alarm trigger time
    taken_at            TIMESTAMPTZ,             -- null = not yet taken / missed
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING','TAKEN','MISSED','SKIPPED')),
    notes               TEXT,                    -- patient's optional note

    -- Device-side alarm ID stored for AlarmManager cancellation
    alarm_id            INT,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_intake_patient        ON intake_logs (patient_id);
CREATE INDEX idx_intake_prescription   ON intake_logs (prescription_id);
CREATE INDEX idx_intake_scheduled_time ON intake_logs (scheduled_time);

-- =============================================================================
-- SECTION 5 · ANONYMISED AUDIT LOG  (Data Privacy Layer)
-- =============================================================================
-- Stores every sensitive action.  PHI is NEVER stored here.
-- patient_id is replaced by anon_alias at write time (done by Ktor service layer).
-- This table is safe to expose to compliance dashboards without PHI risk.
-- =============================================================================

CREATE TABLE anonymised_audit_logs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(64)  NOT NULL,   -- 'PRESCRIPTION_CREATED' | 'INTAKE_MISSED' …
    actor_role      VARCHAR(32)  NOT NULL,   -- 'DOCTOR' | 'PATIENT' | 'SYSTEM'
    actor_alias     VARCHAR(16)  NOT NULL,   -- anon_alias of the actor
    subject_alias   VARCHAR(16),             -- anon_alias of the patient affected (if different)
    resource_type   VARCHAR(64),             -- 'Prescription' | 'IntakeLog' …
    resource_id     UUID,                    -- PK of the affected row
    meta            JSONB,                   -- non-PHI contextual data (drug name, dose, etc.)
    ip_address      INET,                    -- for security auditing
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_actor_alias   ON anonymised_audit_logs (actor_alias);
CREATE INDEX idx_audit_subject_alias ON anonymised_audit_logs (subject_alias);
CREATE INDEX idx_audit_event_type    ON anonymised_audit_logs (event_type);
CREATE INDEX idx_audit_occurred_at   ON anonymised_audit_logs (occurred_at DESC);

-- =============================================================================
-- SECTION 6 · REAL-TIME CHAT (Doctor–Patient WebSocket)
-- =============================================================================

CREATE TABLE chat_rooms (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    doctor_id   UUID        NOT NULL REFERENCES users(id),
    patient_id  UUID        NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (doctor_id, patient_id)
);

CREATE TABLE chat_messages (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id     UUID         NOT NULL REFERENCES chat_rooms(id) ON DELETE CASCADE,
    sender_id   UUID         NOT NULL REFERENCES users(id),
    content     TEXT         NOT NULL,
    is_read     BOOLEAN      NOT NULL DEFAULT FALSE,
    sent_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_messages_room   ON chat_messages (room_id, sent_at DESC);

-- =============================================================================
-- SECTION 7 · UTILITY TRIGGERS
-- =============================================================================

-- Auto-update `updated_at` on mutations
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_prescriptions_updated_at
    BEFORE UPDATE ON prescriptions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
