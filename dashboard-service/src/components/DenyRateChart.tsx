"use client";

/**
 * RAT-17: Deny rate over time chart.
 *
 * Polls /api/stats every 5 s and accumulates data points client-side,
 * building a rolling time-series for the area chart.
 */
import { useEffect, useRef, useState } from "react";
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts";
import type { DenyRateDataPoint, TimeRange } from "@/lib/types";

interface Props {
  timeRange: TimeRange;
}

const MAX_POINTS = 30;

function formatTime(ts: number) {
  return new Date(ts).toLocaleTimeString("en-US", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
}

export default function DenyRateChart({ timeRange }: Props) {
  const [data, setData] = useState<DenyRateDataPoint[]>([]);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchPoint = async () => {
    try {
      const res = await fetch(`/api/stats?timeRange=${timeRange}`);
      if (!res.ok) return;
      const stats = await res.json();
      const point: DenyRateDataPoint = {
        time: formatTime(Date.now()),
        denyRate: parseFloat((stats.denyRate * 100).toFixed(2)),
        totalRequests: stats.totalRequests,
      };
      setData((prev) =>
        [...prev, point].slice(-MAX_POINTS)
      );
    } catch {
      // silently ignore — chart just doesn't update
    }
  };

  useEffect(() => {
    setData([]);
    fetchPoint();
    intervalRef.current = setInterval(fetchPoint, 5_000);
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [timeRange]);

  return (
    <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
      <h2 className="mb-4 text-sm font-semibold uppercase tracking-wide text-gray-500">
        Deny Rate Over Time
      </h2>
      {data.length === 0 ? (
        <div className="flex h-48 items-center justify-center text-sm text-gray-400">
          Waiting for data…
        </div>
      ) : (
        <ResponsiveContainer width="100%" height={220}>
          <AreaChart data={data}>
            <defs>
              <linearGradient id="denyGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="#ef4444" stopOpacity={0.3} />
                <stop offset="95%" stopColor="#ef4444" stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
            <XAxis
              dataKey="time"
              tick={{ fontSize: 11 }}
              interval="preserveStartEnd"
            />
            <YAxis
              tickFormatter={(v) => `${v}%`}
              domain={[0, 100]}
              tick={{ fontSize: 11 }}
              width={42}
            />
            <Tooltip
              formatter={(v: number) => [`${v}%`, "Deny Rate"]}
              labelStyle={{ fontSize: 12 }}
            />
            <Area
              type="monotone"
              dataKey="denyRate"
              stroke="#ef4444"
              fill="url(#denyGrad)"
              strokeWidth={2}
              dot={false}
            />
          </AreaChart>
        </ResponsiveContainer>
      )}
    </div>
  );
}
