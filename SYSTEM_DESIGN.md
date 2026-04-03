# RateForge — System Design

## Complete Architecture Diagram

```mermaid
flowchart TB
    subgraph Clients["Clients"]
        SDK["Any gRPC Client\n(clientId · endpoint · method · cost)"]
    end

    subgraph Docker["Docker Compose — local"]
        direction TB

        subgraph Server["server/ — port 9090 (gRPC) · 8080 (HTTP)"]
            direction TB

            subgraph gRPC["gRPC Layer"]
                RL["RateLimiterService\nCheckLimit · BatchCheck · GetLimitStatus"]
                CFG["ConfigService\nCreatePolicy · UpdatePolicy · DeletePolicy\nGetPolicy · ListPolicies"]
            end

            subgraph Core["Core — request path"]
                PM["PolicyMatcher\nwildcard · prefix · exact · priority"]
                PC["PolicyCache\nAtomicReference · 30 s refresh"]
                CB["CircuitBreaker\nCLOSED → OPEN → HALF_OPEN\nfailure threshold: 5 / 10 s window"]
                LPC["LocalPreCounter\nCaffeine · 100k keys · 5 min TTL\nhot-key threshold: 100 req/s"]
            end

            subgraph Algorithms["Rate Limit Algorithms (Lua — 1 Redis RTT)"]
                FW["FixedWindowExecutor\nINCR · EXPIRE"]
                SW["SlidingWindowExecutor\nZREMRANGEBYSCORE · ZCARD · ZADD"]
                TB["TokenBucketExecutor\nHGET tokens+last_refill\ncompute refill · HSET"]
            end

            subgraph Analytics["Analytics Pipeline"]
                AP["AnalyticsPipeline\nArrayBlockingQueue cap=10k\nflush every 500 ms · batch=1000\n@PreDestroy flush on shutdown"]
                RJ["RetentionJob\nnightly DELETE > 90 days"]
            end

            subgraph Health["Health & Lifecycle"]
                SRM["ServerReadinessManager\nNOT_SERVING → SERVING\non ApplicationReadyEvent"]
                GSM["GrpcShutdownManager\nSmartLifecycle phase=MAX_VALUE\nmarkNotReady → drain 30 s"]
                ACT["Spring Actuator :8080\n/health/readiness\n/health/liveness"]
            end
        end

        subgraph Infra["Infrastructure"]
            REDIS[("Redis 7\nrate-limit counters\nLua scripts")]
            PG[("PostgreSQL 16\npolicies\ndecision_events\nFlyway V1+V2")]
        end
    end

    subgraph NotWired["Stub directories — not wired into docker-compose"]
        AS["analytics-service/\nport 50052 stub"]
        DS["dashboard-service/\nNext.js stub"]
        CS["config-service/ stub"]
        RLS["rate-limiter-service/ stub"]
    end

    %% Client → gRPC
    SDK -->|"gRPC CheckLimit / BatchCheck"| RL
    SDK -->|"gRPC CreatePolicy / ListPolicies"| CFG

    %% CheckLimit flow
    RL --> PM
    PM --> PC
    PC -->|"SELECT enabled policies\nORDER BY priority ASC"| PG
    RL --> LPC
    RL --> CB
    CB -->|"execute Lua script"| FW & SW & TB
    FW & SW & TB -->|"atomic counter ops"| REDIS
    CB -->|"fail-open fallback\nif OPEN"| RL

    %% Analytics write
    RL -->|"publish DecisionEvent\nnon-blocking offer()"| AP
    AP -->|"batch INSERT\ndecision_events"| PG
    RJ -->|"DELETE WHERE occurred_at < now()-90d"| PG

    %% Config flow
    CFG -->|"INSERT/UPDATE/DELETE policies"| PG
    CFG -->|"invalidate()"| PC

    %% Health
    SRM -->|"setStatus SERVING"| RL
    GSM -->|"markNotReady → drain"| SRM
    ACT -->|"Redis ping + cache check"| REDIS & PC
```

---

## Request Flow — CheckLimit

```mermaid
sequenceDiagram
    participant C as gRPC Client
    participant RL as RateLimiterService
    participant PM as PolicyMatcher
    participant PC as PolicyCache
    participant LPC as LocalPreCounter
    participant CB as CircuitBreaker
    participant ALG as Algorithm (Lua)
    participant R as Redis
    participant AP as AnalyticsPipeline
    participant DB as PostgreSQL

    C->>RL: CheckLimit(clientId, endpoint, method, cost)

    alt CircuitBreaker OPEN
        RL-->>C: allowed=false / reason=CIRCUIT_OPEN
    end

    RL->>PM: findMatchingPolicy(clientId, endpoint, method)
    PM->>PC: getPolicies()
    PC-->>PM: List<Policy> sorted by priority

    alt No matching policy
        RL-->>C: allowed=true (FAIL_OPEN) or false (FAIL_CLOSED)
    end

    RL->>LPC: recordRequest(hotKey)
    LPC-->>RL: isHotKey + localBudget?

    alt Hot key + local budget available
        RL-->>C: allowed=true (no Redis RTT)
    end

    RL->>CB: execute(algorithm, fallback)
    CB->>ALG: checkLimit(policy, clientId, endpoint, cost)
    ALG->>R: Lua script (atomic)
    R-->>ALG: [allowed, remaining, resetAtMs]
    ALG-->>CB: RateLimitResult
    CB-->>RL: RateLimitResult

    RL->>AP: record(DecisionEvent) [non-blocking]
    AP-->>DB: batch INSERT every 500ms

    RL-->>C: CheckLimitResponse(allowed, remaining, resetAtMs, policyId, reason)
```

---

## Data Model

```mermaid
erDiagram
    policies {
        uuid id PK
        varchar name UK
        varchar client_id
        varchar endpoint
        varchar method
        varchar algorithm
        bigint limit
        bigint window_ms
        bigint bucket_size
        double refill_rate
        bigint cost
        int priority
        varchar no_match_behavior
        boolean enabled
        timestamptz created_at
        timestamptz updated_at
    }

    decision_events {
        uuid id PK
        varchar client_id
        varchar endpoint
        varchar method
        uuid policy_id FK
        boolean allowed
        varchar reason
        bigint latency_us
        timestamptz occurred_at
    }

    policies ||--o{ decision_events : "policy_id (ON DELETE SET NULL)"
```

**Indexes (V2 migration):**
- `idx_de_client_occurred` — `(client_id, occurred_at DESC)` — top-clients query
- `idx_de_policy_occurred` — `(policy_id, occurred_at DESC)` — per-policy usage stats
- `idx_de_allowed_occurred` — `(allowed, occurred_at DESC)` — deny rate queries
- `uq_policy_scope` — `UNIQUE(client_id, endpoint, method)` — prevents duplicate policies

---

## Circuit Breaker State Machine

```mermaid
stateDiagram-v2
    [*] --> CLOSED
    CLOSED --> OPEN: failures >= 5 in 10s window
    OPEN --> HALF_OPEN: probe interval elapsed (30s)
    HALF_OPEN --> CLOSED: 2 consecutive successes
    HALF_OPEN --> OPEN: any failure

    CLOSED: CLOSED\nAll requests pass through
    OPEN: OPEN\nAll requests get fallback\n(fail-open or fail-closed)
    HALF_OPEN: HALF_OPEN\nOne probe allowed\nOthers get fallback
```

---

## What is and isn't running

| Component | Status | Port |
|-----------|--------|------|
| RateForge gRPC server | Running | 9090 |
| Spring Actuator (health) | Running | 8080 |
| Redis | Running | 6379 |
| PostgreSQL | Running | 5432 |
| analytics-service | Stub only — not wired | 50052 |
| dashboard-service (Next.js) | Stub only — not wired | 3000 |
| config-service | Stub only — not wired | — |
| rate-limiter-service | Stub only — not wired | — |
