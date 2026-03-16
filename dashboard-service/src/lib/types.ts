// Shared TypeScript types mirroring analytics.proto contracts

export type TimeRange = "ONE_HOUR" | "SIX_HOURS" | "ONE_DAY" | "SEVEN_DAYS" | "THIRTY_DAYS";
export type Algorithm = "FIXED_WINDOW" | "SLIDING_WINDOW" | "TOKEN_BUCKET";
export type DecisionResult = "ALLOW" | "DENY";

// GetUsageStats response
export interface UsageStats {
  policyId: string;
  totalRequests: number;
  totalAllows: number;
  totalDenies: number;
  denyRate: number;       // 0.0 – 1.0
  avgLatencyMs: number;
  timeRange: TimeRange;
}

// StreamDecisions event
export interface DecisionEvent {
  id: string;
  timestampMs: number;
  clientKey: string;
  endpoint: string;
  policyId: string;
  algorithm: Algorithm;
  decision: DecisionResult;
  latencyMs: number;
}

// GetTopClients response
export interface ClientStats {
  clientKey: string;
  totalRequests: number;
  totalAllows: number;
  totalDenies: number;
  denyRatio: number;
}

export interface TopClientsList {
  clients: ClientStats[];
  timeRange: TimeRange;
}

// For chart time-series accumulation (client-side)
export interface DenyRateDataPoint {
  time: string;       // formatted HH:mm:ss
  denyRate: number;   // percentage 0–100
  totalRequests: number;
}
