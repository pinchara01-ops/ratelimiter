/**
 * RAT-29 — Algorithm coverage test: Tests all three rate limiting algorithms.
 * Validates fixed window, sliding window, and token bucket under load.
 */
import grpc from "k6/net/grpc";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";

export const options = {
  vus: 50,
  duration: "60s",
  thresholds: {
    checks: ["rate>0.99"],
  },
};

// Custom metrics per algorithm
const fixedWindowLatency = new Trend("fixed_window_latency", true);
const slidingWindowLatency = new Trend("sliding_window_latency", true);
const tokenBucketLatency = new Trend("token_bucket_latency", true);

const fixedWindowCount = new Counter("fixed_window_requests");
const slidingWindowCount = new Counter("sliding_window_requests");
const tokenBucketCount = new Counter("token_bucket_requests");

const client = new grpc.Client();
client.load(["../proto/src/main/proto"], "ratelimiter.proto");

// Endpoints mapped to different algorithms (must match policy configuration)
const ALGORITHM_ENDPOINTS = [
  { endpoint: "/api/orders", algorithm: "fixed_window" },
  { endpoint: "/api/products", algorithm: "sliding_window" },
  { endpoint: "/api/tokens", algorithm: "token_bucket" },
];

export default function () {
  client.connect("localhost:9090", { plaintext: true });

  // Round-robin through algorithms
  const config = ALGORITHM_ENDPOINTS[__ITER % ALGORITHM_ENDPOINTS.length];
  
  const start = Date.now();
  const res = client.invoke("rateforge.v1.RateLimiterService/CheckLimit", {
    client_id: `algo-test-${__VU}`,
    endpoint: config.endpoint,
    method: "GET",
    cost: 1,
  });
  const latency = Date.now() - start;

  check(res, {
    "status OK": r => r && r.status === grpc.StatusOK,
    "has allowed field": r => r && r.message && typeof r.message.allowed === "boolean",
  });

  // Record per-algorithm metrics
  switch (config.algorithm) {
    case "fixed_window":
      fixedWindowLatency.add(latency);
      fixedWindowCount.add(1);
      break;
    case "sliding_window":
      slidingWindowLatency.add(latency);
      slidingWindowCount.add(1);
      break;
    case "token_bucket":
      tokenBucketLatency.add(latency);
      tokenBucketCount.add(1);
      break;
  }

  client.close();
}

export function handleSummary(data) {
  const getMetric = (name, stat) => {
    const m = data.metrics[name];
    return m && m.values ? m.values[stat] : "N/A";
  };

  return {
    stdout: `\n=== Algorithm Performance Comparison ===\n` +
            `\nFixed Window:\n` +
            `  Requests: ${getMetric("fixed_window_requests", "count")}\n` +
            `  p50: ${getMetric("fixed_window_latency", "med")}ms\n` +
            `  p95: ${getMetric("fixed_window_latency", "p(95)")}ms\n` +
            `\nSliding Window:\n` +
            `  Requests: ${getMetric("sliding_window_requests", "count")}\n` +
            `  p50: ${getMetric("sliding_window_latency", "med")}ms\n` +
            `  p95: ${getMetric("sliding_window_latency", "p(95)")}ms\n` +
            `\nToken Bucket:\n` +
            `  Requests: ${getMetric("token_bucket_requests", "count")}\n` +
            `  p50: ${getMetric("token_bucket_latency", "med")}ms\n` +
            `  p95: ${getMetric("token_bucket_latency", "p(95)")}ms\n`,
  };
}
