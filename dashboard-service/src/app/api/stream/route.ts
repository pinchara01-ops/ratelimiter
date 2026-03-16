/**
 * GET /api/stream?policyId=...&clientKey=...&decision=ALL
 *
 * SSE endpoint wrapping AnalyticsService.StreamDecisions gRPC server-streaming (RAT-16).
 *
 * Each SSE event is a serialised DecisionEvent JSON object.
 * The stream stays open until the client disconnects.
 */
import { NextRequest } from "next/server";
import { streamDecisions } from "@/lib/grpc-client";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(req: NextRequest) {
  const { searchParams } = req.nextUrl;
  const policyId = searchParams.get("policyId") ?? "";
  const clientKey = searchParams.get("clientKey") ?? "";
  const decision = searchParams.get("decision") ?? "ALL";
  const filterByDecision = decision !== "ALL";
  const decisionFilter = filterByDecision ? decision : "ALLOW";

  const encoder = new TextEncoder();

  const stream = new ReadableStream({
    start(controller) {
      // Singleton client — do NOT call client.close() here; it's shared across requests
      const call = streamDecisions(policyId, clientKey, decisionFilter, filterByDecision);

      call.on("data", (event: unknown) => {
        const payload = `data: ${JSON.stringify(event)}\n\n`;
        controller.enqueue(encoder.encode(payload));
      });

      call.on("error", (err: Error) => {
        console.error("[/api/stream] gRPC stream error:", err.message);
        controller.enqueue(
          encoder.encode(`event: error\ndata: ${err.message}\n\n`)
        );
        controller.close();
      });

      call.on("end", () => {
        controller.close();
      });

      // Cancel this call (not the shared channel) when the browser disconnects
      req.signal.addEventListener("abort", () => {
        call.cancel();
        controller.close();
      });
    },
  });

  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
    },
  });
}
