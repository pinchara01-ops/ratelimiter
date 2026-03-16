-- RAT-14: decision_events table for async analytics pipeline
CREATE TABLE IF NOT EXISTS decision_events (
    id          VARCHAR(36)        PRIMARY KEY,
    timestamp_ms BIGINT            NOT NULL,
    client_key  VARCHAR(255)       NOT NULL,
    endpoint    VARCHAR(255)       NOT NULL,
    policy_id   VARCHAR(255)       NOT NULL,
    algorithm   VARCHAR(50)        NOT NULL,
    decision    VARCHAR(10)        NOT NULL,
    latency_ms  DOUBLE PRECISION   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_decision_events_policy_id  ON decision_events (policy_id);
CREATE INDEX IF NOT EXISTS idx_decision_events_client_key ON decision_events (client_key);
CREATE INDEX IF NOT EXISTS idx_decision_events_timestamp  ON decision_events (timestamp_ms);
