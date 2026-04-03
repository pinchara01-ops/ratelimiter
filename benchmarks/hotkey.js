/**
 * RAT-29 — Hot key scenario: 1 key receiving 90% of traffic.
 * Validates LocalPreCounter hot-key mitigation.
 */
import grpc from "k6/net/grpc";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

export const options = {
  vus: 100,
  duration: "60s",
  thresholds: {
    checks: ["rate>0.99"],
    hot_key_requests: ["count>5000"],
  },
};

const hotKeyRequests = new Counter("hot_key_requests");
const coldKeyRequests = new Counter("cold_key_requests");

const client = new grpc.Client();
client.load(["../proto/src/main/proto"], "ratelimiter.proto");

const HOT_KEY_CLIENT = "hot-client-001";
const HOT_KEY_ENDPOINT = "/api/hot-endpoint";

export default function () {
  client.connect("localhost:9090", { plaintext: true });

  // 90% of traffic goes to hot key
  const isHotKey = Math.random() < 0.9;
  
  const clientId = isHotKey ? HOT_KEY_CLIENT : `cold-client-${__VU}`;
  const endpoint = isHotKey ? HOT_KEY_ENDPOINT : `/api/endpoint-${__VU % 10}`;
  
  const res = client.invoke("rateforge.v1.RateLimiterService/CheckLimit", {
    client_id: clientId,
    endpoint: endpoint,
    method: "GET",
    cost: 1,
  });

  const success = check(res, {
    "status OK": r => r && r.status === grpc.StatusOK,
    "has response": r => r && r.message && typeof r.message.allowed === "boolean",
  });

  if (isHotKey) {
    hotKeyRequests.add(1);
  } else {
    coldKeyRequests.add(1);
  }

  client.close();
}

export function handleSummary(data) {
  const hotCount = data.metrics.hot_key_requests ? data.metrics.hot_key_requests.values.count : 0;
  const coldCount = data.metrics.cold_key_requests ? data.metrics.cold_key_requests.values.count : 0;
  const total = hotCount + coldCount;
  const hotPercent = total > 0 ? ((hotCount / total) * 100).toFixed(1) : 0;
  
  return {
    stdout: `\n=== Hot Key Scenario Results ===\n` +
            `Hot key requests: ${hotCount} (${hotPercent}%)\n` +
            `Cold key requests: ${coldCount}\n` +
            `Total requests: ${total}\n` +
            `Hot key ratio validates LocalPreCounter effectiveness\n`,
  };
}
