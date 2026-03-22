# RateForge — Architecture

## System Diagram

```mermaid
graph TB
    subgraph Clients
        C1[SDK / HTTP client]
        C2[Dashboard browser]
    end

    subgraph "server/ — port 9090"
        RL[RateLimiterGrpcService<br/>CheckLimit · BatchCheck · GetLimitStatus]
        CFG[ConfigGrpcService<br/>CreatePolicy · UpdatePolicy · ListPolicies]
        PC[PolicyCache<br/>30 s refresh from DB]
        AP[AnalyticsPipeline<br/>async batch writer]
        CB[CircuitBreaker<br/>CLOSED → OPEN → HALF_OPEN]
        LPC[LocalPreCounter<br/>hot-key mitigation]
        ALG[FixedWindow / SlidingWindow / TokenBucket<br/>Lua scripts — 1 Redis RTT each]
    end

    subgraph "analytics-service — port 50052"
        AS[AnalyticsServiceImpl<br/>GetUsageStats · StreamDecisions · GetTopClients]
        REPO[DecisionEventRepository<br/>DB-poll every 500 ms for stream]
    end

    subgraph "dashboard-service — port 3000"
        NEXT[Next.js 14 App Router]
        BFF[API Routes BFF<br/>/api/policies · /api/stats · /api/stream · /api/top-clients]
        UI[Policies · Analytics · Live Feed · Simulator]
    end

    subgraph "Infrastructure"
        REDIS[(Redis 7<br/>rate-limit counters)]
        PG[(PostgreSQL 16<br/>policies · decision_events)]
    end

    C1 -- "gRPC CheckLimit" --> RL
    C2 -- "HTTPS" --> NEXT
    NEXT --> BFF
    BFF -- "gRPC ListPolicies / CRUD" --> CFG
    BFF -- "gRPC GetUsageStats / StreamDecisions" --> AS
    RL --> CB --> ALG --> REDIS
    RL --> LPC
    RL --> AP --> PG
    CFG --> PG
    PC -- "SELECT policies" --> PG
    PC --> RL
    AS --> REPO --> PG
```

## Services

| Service | Port | Responsibility |
|---------|------|----------------|
| `server/` | 9090 | gRPC rate-limit decisions + policy CRUD + analytics write |
| `analytics-service/` | 50052 | gRPC analytics read API (stats, stream, top-clients) |
| `dashboard-service/` | 3000 | Next.js BFF + admin UI |
| Redis | 6379 | Rate-limit state (atomic Lua counters) |
| PostgreSQL | 5432 | Policies + decision event log |

## Request flow — CheckLimit

1. Client calls `CheckLimit(clientId, endpoint, method, cost)`
2. `PolicyMatcher` finds highest-priority matching policy from `PolicyCache`
3. `LocalPreCounter` checks if request is a hot key → pre-allocate Redis budget
4. `CircuitBreaker` wraps Redis call — fail-open if Redis is down
5. Algorithm executes Lua script (1 Redis RTT):
   - **Fixed Window**: `INCR key; EXPIRE if new`
   - **Sliding Window**: `ZREMRANGEBYSCORE; ZCARD; ZADD`
   - **Token Bucket**: `HGET tokens,last_refill; compute refill; HSET`
6. Decision (allow/deny) returned to client
7. `AnalyticsPipeline.publish()` enqueues event async (non-blocking)
8. `BatchProcessor` flushes to PostgreSQL every 500 ms or 1 000 events

## Key design decisions

- **Lua scripts for atomicity** — all counter mutations are single-script, no WATCH/MULTI/EXEC needed
- **Fail-open circuit breaker** — Redis outage degrades gracefully (allows traffic)
- **DB-poll streaming** — `StreamDecisions` tails PostgreSQL every 500 ms, avoiding cross-process SharedFlow coupling
- **Policy cache** — 30-second in-memory cache prevents DB round-trip on every request; cache refreshes automatically
- **Hot-key mitigation** — Caffeine-backed pre-counter batches Redis round-trips for keys > 100 req/s
