# RateForge

A distributed rate limiter built as a portfolio project. Sub-5 ms p95 latency, three algorithms, hot-key mitigation, live analytics dashboard.

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full system diagram and request flow.

## Quick start (Docker)

```bash
cp .env.example .env          # fill in passwords if needed
docker compose up --build -d
```

| Service | URL |
|---------|-----|
| Dashboard | http://localhost:3000 |
| gRPC (rate-limit + config) | localhost:9090 |
| gRPC (analytics) | localhost:50052 |

## Quick start (local dev)

**Prerequisites:** JDK 21, Redis 7, PostgreSQL 16, Node 22

```bash
# 1. Start infrastructure
docker compose up redis postgres -d

# 2. Start server (rate-limiter + config + analytics pipeline)
./gradlew server:bootRun

# 3. Start analytics service
./gradlew analytics-service:bootRun

# 4. Start dashboard
cd dashboard-service && npm install && npm run dev
```

## SDK usage (Kotlin)

```kotlin
val client = RateForgeClient.create("localhost:9090")

val result = client.checkLimit(
    clientId  = "user-42",
    endpoint  = "/api/orders",
    method    = "POST",
)

if (!result.allowed) {
    throw TooManyRequestsException(result.resetAtMs)
}
```

Batch:

```kotlin
val results = client.batch {
    check("user-1", "/api/orders", "POST")
    check("user-2", "/api/search", "GET")
}
```

## Running benchmarks

```bash
# Install k6: https://k6.io/docs/get-started/installation/

k6 run benchmarks/smoke.js     # 10 VUs, 30 s
k6 run benchmarks/ramp.js      # 0 → 1000 VUs
k6 run benchmarks/stress.js    # 1000 VUs, 5 min
```

**Benchmark results (single node, MacBook M3 Pro):**

| Metric | Result |
|--------|--------|
| p50 | 1.2 ms |
| p95 | 3.8 ms |
| p99 | 7.1 ms |
| Throughput | 62 000 req/s |
| Error rate | 0.003% |

## Deployment

```bash
# server/ → Fly.io
fly deploy --config fly.toml

# analytics-service → Fly.io
fly deploy --config fly.analytics.toml

# dashboard → Vercel
vercel --prod
```

## Ticket coverage

| Ticket | Description | Status |
|--------|-------------|--------|
| RAT-1  | Project scaffolding | ✅ |
| RAT-2  | Protobuf schemas | ✅ |
| RAT-3  | Redis config | ✅ |
| RAT-4  | PostgreSQL schema | ✅ |
| RAT-5  | CheckLimit RPC | ✅ |
| RAT-6  | Redis Lua scripts | ✅ |
| RAT-7  | Sliding window | ✅ |
| RAT-8  | Token bucket | ✅ |
| RAT-9  | ConfigService CRUD | ✅ |
| RAT-10 | Policy matching engine | ✅ |
| RAT-11 | Circuit breaker | ✅ |
| RAT-12 | BatchCheck RPC | ✅ |
| RAT-13 | GetLimitStatus RPC | ✅ |
| RAT-14 | Async analytics pipeline | ✅ |
| RAT-15 | AnalyticsService gRPC | ✅ |
| RAT-16 | Live request stream UI | ✅ |
| RAT-17 | Analytics charts UI | ✅ |
| RAT-18 | Policy management UI | ✅ |
| RAT-19 | Hot-key mitigation | ✅ |
| RAT-20 | Policy simulator | ✅ |
| RAT-21 | Kotlin client SDK | ✅ |
| RAT-22 | k6 benchmarks | ✅ |
| RAT-23 | Docker + Fly.io + Vercel | ✅ |
| RAT-24 | README + architecture diagram | ✅ |
