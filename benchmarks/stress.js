/**
 * RAT-22 — Stress test: sustained 1000 VUs for 5 min.
 * Validates performance SLOs under constant load.
 */
import grpc from "k6/net/grpc";
import { check } from "k6";

export const options = {
  vus: 1000,
  duration: "5m",
  thresholds: {
    grpc_req_duration: ["p(50)<2", "p(95)<5", "p(99)<10"],
    grpc_req_failed:   ["rate<0.001"],
  },
};

const client = new grpc.Client();
client.load(["../proto/src/main/proto"], "ratelimiter.proto");

export default function () {
  client.connect("localhost:9090", { plaintext: true });
  const res = client.invoke("rateforge.v1.RateLimiterService/CheckLimit", {
    client_id: `stress-${__VU % 1000}`,
    endpoint: "/api/benchmark",
    method: "GET",
    cost: 1,
  });
  check(res, { "status OK": r => r && r.status === grpc.StatusOK });
  client.close();
}
