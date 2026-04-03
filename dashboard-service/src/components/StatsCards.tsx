"use client";

import type { UsageStats } from "@/lib/types";

interface Props {
  stats: UsageStats | null;
  loading: boolean;
}

interface CardProps {
  label: string;
  value: string;
  sub?: string;
  accent?: "green" | "red" | "indigo" | "gray";
}

function Card({ label, value, sub, accent = "gray" }: CardProps) {
  const accentMap = {
    green: "border-green-500 bg-green-50",
    red: "border-red-500 bg-red-50",
    indigo: "border-indigo-500 bg-indigo-50",
    gray: "border-gray-300 bg-white",
  };

  return (
    <div
      className={`rounded-xl border-l-4 p-5 shadow-sm ${accentMap[accent]}`}
    >
      <p className="text-xs font-semibold uppercase tracking-wide text-gray-500">
        {label}
      </p>
      <p className="mt-1 text-3xl font-bold text-gray-800">{value}</p>
      {sub && <p className="mt-1 text-xs text-gray-400">{sub}</p>}
    </div>
  );
}

function fmt(n: number) {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

export default function StatsCards({ stats, loading }: Props) {
  if (loading || !stats) {
    return (
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        {Array.from({ length: 5 }).map((_, i) => (
          <div
            key={i}
            className="h-24 animate-pulse rounded-xl bg-gray-200"
          />
        ))}
      </div>
    );
  }

  const denyPct = ((stats.denyRate ?? 0) * 100).toFixed(1);

  return (
    <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
      <Card
        label="Total Requests"
        value={fmt(stats.totalRequests)}
        accent="indigo"
      />
      <Card
        label="Allowed"
        value={fmt(stats.totalAllows)}
        accent="green"
      />
      <Card
        label="Denied"
        value={fmt(stats.totalDenies)}
        accent="red"
      />
      <Card
        label="Deny Rate"
        value={`${denyPct}%`}
        sub="of total traffic"
        accent={stats.denyRate > 0.3 ? "red" : "gray"}
      />
      <Card
        label="Avg Latency"
        value={`${(stats.avgLatencyMs ?? 0).toFixed(2)} ms`}
        sub="per decision"
        accent="gray"
      />
    </div>
  );
}
