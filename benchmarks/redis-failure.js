/**
 * RAT-29 — Redis failure scenario: Validates circuit breaker behavior.
 * Run this test, then manually stop Redis mid-test to verify failover.
 * 
 * Usage:
 *   1. Start the test: k6 run benchmarks/redis-failure.js
 *   2. After ~30s, stop Redis: docker stop rateforge-redis
 *   3. Observe circuit breaker transitions in logs
 *   4. Restart Redis: docker start rateforge-redis
 *   5. Verify service recovery
 */
import grpc from "k6/net/grpc";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

export const options = {
  vus: 20,
  duration: "2m",
  thresholds: {
    // Allow some failures during Redis outage (circuit breaker should handle)
    checks: ["rate>0.90"],
  },
};

const successCount = new Counter("success_count");
const failureCount = new Counter("failure_count");
const circuitOpenCount = new Counter("circuit_open_responses");
const responseTrend = new Trend("response_latency", true);

const client = new grpc.Client();
client.load(["../proto/src/main/proto"], "ratelimiter.proto");

export default function () {
  client.connect("localhost:9090", { plaintext: true });

  const start = Date.now();
  
  try {
    const res = client.invoke("rateforge.v1.RateLimiterService/CheckLimit", {
      client_id: `redis-test-${__VU}`,
      endpoint: "/api/resilience-test",
      method: "GET",
      cost: 1,
    });
    
    const latency = Date.now() - start;
    responseTrend.add(latency);

    const isOK = res && res.status === grpc.StatusOK;
    
    if (isOK) {
      successCount.add(1);
      
      // Check if response indicates circuit breaker is open
      if (res.message && res.message.reason === "CIRCUIT_OPEN") {
        circuitOpenCount.add(1);
      }
    } else {
      failureCount.add(1);
    }

    check(res, {
      "response received": r => r !== null && r !== undefined,
      "status OK or circuit open": r => r && (r.status === grpc.StatusOK),
    });

  } catch (e) {
    failureCount.add(1);
    console.log(`Request failed: ${e.message}`);
  }

  client.close();
  sleep(0.1); // 100ms between requests for visibility
}

export function handleSummary(data) {
  const success = data.metrics.success_count ? data.metrics.success_count.values.count : 0;
  const failure = data.metrics.failure_count ? data.metrics.failure_count.values.count : 0;
  const circuitOpen = data.metrics.circuit_open_responses ? data.metrics.circuit_open_responses.values.count : 0;
  const total = success + failure;
  const successRate = total > 0 ? ((success / total) * 100).toFixed(2) : 0;

  return {
    stdout: `\n=== Redis Failure Scenario Results ===\n` +
            `Total requests: ${total}\n` +
            `Successful: ${success} (${successRate}%)\n` +
            `Failed: ${failure}\n` +
            `Circuit breaker responses: ${circuitOpen}\n` +
            `\nCircuit breaker target: Transition within 100ms of Redis failure\n` +
            `Note: If Redis was stopped during test, circuit_open > 0 indicates proper failover\n`,
  };
}
