# RateForge Benchmarks

k6 load tests targeting the gRPC `CheckLimit` RPC.

## Prerequisites

```bash
brew install k6  # macOS
# or: https://k6.io/docs/get-started/installation/
```

## Run

```bash
# Smoke test
k6 run benchmarks/smoke.js

# Ramp load (100 → 1000 VUs)
k6 run benchmarks/ramp.js

# Sustained 1000 VUs for 5 min
k6 run benchmarks/stress.js
```

## Target thresholds (from PRD)

| Metric | Target |
|--------|--------|
| p50 latency | < 2 ms |
| p95 latency | < 5 ms |
| p99 latency | < 10 ms |
| Error rate | < 0.1% |
| Throughput | > 50 000 req/s (single node) |
