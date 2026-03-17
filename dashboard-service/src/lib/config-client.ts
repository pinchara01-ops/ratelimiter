import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";
import path from "path";

// Proto bundled inside dashboard-service so it's available in all deployment targets
// (Vercel / Docker — the sibling config-service source is not present at runtime).
const PROTO_PATH = path.resolve(process.cwd(), "src/proto/config.proto");
const CONFIG_URL = process.env.CONFIG_GRPC_URL ?? "localhost:50053";
const DEADLINE_MS = 5_000;

// ── Lazy initialisation: proto is loaded only on first call, not at import time.
// This prevents ENOENT boot failures in test environments where the file may be absent.

let _configPkg: any = null;
function getPkg(): any {
  if (!_configPkg) {
    const pkgDef = protoLoader.loadSync(PROTO_PATH, {
      keepCase: false,
      longs: String,
      enums: String,
      defaults: true,
      oneofs: true,
    });
    _configPkg = (grpc.loadPackageDefinition(pkgDef) as any)
      .com.rateforge.config.grpc.proto;
  }
  return _configPkg;
}

let _client: any = null;
function getClient(): any {
  if (!_client) {
    _client = new (getPkg().ConfigService)(
      CONFIG_URL,
      grpc.credentials.createInsecure()
    );
  }
  return _client;
}

// ── Types

export interface PolicyDto {
  id: string;
  limit: number;
  windowSeconds: number;
  algorithm: "FIXED_WINDOW" | "SLIDING_WINDOW" | "TOKEN_BUCKET";
  priority: number;
  clientKeyPattern: string;
  endpointPattern: string;
  createdAtMs: string;
  updatedAtMs: string;
}

// ── Explicit per-method wrappers — no dynamic string dispatch so method name
//    typos are caught at compile time rather than as runtime TypeErrors.

export function listPolicies(): Promise<{ policies: PolicyDto[] }> {
  return new Promise((resolve, reject) => {
    const deadline = new Date(Date.now() + DEADLINE_MS);
    getClient().listPolicies({}, { deadline }, (err: Error | null, res: { policies: PolicyDto[] }) => {
      if (err) reject(err); else resolve(res);
    });
  });
}

export function createPolicy(
  policy: Partial<PolicyDto>
): Promise<{ policy: PolicyDto }> {
  return new Promise((resolve, reject) => {
    const deadline = new Date(Date.now() + DEADLINE_MS);
    getClient().createPolicy({ policy }, { deadline }, (err: Error | null, res: { policy: PolicyDto }) => {
      if (err) reject(err); else resolve(res);
    });
  });
}

export function updatePolicy(
  policy: Partial<PolicyDto> & { id: string }
): Promise<{ policy: PolicyDto }> {
  return new Promise((resolve, reject) => {
    const deadline = new Date(Date.now() + DEADLINE_MS);
    getClient().updatePolicy({ policy }, { deadline }, (err: Error | null, res: { policy: PolicyDto }) => {
      if (err) reject(err); else resolve(res);
    });
  });
}

export function deletePolicy(id: string): Promise<{ deleted: boolean }> {
  return new Promise((resolve, reject) => {
    const deadline = new Date(Date.now() + DEADLINE_MS);
    getClient().deletePolicy({ id }, { deadline }, (err: Error | null, res: { deleted: boolean }) => {
      if (err) reject(err); else resolve(res);
    });
  });
}
