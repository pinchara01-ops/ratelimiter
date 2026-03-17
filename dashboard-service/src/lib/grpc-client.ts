import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";
import path from "path";

const ANALYTICS_PROTO = path.resolve(process.cwd(), "src/proto/analytics.proto");
const GRPC_URL = process.env.ANALYTICS_GRPC_URL ?? "localhost:50052";
const DEADLINE_MS = 5_000;

let _analyticsClient: any = null;
function getClient(): any {
  if (!_analyticsClient) {
    const pkgDef = protoLoader.loadSync(ANALYTICS_PROTO, {
      keepCase: false, longs: String, enums: String, defaults: true, oneofs: true,
    });
    const pkg = (grpc.loadPackageDefinition(pkgDef) as any).rateforge.analytics;
    _analyticsClient = new pkg.AnalyticsService(GRPC_URL, grpc.credentials.createInsecure());
  }
  return _analyticsClient;
}

function call<T>(method: string, req: unknown): Promise<T> {
  return new Promise((resolve, reject) => {
    const deadline = new Date(Date.now() + DEADLINE_MS);
    getClient()[method](req, { deadline }, (err: Error | null, res: T) => {
      if (err) reject(err); else resolve(res);
    });
  });
}

export function getUsageStats(
  policyId: string, clientId: string, timeRange: string
): Promise<unknown> {
  return call("getUsageStats", { policyId, clientId, timeRange });
}

export function getTopClients(timeRange: string, limit = 10): Promise<unknown> {
  return call("getTopClients", { timeRange, limit });
}

export function streamDecisions(
  req: { policyId?: string; clientId?: string },
  onEvent: (event: unknown) => void,
  onError: (err: Error) => void,
  onEnd: () => void
): () => void {
  const deadline = new Date(Date.now() + 5 * 60 * 1_000); // 5-min deadline
  const call = getClient().streamDecisions(req, { deadline });
  call.on("data", onEvent);
  call.on("error", onError);
  call.on("end", onEnd);
  return () => call.cancel();
}
