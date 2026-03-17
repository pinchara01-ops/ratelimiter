import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";
import path from "path";

const PROTO_PATH = path.resolve(process.cwd(), "../config-service/src/main/proto/config.proto");
const CONFIG_URL = process.env.CONFIG_GRPC_URL ?? "localhost:50053";
const DEADLINE_MS = 5_000;

const pkgDef = protoLoader.loadSync(PROTO_PATH, {
  keepCase: false,
  longs: String,
  enums: String,
  defaults: true,
  oneofs: true,
});
const configPkg = (grpc.loadPackageDefinition(pkgDef) as any)
  .com.rateforge.config.grpc.proto;

let _client: any = null;
function getClient(): any {
  if (!_client) {
    _client = new configPkg.ConfigService(
      CONFIG_URL,
      grpc.credentials.createInsecure()
    );
  }
  return _client;
}

function call<T>(method: string, req: unknown): Promise<T> {
  return new Promise((resolve, reject) => {
    const deadline = new Date(Date.now() + DEADLINE_MS);
    getClient()[method](req, { deadline }, (err: Error | null, res: T) => {
      if (err) reject(err);
      else resolve(res);
    });
  });
}

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

export function listPolicies(): Promise<{ policies: PolicyDto[] }> {
  return call("listPolicies", {});
}

export function createPolicy(policy: Partial<PolicyDto>): Promise<{ policy: PolicyDto }> {
  return call("createPolicy", { policy });
}

export function updatePolicy(policy: Partial<PolicyDto> & { id: string }): Promise<{ policy: PolicyDto }> {
  return call("updatePolicy", { policy });
}

export function deletePolicy(id: string): Promise<{ deleted: boolean }> {
  return call("deletePolicy", { id });
}
