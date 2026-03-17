import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";
import path from "path";

// Proto bundled inside dashboard-service — available in all deployment targets
const PROTO_PATH = path.resolve(process.cwd(), "src/proto/config.proto");
// server/ module exposes ConfigService on the same port as RateLimiterService (9090)
const CONFIG_URL = process.env.CONFIG_GRPC_URL ?? "localhost:9090";
const DEADLINE_MS = 5_000;

let _pkg: any = null;
function getPkg(): any {
  if (!_pkg) {
    const pkgDef = protoLoader.loadSync(PROTO_PATH, {
      keepCase: false, longs: String, enums: String, defaults: true, oneofs: true,
    });
    // package = "rateforge.v1"
    _pkg = (grpc.loadPackageDefinition(pkgDef) as any).rateforge.v1;
  }
  return _pkg;
}

let _client: any = null;
function getClient(): any {
  if (!_client) {
    _client = new (getPkg().ConfigService)(CONFIG_URL, grpc.credentials.createInsecure());
  }
  return _client;
}

function call<T>(method: string, req: unknown): Promise<T> {
  return new Promise((resolve, reject) => {
    const deadline = new Date(Date.now() + DEADLINE_MS);
    getClient()[method](req, { deadline }, (err: Error | null, res: T) => {
      if (err) reject(err); else resolve(res);
    });
  });
}

// ── Types (aligned to proto/config.proto PolicyProto) ─────────────────────────

export type AlgorithmType =
  | "ALGORITHM_TYPE_FIXED_WINDOW"
  | "ALGORITHM_TYPE_SLIDING_WINDOW"
  | "ALGORITHM_TYPE_TOKEN_BUCKET";

export type NoMatchBehavior =
  | "NO_MATCH_BEHAVIOR_FAIL_OPEN"
  | "NO_MATCH_BEHAVIOR_FAIL_CLOSED";

export interface PolicyDto {
  id: string;
  name: string;
  clientId: string;
  endpoint: string;
  method: string;
  algorithm: AlgorithmType;
  limit: number;
  windowMs: number;
  bucketSize: number;
  refillRate: number;
  cost: number;
  priority: number;
  noMatchBehavior: NoMatchBehavior;
  enabled: boolean;
  createdAtMs: string;
  updatedAtMs: string;
}

/** Friendly display label for algorithm enum values */
export function algorithmLabel(a: string): string {
  return a.replace("ALGORITHM_TYPE_", "").replace(/_/g, " ");
}

// ── API wrappers ──────────────────────────────────────────────────────────────

export function listPolicies(
  opts: { page?: number; pageSize?: number; enabledOnly?: boolean } = {}
): Promise<{ policies: PolicyDto[]; totalCount: number }> {
  return call("listPolicies", {
    page: opts.page ?? 0,
    pageSize: opts.pageSize ?? 100,
    enabledOnly: opts.enabledOnly ?? false,
  });
}

export function createPolicy(
  req: Omit<PolicyDto, "id" | "createdAtMs" | "updatedAtMs">
): Promise<{ policy: PolicyDto }> {
  return call("createPolicy", req);
}

export function updatePolicy(
  req: Partial<PolicyDto> & { id: string }
): Promise<{ policy: PolicyDto }> {
  return call("updatePolicy", req);
}

export function deletePolicy(id: string): Promise<{ success: boolean; message: string }> {
  return call("deletePolicy", { id });
}

export function getPolicy(id: string): Promise<{ policy: PolicyDto }> {
  return call("getPolicy", { id });
}
