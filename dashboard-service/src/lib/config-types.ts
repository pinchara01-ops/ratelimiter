// Pure types + helpers — safe to import in Client Components (no Node.js imports)

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
  softLimit?: number; // Soft limit for throttling warnings (0 = disabled)
  createdAtMs: string;
  updatedAtMs: string;
}

/** Friendly display label for algorithm enum values */
export function algorithmLabel(a: string): string {
  return a.replace("ALGORITHM_TYPE_", "").replace(/_/g, " ");
}
