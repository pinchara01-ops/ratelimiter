-- RAT-4: PostgreSQL schema for policies
CREATE TABLE policies (
    id                 VARCHAR(100)  PRIMARY KEY,
    policy_limit       BIGINT        NOT NULL,
    window_seconds     BIGINT        NOT NULL,
    algorithm          VARCHAR(30)   NOT NULL CHECK (algorithm IN ('FIXED_WINDOW','SLIDING_WINDOW','TOKEN_BUCKET')),
    priority           INTEGER       NOT NULL DEFAULT 0,
    client_key_pattern VARCHAR(500),
    endpoint_pattern   VARCHAR(500),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_policies_priority ON policies (priority DESC);
CREATE INDEX idx_policies_algorithm ON policies (algorithm);

-- Seed: default catch-all policy (100 req/min, fixed window)
INSERT INTO policies (id, policy_limit, window_seconds, algorithm, priority)
VALUES ('default', 100, 60, 'FIXED_WINDOW', 0);
