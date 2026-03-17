/**
 * GET /api/top-clients?timeRange=ONE_HOUR&limit=10
 *
 * Server-side proxy to AnalyticsService.GetTopClients gRPC (RAT-15 / RAT-17).
 */
import { NextRequest, NextResponse } from "next/server";
import { getTopClients } from "@/lib/grpc-client";
import type { TimeRange } from "@/lib/types";

export async function GET(req: NextRequest) {
  const { searchParams } = req.nextUrl;
  const timeRange = (searchParams.get("timeRange") ?? "ONE_HOUR") as TimeRange;
  const limit = Math.min(parseInt(searchParams.get("limit") ?? "10", 10), 50);

  try {
    const data = await getTopClients(timeRange, limit);
    return NextResponse.json(data);
  } catch (err) {
    console.error("[/api/top-clients] gRPC error:", err);
    return NextResponse.json(
      { error: "Failed to fetch top clients" },
      { status: 502 }
    );
  }
}
