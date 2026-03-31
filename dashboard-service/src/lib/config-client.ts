import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";
import path from "path";

// Proto bundled inside dashboard-service — available in all deployment targets
const PROTO_PATH = path.resolve(process.cwd(), "src/proto/config.proto");
// server/ module exposes ConfigService on the same port as RateLimiterService (9090)
const CONFIG_URL = process.env.CONFIG_GRPC_URL ?? "127.0.0.1:9090";
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
// Import types for local use, then re-export for consumers
import type {
  AlgorithmType,
  NoMatchBehavior,
  PolicyDto,
} from "./config-types";
export type { AlgorithmType, NoMatchBehavior, PolicyDto };
export { algorithmLabel } from "./config-types";

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
