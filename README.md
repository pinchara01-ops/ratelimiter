# RateForge

A distributed rate limiter built with Kotlin, Spring Boot, gRPC, Redis, and PostgreSQL. Exposes a gRPC API for checking rate limits and managing policies. Built as a portfolio project.

## What it does

- Enforces per-client, per-endpoint rate limits using configurable policies
- Supports three algorithms: Fixed Window, Sliding Window, and Token Bucket
- Policies are stored in PostgreSQL and cached in memory with a 30s refresh
- Rate limit counters live in Redis using Lua scripts for atomic execution
- Includes a circuit breaker that falls back to fail-open/fail-closed if Redis becomes unavailable
- Hot-key mitigation: high-traffic keys are served from a local pre-counter to reduce Redis round-trips
- Decision events (allowed/denied + latency) are written asynchronously to PostgreSQL for analytics

## Stack

- **Kotlin + Spring Boot 3.2** — application framework
- **gRPC (grpc-spring-boot-starter)** — API layer (port 9090)
- **Redis 7** — rate limit counters via Lua scripts
- **PostgreSQL 16** — policy storage + analytics events
- **Flyway** — database migrations
- **Docker Compose** — local setup

## Running locally

Requires Docker Desktop.

```bash
docker compose up --build
```

Services started:
- gRPC server: `localhost:9090`
- HTTP (actuator): `localhost:8080`
- PostgreSQL: `localhost:5432`
- Redis: `localhost:6379`

Health check:
```bash
curl http://localhost:8080/actuator/health/readiness
```

## gRPC API

### RateLimiterService

**CheckLimit** — single rate limit check
```protobuf
message CheckLimitRequest {
  string client_id = 1;
  string endpoint  = 2;
  string method    = 3;
  int64  cost      = 4;        // defaults to 1
}

message CheckLimitResponse {
  bool   allowed    = 1;
  int64  remaining  = 2;
  int64  reset_at_ms = 3;
  string policy_id  = 4;
  string reason     = 5;
}
```

**BatchCheck** — check multiple requests in one call (processed in parallel)

**GetLimitStatus** — query current remaining quota without consuming it

### ConfigService

CRUD for rate limit policies:

| RPC | Description |
|-----|-------------|
| `CreatePolicy` | Create a new policy |
| `UpdatePolicy` | Update an existing policy |
| `DeletePolicy` | Delete a policy by ID |
| `GetPolicy` | Fetch a single policy |
| `ListPolicies` | Paginated list, optional enabled-only filter |

## Policies

A policy defines who gets rate limited, how, and by how much.

| Field | Description |
|-------|-------------|
| `client_id` | Client identifier, or `*` to match all |
| `endpoint` | Exact path, prefix (`/api/*`), or `*` |
| `method` | HTTP method or `*` |
| `algorithm` | `FIXED_WINDOW`, `SLIDING_WINDOW`, or `TOKEN_BUCKET` |
| `limit` | Max requests per window |
| `window_ms` | Window duration in milliseconds |
| `priority` | Lower number = matched first |
| `no_match_behavior` | `FAIL_OPEN` (allow) or `FAIL_CLOSED` (deny) when no policy matches |

Token Bucket additionally requires `bucket_size` and `refill_rate`.

The first matching policy (by priority order) is applied. If no policy matches, the server-level default applies (`FAIL_OPEN` by default).

## Database

Two Flyway migrations:

**V1** — creates `policies` and `decision_events` tables

**V2** — adds composite indexes for analytics queries, FK constraint on `decision_events.policy_id`, and a unique constraint on `(client_id, endpoint, method)` to prevent duplicate policies

Decision events older than 90 days are deleted nightly (configurable via `rateforge.analytics.retention-days`).

## Configuration

Key properties in `application.yml`:

```yaml
rateforge:
  default-no-match-behavior: FAIL_OPEN
  policy-cache-refresh-interval-ms: 30000
  circuit-breaker:
    failure-threshold: 5
    window-ms: 10000
    probe-interval-ms: 30000
    success-threshold: 2
  analytics:
    queue-capacity: 10000
    flush-interval-ms: 500
    retention-days: 90
```

All infrastructure hostnames are overridable via environment variables (`DB_HOST`, `REDIS_HOST`, etc.).

## Tests

Unit tests cover:
- `FixedWindowExecutor`, `SlidingWindowExecutor`, `TokenBucketExecutor` — algorithm correctness, cost multiplier, window expiry, client isolation
- `CircuitBreaker` — state transitions (CLOSED → OPEN → HALF\_OPEN → CLOSED), probe logic, fallback behavior
- `LocalPreCounter` — hot-key detection, local budget consumption and replenishment
- `PolicyMatcher` — priority ordering, wildcard and prefix matching, case-insensitive method matching

```bash
./gradlew test
```

## What's not done

The repo contains stubs for separate `analytics-service`, `config-service`, `rate-limiter-service`, and `dashboard-service` directories. These are not wired into docker-compose and are not functional — everything runs as a single server module for now.
