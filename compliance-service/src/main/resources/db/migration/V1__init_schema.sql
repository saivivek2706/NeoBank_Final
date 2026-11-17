-- Compliance service schema: sanctions screening, AML transaction monitoring and case management.

-- ---------------------------------------------------------------- platform tables (same pattern as every service)
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(100)    NOT NULL,
    aggregate_id    VARCHAR(100)    NOT NULL,
    event_type      VARCHAR(100)    NOT NULL,
    topic           VARCHAR(150)    NOT NULL,
    payload         TEXT            NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    attempts        INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);
CREATE INDEX idx_outbox_status_created ON outbox_events (status, created_at);

CREATE TABLE processed_events (
    event_id        UUID PRIMARY KEY,
    processed_at    TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE TABLE shedlock (
    name        VARCHAR(64)  NOT NULL,
    lock_until  TIMESTAMP    NOT NULL,
    locked_at   TIMESTAMP    NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- ---------------------------------------------------------------- sanctions watchlists
-- Every import is a version, so any screening result can be tied to exactly the list content it was run against.
CREATE TABLE watchlist_versions (
    id            UUID PRIMARY KEY,
    imported_by   UUID         NOT NULL,
    imported_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    mode          VARCHAR(10)  NOT NULL,                 -- FULL (snapshot: absent entries are deactivated) | DELTA
    entry_count   INT          NOT NULL,
    added         INT          NOT NULL,
    changed       INT          NOT NULL,
    deactivated   INT          NOT NULL,
    checksum      VARCHAR(64)  NOT NULL                  -- SHA-256 of the uploaded file
);

CREATE TABLE watchlist_entries (
    id               UUID PRIMARY KEY,
    list_source      VARCHAR(50)  NOT NULL,              -- e.g. OFAC_SDN, UN_CONSOLIDATED, INTERNAL
    external_id      VARCHAR(100) NOT NULL,
    primary_name     VARCHAR(255) NOT NULL,
    normalized_name  VARCHAR(255) NOT NULL,
    country          VARCHAR(2),
    entity_type      VARCHAR(20)  NOT NULL,              -- INDIVIDUAL | ORGANIZATION
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    version_id       UUID         NOT NULL REFERENCES watchlist_versions(id),   -- version that last added/changed it
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_watchlist_entry UNIQUE (list_source, external_id)
);
CREATE INDEX idx_watchlist_entries_active ON watchlist_entries (active);

CREATE TABLE watchlist_aliases (
    id                UUID PRIMARY KEY,
    entry_id          UUID         NOT NULL REFERENCES watchlist_entries(id),
    alias             VARCHAR(255) NOT NULL,
    normalized_alias  VARCHAR(255) NOT NULL
);
CREATE INDEX idx_watchlist_aliases_entry ON watchlist_aliases (entry_id);

-- ---------------------------------------------------------------- screening
CREATE TABLE screened_parties (
    id                UUID PRIMARY KEY,
    account_id        UUID          NOT NULL UNIQUE,
    owner_id          UUID          NOT NULL,
    party_name        VARCHAR(255)  NOT NULL,
    status            VARCHAR(30)   NOT NULL,   -- CLEAR | POTENTIAL_MATCH | CONFIRMED_MATCH | CLEARED_FALSE_POSITIVE
    top_score         NUMERIC(5,4)  NOT NULL DEFAULT 0,
    auto_frozen       BOOLEAN       NOT NULL DEFAULT FALSE,   -- compliance froze the account because of this hit
    last_screened_at  TIMESTAMPTZ   NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_by       UUID,
    resolved_at       TIMESTAMPTZ,
    resolution_note   VARCHAR(500)
);
CREATE INDEX idx_screened_parties_status ON screened_parties (status);

-- Evidence: one immutable row per screening run (what was checked, against which list version, with what result).
CREATE TABLE screening_results (
    id                 UUID PRIMARY KEY,
    party_id           UUID REFERENCES screened_parties(id),
    screened_name      VARCHAR(255)  NOT NULL,
    trigger_type       VARCHAR(30)   NOT NULL,  -- ONBOARDING | WATCHLIST_UPDATE | MANUAL | AD_HOC
    outcome            VARCHAR(20)   NOT NULL,  -- CLEAR | POTENTIAL_MATCH | STRONG_MATCH
    top_score          NUMERIC(5,4)  NOT NULL,
    matches            TEXT          NOT NULL,  -- JSON array of {entryId, listSource, name, via, score}
    watchlist_version  UUID,
    screened_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_screening_results_party ON screening_results (party_id, screened_at);

-- ---------------------------------------------------------------- transaction monitoring
CREATE TABLE monitored_transactions (
    id              UUID PRIMARY KEY,
    event_id        UUID          NOT NULL UNIQUE,
    transaction_id  UUID          NOT NULL,
    account_id      UUID          NOT NULL,
    direction       VARCHAR(3)    NOT NULL CHECK (direction IN ('IN', 'OUT')),
    amount          NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency        VARCHAR(3)    NOT NULL,
    occurred_at     TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_monitored_tx_account_time ON monitored_transactions (account_id, occurred_at);

-- ---------------------------------------------------------------- cases
CREATE SEQUENCE case_number_seq START 1;

CREATE TABLE aml_cases (
    id                  UUID PRIMARY KEY,
    case_number         VARCHAR(30)  NOT NULL UNIQUE,
    subject_account_id  UUID         NOT NULL,
    case_type           VARCHAR(30)  NOT NULL,   -- TRANSACTION_MONITORING | SANCTIONS_SCREENING
    status              VARCHAR(20)  NOT NULL,   -- OPEN | IN_REVIEW | ESCALATED | CLOSED
    priority            VARCHAR(10)  NOT NULL,   -- LOW | MEDIUM | HIGH | CRITICAL
    assignee            UUID,
    escalated_by        UUID,                    -- the maker in the maker-checker (four-eyes) rule
    account_frozen      BOOLEAN      NOT NULL DEFAULT FALSE,
    summary             VARCHAR(500) NOT NULL,
    disposition         VARCHAR(30),
    closed_by           UUID,
    opened_at           TIMESTAMPTZ  NOT NULL,
    due_at              TIMESTAMPTZ  NOT NULL,
    closed_at           TIMESTAMPTZ,
    version             BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_aml_cases_status ON aml_cases (status, priority);
CREATE INDEX idx_aml_cases_assignee ON aml_cases (assignee) WHERE status <> 'CLOSED';
-- At most ONE open case per subject account and type: new alerts attach to it instead of piling up duplicates.
CREATE UNIQUE INDEX uq_one_open_case_per_subject
    ON aml_cases (subject_account_id, case_type) WHERE status <> 'CLOSED';

CREATE TABLE aml_alerts (
    id              UUID PRIMARY KEY,
    rule_code       VARCHAR(40)   NOT NULL,
    account_id      UUID          NOT NULL,
    transaction_id  UUID,
    severity        VARCHAR(10)   NOT NULL,
    score           INT           NOT NULL,
    summary         VARCHAR(500)  NOT NULL,
    details         TEXT          NOT NULL,           -- JSON: the figures that triggered the rule
    dedup_key       VARCHAR(200)  NOT NULL UNIQUE,    -- one alert per rule per window, however many events arrive
    case_id         UUID REFERENCES aml_cases(id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_aml_alerts_case ON aml_alerts (case_id);
CREATE INDEX idx_aml_alerts_account ON aml_alerts (account_id, created_at);

-- Case history is append-only: who did what, when, and why - the audit trail a regulator asks for.
CREATE TABLE case_events (
    id           UUID PRIMARY KEY,
    case_id      UUID          NOT NULL REFERENCES aml_cases(id),
    event_type   VARCHAR(30)   NOT NULL,
    actor        UUID,                                -- NULL = the system
    from_status  VARCHAR(20),
    to_status    VARCHAR(20),
    note         VARCHAR(1000),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_case_events_case ON case_events (case_id, created_at);

CREATE FUNCTION forbid_case_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: case history is append-only', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END $$;

CREATE TRIGGER trg_case_events_append_only
    BEFORE UPDATE OR DELETE ON case_events
    FOR EACH ROW EXECUTE FUNCTION forbid_case_history_mutation();

CREATE TRIGGER trg_screening_results_append_only
    BEFORE UPDATE OR DELETE ON screening_results
    FOR EACH ROW EXECUTE FUNCTION forbid_case_history_mutation();
