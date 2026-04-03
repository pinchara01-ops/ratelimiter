-- V3: Add soft_limit column for soft rate limiting
-- When soft_limit is set and usage exceeds it (but not hard limit), 
-- requests are still allowed but flagged as "throttled"

ALTER TABLE policies ADD COLUMN soft_limit BIGINT DEFAULT NULL;

COMMENT ON COLUMN policies.soft_limit IS 'Soft limit threshold - requests allowed but flagged as throttled when exceeded';
