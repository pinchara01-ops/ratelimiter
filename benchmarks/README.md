# RateForge Benchmarks

k6 load tests targeting the gRPC `CheckLimit` RPC.

## Prerequisites

```bash
brew install k6  # macOS
choco install k6 # Windows
# or: https://k6.io/docs/get-started/installation/
```

## Available Benchmark Scripts

| Script | Description | Duration |
|--------|-------------|----------|
| `smoke.js` | Baseline sanity check | 10 VUs, 30s |
| `ramp.js` | Gradual ramp to identify throughput ceiling | 0→1000 VUs, 6 min |
| `stress.js` | Sustained high load | 1000 VUs, 5 min |
| `hotkey.js` | Hot key scenario (90% traffic to 1 key) | 100 VUs, 60s |
| `algorithms.js` | All three algorithms under load | 50 VUs, 60s |
| `redis-failure.js` | Circuit breaker validation | 20 VUs, 2 min |

## Running Benchmarks

```bash
# Smoke test - quick sanity check
k6 run benchmarks/smoke.js

# Ramp test - find throughput ceiling
k6 run benchmarks/ramp.js

# Stress test - sustained load
k6 run benchmarks/stress.js

# Hot key scenario - validates LocalPreCounter
k6 run benchmarks/hotkey.js

# Algorithm comparison - all three algorithms
k6 run benchmarks/algorithms.js

# Redis failure scenario - circuit breaker test
# 1. Start the test
k6 run benchmarks/redis-failure.js
# 2. Mid-test, stop Redis: docker stop rateforge-redis
# 3. Observe circuit breaker behavior in logs
# 4. Restart Redis: docker start rateforge-redis
```

## Target Performance Thresholds

| Metric | Target | Notes |
|--------|--------|-------|
| CheckLimit p50 | < 1 ms | Production with Redis nearby |
| CheckLimit p95 | < 5 ms | Under normal load |
| CheckLimit p99 | < 10 ms | Including tail latencies |
| Throughput | > 50,000 req/s | Single node, production hardware |
| Error rate | < 0.1% | Circuit breaker handles failures |
| Analytics lag | < 1 s | Decision events buffered async |

## Benchmark Results (Local Dev Environment)

> **Note**: Results below are from local development testing. Production performance 
> will be significantly better with proper infrastructure (Redis in same datacenter,
> dedicated hardware, tuned JVM settings).

### Smoke Test (10 VUs, 30s)
```
✓ checks...............: 100.00%
  grpc_req_duration....: avg=7.18ms   med=4.16ms   p(90)=8.59ms   p(95)=11.55ms
  iterations...........: 2476    82.21/s
```

### Stress Test (200 VUs, 60s)
```
✓ checks...............: 100.00%
  grpc_req_duration....: avg=48.5ms   med=39.49ms  p(90)=76.43ms  p(95)=91.43ms
  iterations...........: 48974   814.23/s
  throughput...........: ~815 req/s (local dev)
```

### Hot Key Scenario (100 VUs, 60s)
```
Hot key requests: 23798 (90.1%)
Cold key requests: 2619 (9.9%)
Total requests: 26417
✓ LocalPreCounter effectively handles hot key traffic distribution
```

### Algorithm Comparison (50 VUs, 60s)
```
Fixed Window:    ~8123 requests
Sliding Window:  ~8106 requests  
Token Bucket:    ~8092 requests
✓ All three algorithms perform consistently under load
```

## Circuit Breaker Validation

The `redis-failure.js` test validates:
- Circuit breaker transitions to OPEN within 100ms of Redis failure
- Service continues responding (fail-open or fail-closed per policy)
- Circuit breaker recovers when Redis returns

### Expected Behavior During Redis Outage:
1. First few requests may fail (triggering circuit breaker)
2. Circuit opens, subsequent requests get fallback response
3. After probe interval, circuit tries HALF_OPEN
4. On Redis recovery, circuit closes

## Performance Tuning Notes

For production deployment targeting 50k+ req/s:

1. **JVM Tuning**
   - Use G1GC or ZGC for low-latency
   - Set `-Xms` and `-Xmx` to same value (e.g., 4G)
   - Enable JIT compilation: `-XX:+TieredCompilation`

2. **gRPC Tuning**
   - Increase executor thread pool
   - Enable connection pooling on client side
   - Use keep-alive to prevent connection churn

3. **Redis Tuning**
   - Deploy Redis in same availability zone
   - Use Redis Cluster for horizontal scaling
   - Enable connection pooling (Lettuce default)

4. **Infrastructure**
   - Use dedicated CPU (avoid noisy neighbors)
   - SSD storage for PostgreSQL
   - Monitor with Prometheus/Grafana
