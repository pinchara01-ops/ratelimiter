/**
 * RAT-22 — Smoke test: 10 VUs for 30 s.
 * Verifies the stack is up and responding correctly.
 */
import grpc from "k6/net/grpc";
import { check, sleep } from "k6";

export const options = {
  vus: 10,
  duration: "30s",
  thresholds: {
    grpc_req_duration: ["p(95)<20"],
    checks: ["rate>0.99"],
  },
};

const client = new grpc.Client();
client.load(["../proto/src/main/proto"], "ratelimiter.proto");

export default function () {
  client.connect("localhost:9090", { plaintext: true });

  const res = client.invoke("rateforge.v1.RateLimiterService/CheckLimit", {
    client_id: `smoke-${__VU}`,
    endpoint: "/api/test",
    method: "GET",
    cost: 1,
  });

  check(res, {
    "status OK":    r => r && r.status === grpc.StatusOK,
    "has allowed":  r => r && r.message && typeof r.message.allowed === "boolean",
  });

  client.close();
  sleep(0.1);
}
