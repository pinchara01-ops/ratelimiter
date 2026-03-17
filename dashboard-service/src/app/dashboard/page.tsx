"use client";

/**
 * RAT-17: Analytics overview dashboard.
 *
 * - Stats cards: total requests, allows, denies, deny rate, avg latency
 * - Deny rate over time chart (polls every 5s)
 * - Top clients table
 * - Time range selector
 */
import { useEffect, useState } from "react";
import StatsCards from "@/components/StatsCards";
import DenyRateChart from "@/components/DenyRateChart";
import TopClientsTable from "@/components/TopClientsTable";
import type { TimeRange, UsageStats } from "@/lib/types";

const TIME_RANGE_OPTIONS: { label: string; value: TimeRange }[] = [
  { label: "1 Hour", value: "ONE_HOUR" },
  { label: "6 Hours", value: "SIX_HOURS" },
  { label: "1 Day", value: "ONE_DAY" },
  { label: "7 Days", value: "SEVEN_DAYS" },
  { label: "30 Days", value: "THIRTY_DAYS" },
];

export default function DashboardPage() {
  const [timeRange, setTimeRange] = useState<TimeRange>("ONE_HOUR");
  const [stats, setStats] = useState<UsageStats | null>(null);
  const [statsLoading, setStatsLoading] = useState(true);

  useEffect(() => {
    setStatsLoading(true);
    fetch(`/api/stats?timeRange=${timeRange}`)
      .then((r) => r.json())
      .then((data) => setStats(data as UsageStats))
      .catch(() => setStats(null))
      .finally(() => setStatsLoading(false));
  }, [timeRange]);

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-800">
            Analytics Overview
          </h1>
          <p className="mt-1 text-sm text-gray-400">
            Aggregated rate limiting decisions
          </p>
        </div>

        {/* Time range selector */}
        <div className="flex rounded-lg border border-gray-200 bg-white shadow-sm">
          {TIME_RANGE_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              onClick={() => setTimeRange(opt.value)}
              className={`px-4 py-2 text-sm font-medium transition-colors first:rounded-l-lg last:rounded-r-lg ${
                timeRange === opt.value
                  ? "bg-indigo-600 text-white"
                  : "text-gray-500 hover:bg-gray-50"
              }`}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* Stats cards */}
      <StatsCards stats={stats} loading={statsLoading} />

      {/* Deny rate chart + top clients side by side on large screens */}
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
        <DenyRateChart timeRange={timeRange} />
        <TopClientsTable timeRange={timeRange} />
      </div>

      {/* Policy management link card */}
      <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-base font-semibold text-gray-800">Rate-Limit Policies</h2>
            <p className="mt-1 text-sm text-gray-400">
              Create, edit, and delete rate-limiting policies (RAT-18)
            </p>
          </div>
          <a
            href="/dashboard/policies"
            className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-indigo-500"
          >
            Manage Policies →
          </a>
        </div>
      </div>
    </div>
  );
}
