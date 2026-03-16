"use client";

import type { ClientStats, TimeRange } from "@/lib/types";
import { useEffect, useState } from "react";

interface Props {
  timeRange: TimeRange;
}

export default function TopClientsTable({ timeRange }: Props) {
  const [clients, setClients] = useState<ClientStats[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    fetch(`/api/top-clients?timeRange=${timeRange}&limit=10`)
      .then((r) => r.json())
      .then((data) => setClients(data.clients ?? []))
      .catch(() => setClients([]))
      .finally(() => setLoading(false));
  }, [timeRange]);

  return (
    <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
      <h2 className="mb-4 text-sm font-semibold uppercase tracking-wide text-gray-500">
        Top Clients by Volume
      </h2>

      {loading ? (
        <div className="space-y-2">
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="h-8 animate-pulse rounded bg-gray-100" />
          ))}
        </div>
      ) : clients.length === 0 ? (
        <p className="py-8 text-center text-sm text-gray-400">No data yet</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-100 text-left text-xs font-semibold uppercase text-gray-400">
                <th className="pb-2 pr-4">#</th>
                <th className="pb-2 pr-4">Client Key</th>
                <th className="pb-2 pr-4 text-right">Requests</th>
                <th className="pb-2 pr-4 text-right">Allowed</th>
                <th className="pb-2 pr-4 text-right">Denied</th>
                <th className="pb-2 text-right">Deny %</th>
              </tr>
            </thead>
            <tbody>
              {clients.map((c, idx) => (
                <tr
                  key={c.clientKey}
                  className="border-b border-gray-50 hover:bg-gray-50"
                >
                  <td className="py-2 pr-4 text-gray-400">{idx + 1}</td>
                  <td className="py-2 pr-4 font-mono text-xs text-gray-700">
                    {c.clientKey}
                  </td>
                  <td className="py-2 pr-4 text-right text-gray-700">
                    {c.totalRequests.toLocaleString()}
                  </td>
                  <td className="py-2 pr-4 text-right text-green-600">
                    {c.totalAllows.toLocaleString()}
                  </td>
                  <td className="py-2 pr-4 text-right text-red-500">
                    {c.totalDenies.toLocaleString()}
                  </td>
                  <td className="py-2 text-right">
                    <span
                      className={`inline-block rounded-full px-2 py-0.5 text-xs font-semibold ${
                        c.denyRatio > 0.5
                          ? "bg-red-100 text-red-700"
                          : c.denyRatio > 0.2
                          ? "bg-yellow-100 text-yellow-700"
                          : "bg-green-100 text-green-700"
                      }`}
                    >
                      {(c.denyRatio * 100).toFixed(1)}%
                    </span>
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
