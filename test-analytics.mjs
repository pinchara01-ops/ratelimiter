/**
 * Direct gRPC call to analytics-service (port 50052) to debug why /api/stats returns zeros.
 */
import grpc from "./dashboard-service/node_modules/@grpc/grpc-js/build/src/index.js";
import protoLoader from "./dashboard-service/node_modules/@grpc/proto-loader/build/src/index.js";
import { fileURLToPath } from "url";
import path from "path";

const __dir = path.dirname(fileURLToPath(import.meta.url));
const PROTO = path.join(__dir, "dashboard-service/src/proto/analytics.proto");

const pkgDef = protoLoader.loadSync(PROTO, {
  keepCase: true, longs: String, enums: String, defaults: true, oneofs: true,
});
const proto = grpc.loadPackageDefinition(pkgDef);

// Find service
const svcDef =
  proto?.rateforge?.analytics?.AnalyticsService ??
  proto?.rateforge?.v1?.AnalyticsService ??
  proto?.AnalyticsService;

if (!svcDef) {
  console.error("Could not find AnalyticsService. Keys:", JSON.stringify(Object.keys(proto)));
  process.exit(1);
}

const client = new svcDef("127.0.0.1:50052", grpc.credentials.createInsecure());

// GetUsageStats — no filters, THIRTY_DAYS
console.log("=== GetUsageStats (no filter, THIRTY_DAYS) ===");
await new Promise((res, rej) => {
  client.GetUsageStats(
    { policy_id: "", client_id: "", time_range: "THIRTY_DAYS" },
    (err, resp) => {
      if (err) { console.error("ERROR:", err.message); rej(err); return; }
      console.log("Response:", JSON.stringify(resp, null, 2));
      res();
    }
  );
}).catch(() => {});

// GetTopClients — ONE_HOUR, limit 10
console.log("\n=== GetTopClients (ONE_HOUR, limit=10) ===");
await new Promise((res, rej) => {
  client.GetTopClients(
    { time_range: "ONE_HOUR", limit: 10 },
    (err, resp) => {
      if (err) { console.error("ERROR:", err.message); rej(err); return; }
      console.log("Response:", JSON.stringify(resp, null, 2));
      res();
    }
  );
}).catch(() => {});

client.close();
