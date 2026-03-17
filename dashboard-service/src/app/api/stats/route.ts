/**
 * GET /api/stats?timeRange=ONE_HOUR&policyId=...&clientKey=...
 *
 * Server-side proxy to AnalyticsService.GetUsageStats gRPC (RAT-15 / RAT-17).
 */
import { NextRequest, NextResponse } from "next/server";
import { getUsageStats } from "@/lib/grpc-client";
import type { TimeRange } from "@/lib/types";

const VALID_RANGES: TimeRange[] = [
  "ONE_HOUR",
  "SIX_HOURS",
  "ONE_DAY",
  "SEVEN_DAYS",
  "THIRTY_DAYS",
];

export async function GET(req: NextRequest) {
  const { searchParams } = req.nextUrl;
  const timeRange = (searchParams.get("timeRange") ?? "ONE_HOUR") as TimeRange;
  const policyId = searchParams.get("policyId") ?? "";
  const clientKey = searchParams.get("clientKey") ?? "";

  if (!VALID_RANGES.includes(timeRange)) {
    return NextResponse.json({ error: "Invalid timeRange" }, { status: 400 });
  }

  try {
    const stats = await getUsageStats(policyId, clientKey, timeRange);
    return NextResponse.json(stats);
  } catch (err) {
    console.error("[/api/stats] gRPC error:", err);
    return NextResponse.json(
      { error: "Failed to fetch usage stats" },
      { status: 502 }
    );
  }
}
