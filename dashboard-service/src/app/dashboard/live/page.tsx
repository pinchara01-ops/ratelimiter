/**
 * RAT-16: Live request stream page.
 *
 * Renders the LiveFeed component which connects to /api/stream (SSE)
 * wrapping AnalyticsService.StreamDecisions gRPC server-streaming RPC.
 */
import LiveFeed from "@/components/LiveFeed";

export default function LivePage() {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold text-gray-800">Live Decision Feed</h1>
        <p className="mt-1 text-sm text-gray-400">
          Real-time stream of rate limiting decisions — up to 200 most recent
          events
        </p>
      </div>
      <LiveFeed />
    </div>
  );
}
