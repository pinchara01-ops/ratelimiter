-- =========================================================================
-- V2 Migration: Add missing indexes, FK constraints, and data retention
-- =========================================================================

-- Composite indexes for common analytics queries
-- getTopClients: GROUP BY client_id ORDER BY count DESC, filtered by time
CREATE INDEX idx_de_client_occurred ON decision_events (client_id, occurred_at DESC);

-- getAggregatedStats: filtered by policy_id and time
CREATE INDEX idx_de_policy_occurred ON decision_events (policy_id, occurred_at DESC);

-- Deny rate queries: filtered by allowed status and time
CREATE INDEX idx_de_allowed_occurred ON decision_events (allowed, occurred_at DESC);

-- getRecentEvents: multiple filter combinations with occurred_at ordering
CREATE INDEX idx_de_client_allowed_occurred ON decision_events (client_id, allowed, occurred_at ASC);

-- Foreign key constraint to prevent orphaned analytics events
-- Note: policy_id can be NULL for unmatched requests, so ON DELETE SET NULL
ALTER TABLE decision_events
  ADD CONSTRAINT fk_de_policy
  FOREIGN KEY (policy_id) REFERENCES policies(id) ON DELETE SET NULL;

-- Unique constraint to prevent duplicate policies for same scope
-- This ensures only one policy per (client_id, endpoint, method) combination
ALTER TABLE policies ADD CONSTRAINT uq_policy_scope
  UNIQUE (client_id, endpoint, method);

-- Add comments for documentation
COMMENT ON INDEX idx_de_client_occurred IS 'Optimizes getTopClients query: GROUP BY client_id with time filter';
COMMENT ON INDEX idx_de_policy_occurred IS 'Optimizes getAggregatedStats query: filter by policy_id and time';
COMMENT ON INDEX idx_de_allowed_occurred IS 'Optimizes deny rate queries: filter by allowed status and time';
COMMENT ON INDEX idx_de_client_allowed_occurred IS 'Optimizes getRecentEvents: multiple filters with time ordering';
COMMENT ON CONSTRAINT fk_de_policy IS 'Prevents orphaned decision_events when policies are deleted';
COMMENT ON CONSTRAINT uq_policy_scope IS 'Ensures unique policy per (client_id, endpoint, method)';