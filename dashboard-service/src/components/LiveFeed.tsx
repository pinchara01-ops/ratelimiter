"use client";

/**
 * RAT-16: Live decision stream feed.
 *
 * Connects to /api/stream (SSE) which wraps AnalyticsService.StreamDecisions gRPC.
 * Displays incoming events in a scrolling table, newest at the top.
 */
import { useEffect, useRef, useState } from "react";
import type { DecisionEvent } from "@/lib/types";

const MAX_EVENTS = 200;

function Badge({ decision }: { decision: string }) {
  return (
    <span
      className={`inline-block rounded px-2 py-0.5 text-xs font-bold ${
        decision === "ALLOW"
          ? "bg-green-100 text-green-700"
          : "bg-red-100 text-red-700"
      }`}
    >
      {decision}
    </span>
  );
}

function AlgoBadge({ algorithm }: { algorithm: string }) {
  const map: Record<string, string> = {
    FIXED_WINDOW: "bg-blue-100 text-blue-700",
    SLIDING_WINDOW: "bg-purple-100 text-purple-700",
    TOKEN_BUCKET: "bg-amber-100 text-amber-700",
  };
  return (
    <span
      className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
        map[algorithm] ?? "bg-gray-100 text-gray-600"
      }`}
    >
      {algorithm.replace("_", " ")}
    </span>
  );
}

export default function LiveFeed() {
  const [events, setEvents] = useState<DecisionEvent[]>([]);
  const [connected, setConnected] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const esRef = useRef<EventSource | null>(null);

  useEffect(() => {
    const es = new EventSource("/api/stream");
    esRef.current = es;

    es.onopen = () => {
      setConnected(true);
      setErrorMsg(null);
    };

    es.onmessage = (e) => {
      try {
        const event: DecisionEvent = JSON.parse(e.data as string);
        setEvents((prev) => [event, ...prev].slice(0, MAX_EVENTS));
      } catch {
        // ignore malformed frames
      }
    };

    // Application-level error sent by server as: "event: error\ndata: <message>"
    // This IS a MessageEvent so .data is valid here
    es.addEventListener("error", (e) => {
      const msg = (e as MessageEvent).data as string | undefined;
      setErrorMsg(msg ?? "Stream error");
      setConnected(false);
    });

    // Native EventSource connection error — fires a plain Event (no .data)
    es.onerror = () => {
      setConnected(false);
      setErrorMsg("Connection lost — retrying…");
    };

    return () => {
      es.close();
    };
  }, []);

  return (
    <div className="rounded-xl border border-gray-200 bg-white shadow-sm">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-gray-100 px-5 py-4">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-gray-500">
          Live Decision Stream
        </h2>
        <div className="flex items-center gap-2">
          <span
            className={`inline-block h-2 w-2 rounded-full ${
              connected ? "animate-pulse bg-green-500" : "bg-gray-300"
            }`}
          />
          <span className="text-xs text-gray-400">
            {connected ? "Connected" : "Disconnected"}
          </span>
          {events.length > 0 && (
            <span className="ml-2 text-xs text-gray-400">
              {events.length} event{events.length !== 1 ? "s" : ""}
            </span>
          )}
        </div>
      </div>

      {/* Error banner */}
      {errorMsg && (
        <div className="border-b border-red-100 bg-red-50 px-5 py-2 text-xs text-red-600">
          {errorMsg}
        </div>
      )}

      {/* Table */}
      {events.length === 0 ? (
        <div className="flex h-64 items-center justify-center text-sm text-gray-400">
          {connected ? "Waiting for events…" : "Not connected to stream"}
        </div>
      ) : (
        <div className="overflow-auto" style={{ maxHeight: "520px" }}>
          <table className="w-full text-xs">
            <thead className="sticky top-0 bg-gray-50">
              <tr className="border-b border-gray-100 text-left text-xs font-semibold uppercase text-gray-400">
                <th className="px-4 py-2">Time</th>
                <th className="px-4 py-2">Client</th>
                <th className="px-4 py-2">Endpoint</th>
                <th className="px-4 py-2">Policy</th>
                <th className="px-4 py-2">Algorithm</th>
                <th className="px-4 py-2">Decision</th>
                <th className="px-4 py-2 text-right">Latency</th>
              </tr>
            </thead>
            <tbody>
              {events.map((ev) => (
                <tr
                  key={ev.id}
                  className="border-b border-gray-50 hover:bg-gray-50"
                >
                  <td className="px-4 py-2 font-mono text-gray-400">
                    {new Date(ev.timestampMs).toLocaleTimeString("en-US", {
                      hour12: false,
                    })}
                  </td>
                  <td className="px-4 py-2 font-mono text-gray-700">
                    {ev.clientKey}
                  </td>
                  <td className="px-4 py-2 text-gray-500">{ev.endpoint}</td>
                  <td className="px-4 py-2 font-mono text-gray-500">
                    {ev.policyId}
                  </td>
                  <td className="px-4 py-2">
                    <AlgoBadge algorithm={ev.algorithm} />
                  </td>
                  <td className="px-4 py-2">
                    <Badge decision={ev.decision} />
                  </td>
                  <td className="px-4 py-2 text-right font-mono text-gray-500">
                    {ev.latencyMs.toFixed(2)} ms
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
