/**
 * RAT-22 — Ramp test: 0 → 1000 VUs over 2 min, hold 3 min, ramp down 1 min.
 * Identifies the throughput ceiling and latency inflection point.
 */
import grpc from "k6/net/grpc";
import { check } from "k6";

export const options = {
  stages: [
    { duration: "2m", target: 1000 },
    { duration: "3m", target: 1000 },
    { duration: "1m", target: 0 },
  ],
  thresholds: {
    grpc_req_duration: ["p(50)<2", "p(95)<5", "p(99)<10"],
    checks: ["rate>0.999"],
  },
};

const client = new grpc.Client();
client.load(["../proto/src/main/proto"], "ratelimiter.proto");

const ENDPOINTS = ["/api/orders", "/api/products", "/api/users", "/api/search"];
const METHODS   = ["GET", "POST", "GET", "GET"];

export default function () {
  client.connect("localhost:9090", { plaintext: true });

  const idx = __VU % ENDPOINTS.length;
  const res = client.invoke("rateforge.v1.RateLimiterService/CheckLimit", {
    client_id: `user-${__VU % 500}`,
    endpoint:  ENDPOINTS[idx],
    method:    METHODS[idx],
    cost: 1,
  });

  check(res, { "status OK": r => r && r.status === grpc.StatusOK });
  client.close();
}
