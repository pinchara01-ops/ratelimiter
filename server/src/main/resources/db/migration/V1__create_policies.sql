CREATE TABLE policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL UNIQUE,
    client_id VARCHAR(255) NOT NULL DEFAULT '*',
    endpoint VARCHAR(500) NOT NULL DEFAULT '*',
    method VARCHAR(10) NOT NULL DEFAULT '*',
    algorithm VARCHAR(20) NOT NULL,
    "limit" BIGINT NOT NULL,
    window_ms BIGINT NOT NULL,
    bucket_size BIGINT,
    refill_rate DOUBLE PRECISION,
    cost BIGINT NOT NULL DEFAULT 1,
    priority INT NOT NULL DEFAULT 100,
    no_match_behavior VARCHAR(20),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_policies_priority ON policies(priority ASC) WHERE enabled = TRUE;

CREATE TABLE decision_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id VARCHAR(255) NOT NULL,
    endpoint VARCHAR(500) NOT NULL,
    method VARCHAR(10),
    policy_id UUID,
    allowed BOOLEAN NOT NULL,
    reason VARCHAR(50) NOT NULL,
    latency_us BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_decision_events_occurred_at ON decision_events(occurred_at DESC);
