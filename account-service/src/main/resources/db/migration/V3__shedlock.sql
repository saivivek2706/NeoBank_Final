-- Distributed lock table for ShedLock: guarantees a @Scheduled maintenance job
-- (outbox / processed_events cleanup) runs on only one replica at a time.
CREATE TABLE shedlock (
    name        VARCHAR(64)  NOT NULL,
    lock_until  TIMESTAMP    NOT NULL,
    locked_at   TIMESTAMP    NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
