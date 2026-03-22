/**
 * E2E traffic test: fires 8 CheckLimit calls against the test-strict policy
 * (100 req/min limit) then against e2e-tight (5 req/min limit).
 * Prints ALLOW / DENY + remaining quota for each call.
 */
import grpc from "./dashboard-service/node_modules/@grpc/grpc-js/build/src/index.js";
import protoLoader from "./dashboard-service/node_modules/@grpc/proto-loader/build/src/index.js";
import { fileURLToPath } from "url";
import path from "path";

const __dir = path.dirname(fileURLToPath(import.meta.url));
const PROTO = path.join(__dir, "proto/src/main/proto/ratelimiter.proto");

const pkgDef = protoLoader.loadSync(PROTO, {
  keepCase: true,
  longs: String,
  enums: String,
  defaults: true,
  oneofs: true,
});
const proto = grpc.loadPackageDefinition(pkgDef);

// Find RateLimiterService — it may be nested under rateforge.v1 or top-level
const svcDef = proto?.rateforge?.v1?.RateLimiterService ?? proto?.RateLimiterService;
if (!svcDef) {
  console.error("Could not find RateLimiterService in proto. Keys:", JSON.stringify(Object.keys(proto)));
  process.exit(1);
}

const client = new svcDef("127.0.0.1:9090", grpc.credentials.createInsecure());

function checkLimit(clientId, endpoint, method) {
  return new Promise((resolve, reject) => {
    client.CheckLimit(
      { client_id: clientId, endpoint, method, cost: 1, metadata: {} },
      (err, resp) => {
        if (err) reject(err);
        else resolve(resp);
      }
    );
  });
}

async function burst(label, clientId, endpoint, method, count) {
  console.log(`\n--- ${label} (${count} requests) ---`);
  for (let i = 1; i <= count; i++) {
    try {
      const r = await checkLimit(clientId, endpoint, method);
      const status = r.allowed ? "✅ ALLOW" : "❌ DENY ";
      console.log(`  [${i}] ${status}  remaining=${r.remaining}  policy=${r.policy_id || "(none)"}  reason=${r.reason || ""}`);
    } catch (e) {
      console.log(`  [${i}] ERROR: ${e.message}`);
    }
  }
}

// Test 1: test-strict policy (100/min, only matches GET /api/items)
await burst("test-strict  [GET /api/items — limit=100]", "client-abc", "/api/items", "GET", 5);

// Test 2: e2e-tight (ENABLED, limit=5/min) — fire 8, expect ALLOW x5 then DENY x3
await burst("e2e-tight    [wildcard — limit=5, ENABLED]", "burst-client", "/anything", "POST", 8);

// Test 3: no policy match — should fail-open
await burst("no-match     [/unknown/path — no policy]", "client-xyz", "/unknown/path", "GET", 3);

client.close();
console.log("\nDone.");
