/**
 * gRPC client factory for AnalyticsService.
 *
 * Uses @grpc/proto-loader to load analytics.proto dynamically, so there's
 * no separate proto-compilation step required in the dashboard build.
 *
 * Used by Next.js API routes (server-side only — never imported in client components).
 */
import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";
import path from "path";
import type { TimeRange } from "./types";

// Resolve proto path relative to the monorepo root
const PROTO_PATH = path.join(
  process.cwd(),
  "..",
  "analytics-service",
  "src",
  "main",
  "proto",
  "analytics.proto"
);

const packageDef = protoLoader.loadSync(PROTO_PATH, {
  keepCase: false,
  longs: Number,
  enums: String,
  defaults: true,
  oneofs: true,
});

const grpcObject = grpc.loadPackageDefinition(packageDef) as Record<string, unknown>;

// Navigate to rateforge.analytics package
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const analyticsPackage = (grpcObject["rateforge"] as any)?.["analytics"] as any;

const GRPC_URL = process.env.ANALYTICS_GRPC_URL ?? "localhost:50052";
const DEADLINE_MS = 5_000; // 5 s timeout on all unary calls

/**
 * Module-level singleton client — gRPC channels are expensive to create.
 * Reusing a single channel enables HTTP/2 multiplexing across concurrent requests.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
let _client: any = null;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function getClient(): any {
  if (!_client) {
    _client = new analyticsPackage.AnalyticsService(
      GRPC_URL,
      grpc.credentials.createInsecure()
    );
  }
  return _client;
}

/** Promisified GetUsageStats — 5 s deadline */
export function getUsageStats(
  policyId: string,
  clientKey: string,
  timeRange: TimeRange
): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const deadline = new Date(Date.now() + DEADLINE_MS);
    getClient().getUsageStats(
      { policyId, clientKey, timeRange },
      { deadline },
      (err: grpc.ServiceError | null, response: unknown) => {
        if (err) reject(err);
        else resolve(response);
      }
    );
  });
}

/** Promisified GetTopClients — 5 s deadline */
export function getTopClients(
  timeRange: TimeRange,
  limit: number
): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const deadline = new Date(Date.now() + DEADLINE_MS);
    getClient().getTopClients(
      { timeRange, limit },
      { deadline },
      (err: grpc.ServiceError | null, response: unknown) => {
        if (err) reject(err);
        else resolve(response);
      }
    );
  });
}

/**
 * Open a server-streaming StreamDecisions call.
 * Returns the gRPC call object so the caller can cancel it and pipe events out.
 */
export function streamDecisions(
  policyId: string,
  clientKey: string,
  decisionFilter: string,
  filterByDecision: boolean
) {
  return getClient().streamDecisions({
    policyId,
    clientKey,
    decisionFilter,
    filterByDecision,
  });
}
