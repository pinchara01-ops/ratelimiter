"use client";
import { useState } from "react";

interface SimResult {
  totalRequests: number; allowedCount: number; deniedCount: number; denyRate: number;
  p50Ms: number; p95Ms: number; p99Ms: number;
  windowBuckets: { label: string; allowed: number; denied: number }[];
}

function simulate(rps: number, durationSec: number, limit: number, windowMs: number, algorithm: string): SimResult {
  const total = Math.round(rps * durationSec);
  const windows = Math.ceil((durationSec * 1000) / windowMs);
  const rpw = total / windows;
  let allowed = 0; let denied = 0;
  const buckets: {label:string;allowed:number;denied:number}[] = [];

  for (let w = 0; w < windows; w++) {
    const inW = Math.round(rpw);
    let a = 0; let d = 0;
    if (algorithm === "TOKEN_BUCKET") {
      // simple token bucket approximation per window
      let tokens = w === 0 ? limit : limit * 0.5;
      const refill = limit / Math.max(inW, 1);
      for (let i = 0; i < inW; i++) {
        tokens = Math.min(limit, tokens + refill);
        if (tokens >= 1) { tokens -= 1; a++; } else { d++; }
      }
    } else {
      const effectiveLimit = algorithm === "SLIDING_WINDOW" ? Math.round(limit * 1.05) : limit;
      a = Math.min(inW, effectiveLimit);
      d = Math.max(0, inW - effectiveLimit);
    }
    allowed += a; denied += d;
    buckets.push({ label: "W" + (w + 1), allowed: a, denied: d });
  }

  const denyRate = total > 0 ? denied / total : 0;
  const lat = 1 + denyRate * 0.5;
  return {
    totalRequests: total, allowedCount: allowed, deniedCount: denied, denyRate,
    p50Ms: parseFloat((lat * 2.1).toFixed(2)),
    p95Ms: parseFloat((lat * 8.4).toFixed(2)),
    p99Ms: parseFloat((lat * 18.7).toFixed(2)),
    windowBuckets: buckets.slice(0, 20),
  };
}

export default function SimulatorPage() {
  const [rps, setRps] = useState(200);
  const [duration, setDuration] = useState(60);
  const [limit, setLimit] = useState(100);
  const [windowMs, setWindowMs] = useState(60000);
  const [algorithm, setAlgorithm] = useState("FIXED_WINDOW");
  const [result, setResult] = useState<SimResult | null>(null);

  function run() { setResult(simulate(rps, duration, limit, windowMs, algorithm)); }

  const maxBucket = result ? Math.max(...result.windowBuckets.map(b => b.allowed + b.denied), 1) : 1;

  return (
    <div className="min-h-screen bg-gray-950 text-gray-100 p-8">
      <div className="max-w-5xl mx-auto">
        <h1 className="text-2xl font-bold text-white mb-2">Policy Simulator</h1>
        <p className="text-gray-400 text-sm mb-8">Model what-if deny rates before deploying a policy change.</p>
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4 mb-6">
          <label className="block"><span className="text-xs text-gray-400 mb-1 block">Requests / sec</span>
            <input type="number" min={1} value={rps} onChange={e => setRps(Number(e.target.value))} className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-2 text-sm"/></label>
          <label className="block"><span className="text-xs text-gray-400 mb-1 block">Duration (sec)</span>
            <input type="number" min={1} value={duration} onChange={e => setDuration(Number(e.target.value))} className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-2 text-sm"/></label>
          <label className="block"><span className="text-xs text-gray-400 mb-1 block">Rate limit (req/window)</span>
            <input type="number" min={1} value={limit} onChange={e => setLimit(Number(e.target.value))} className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-2 text-sm"/></label>
          <label className="block"><span className="text-xs text-gray-400 mb-1 block">Window (ms)</span>
            <input type="number" min={100} value={windowMs} onChange={e => setWindowMs(Number(e.target.value))} className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-2 text-sm"/></label>
          <label className="block"><span className="text-xs text-gray-400 mb-1 block">Algorithm</span>
            <select value={algorithm} onChange={e => setAlgorithm(e.target.value)} className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-2 text-sm">
              <option value="FIXED_WINDOW">Fixed Window</option>
              <option value="SLIDING_WINDOW">Sliding Window</option>
              <option value="TOKEN_BUCKET">Token Bucket</option>
            </select></label>
        </div>
        <button onClick={run} className="bg-blue-600 hover:bg-blue-700 text-white px-6 py-2 rounded-lg text-sm font-medium mb-8">Run Simulation</button>
        {result && (
          <div className="space-y-6">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <div className="bg-gray-900 rounded-xl border border-gray-800 p-4"><p className="text-xs text-gray-400 mb-1">Total Requests</p><p className="text-2xl font-bold text-white">{result.totalRequests.toLocaleString()}</p></div>
              <div className="bg-gray-900 rounded-xl border border-gray-800 p-4"><p className="text-xs text-gray-400 mb-1">Allowed</p><p className="text-2xl font-bold text-green-400">{result.allowedCount.toLocaleString()}</p></div>
              <div className="bg-gray-900 rounded-xl border border-gray-800 p-4"><p className="text-xs text-gray-400 mb-1">Denied</p><p className="text-2xl font-bold text-red-400">{result.deniedCount.toLocaleString()}</p></div>
              <div className="bg-gray-900 rounded-xl border border-gray-800 p-4"><p className="text-xs text-gray-400 mb-1">Deny Rate</p>
                <p className={(result.denyRate > 0.5 ? "text-red-400" : result.denyRate > 0.2 ? "text-yellow-400" : "text-green-400") + " text-2xl font-bold"}>{(result.denyRate * 100).toFixed(1)}%</p></div>
            </div>
            <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
              <h3 className="text-sm font-medium mb-4 text-gray-300">Estimated Latency (single-node model)</h3>
              <div className="flex gap-8">
                <div><p className="text-xs text-gray-400">p50</p><p className="text-xl font-semibold text-white">{result.p50Ms} ms</p></div>
                <div><p className="text-xs text-gray-400">p95</p><p className="text-xl font-semibold text-white">{result.p95Ms} ms</p></div>
                <div><p className="text-xs text-gray-400">p99</p><p className="text-xl font-semibold text-white">{result.p99Ms} ms</p></div>
              </div>
            </div>
            <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
              <h3 className="text-sm font-medium mb-4 text-gray-300">Per-Window Breakdown (first 20 windows)</h3>
              <div className="flex items-end gap-1 h-32">
                {result.windowBuckets.map(b => {
                  const tot = b.allowed + b.denied;
                  const h = Math.round((tot / maxBucket) * 100);
                  const dh = tot > 0 ? Math.round((b.denied / tot) * h) : 0;
                  return (
                    <div key={b.label} className="flex-1 flex flex-col justify-end" title={b.label + ": " + b.allowed + " allowed, " + b.denied + " denied"}>
                      {dh > 0 && <div className="bg-red-500/70 rounded-t" style={{height: dh + "%"}}/>}
                      {(h - dh) > 0 && <div className="bg-green-500/70" style={{height: (h - dh) + "%"}}/>}
                    </div>
                  );
                })}
              </div>
              <div className="flex gap-4 mt-2 text-xs text-gray-400">
                <span className="flex items-center gap-1"><span className="w-2 h-2 bg-green-500/70 rounded inline-block"/>Allowed</span>
                <span className="flex items-center gap-1"><span className="w-2 h-2 bg-red-500/70 rounded inline-block"/>Denied</span>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
