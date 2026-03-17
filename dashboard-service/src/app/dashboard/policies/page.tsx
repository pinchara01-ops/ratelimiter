"use client";
import { useCallback, useEffect, useState } from "react";
import type { PolicyDto, AlgorithmType } from "@/lib/config-client";
import { algorithmLabel } from "@/lib/config-client";

const ALGORITHMS: AlgorithmType[] = ["ALGORITHM_TYPE_FIXED_WINDOW","ALGORITHM_TYPE_SLIDING_WINDOW","ALGORITHM_TYPE_TOKEN_BUCKET"];
const ALG_BADGE: Record<string,string> = {
  ALGORITHM_TYPE_FIXED_WINDOW:"bg-blue-900/40 text-blue-300",
  ALGORITHM_TYPE_SLIDING_WINDOW:"bg-purple-900/40 text-purple-300",
  ALGORITHM_TYPE_TOKEN_BUCKET:"bg-green-900/40 text-green-300",
};
const EMPTY = { name:"", clientId:"*", endpoint:"*", method:"*",
  algorithm:"ALGORITHM_TYPE_FIXED_WINDOW" as AlgorithmType,
  limit:100, windowMs:60000, bucketSize:0, refillRate:0,
  cost:1, priority:100, noMatchBehavior:"NO_MATCH_BEHAVIOR_FAIL_OPEN", enabled:true };

export default function PoliciesPage() {
  const [policies, setPolicies] = useState<PolicyDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [form, setForm] = useState({...EMPTY});
  const [saving, setSaving] = useState(false);
  const [formErr, setFormErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const res = await fetch("/api/policies");
      if (!res.ok) throw new Error("HTTP " + res.status);
      const data = await res.json();
      setPolicies(data.policies ?? []);
    } catch (e) { setError(e instanceof Error ? e.message : "Load failed"); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { load(); }, [load]);

  function openCreate() { setEditId(null); setForm({...EMPTY}); setFormErr(null); setShowForm(true); }
  function openEdit(p: PolicyDto) {
    setEditId(p.id);
    setForm({ name:p.name, clientId:p.clientId, endpoint:p.endpoint, method:p.method,
      algorithm:p.algorithm, limit:p.limit, windowMs:p.windowMs, bucketSize:p.bucketSize,
      refillRate:p.refillRate, cost:p.cost, priority:p.priority,
      noMatchBehavior:p.noMatchBehavior, enabled:p.enabled });
    setFormErr(null); setShowForm(true);
  }
  async function save() {
    if (!form.name.trim()) { setFormErr("Name is required"); return; }
    setSaving(true); setFormErr(null);
    try {
      const url = editId ? "/api/policies/" + editId : "/api/policies";
      const res = await fetch(url, { method: editId ? "PUT" : "POST",
        headers:{"Content-Type":"application/json"}, body: JSON.stringify(form) });
      if (!res.ok) { const d = await res.json(); throw new Error(d.error ?? "HTTP " + res.status); }
      setShowForm(false); await load();
    } catch (e) { setFormErr(e instanceof Error ? e.message : "Save failed"); }
    finally { setSaving(false); }
  }
  async function remove(id: string) {
    if (!confirm("Delete this policy?")) return;
    if ((await fetch("/api/policies/" + id, { method:"DELETE" })).ok) await load();
  }

  return (
    <div className="min-h-screen bg-gray-950 text-gray-100 p-8">
      <div className="max-w-6xl mx-auto">
        <div className="flex items-center justify-between mb-8">
          <div>
            <h1 className="text-2xl font-bold text-white">Rate-Limit Policies</h1>
            <p className="text-gray-400 text-sm mt-1">Changes propagate to the rate-limiter within 30 s.</p>
          </div>
          <button onClick={openCreate} className="bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded-lg text-sm font-medium">+ New Policy</button>
        </div>
        {error && <div className="mb-4 bg-red-900/30 border border-red-700 rounded p-3 text-sm text-red-300">{error}</div>}
        {loading ? <p className="text-gray-500">Loading...</p> : (
          <div className="bg-gray-900 rounded-xl border border-gray-800 overflow-hidden">
            <table className="w-full text-sm">
              <thead><tr className="border-b border-gray-800 text-gray-400 text-xs uppercase">
                {["Name","Algorithm","Limit","Window","Priority","Enabled",""].map(h => <th key={h} className="text-left px-4 py-3">{h}</th>)}
              </tr></thead>
              <tbody>
                {policies.map(p => (
                  <tr key={p.id} className="border-b border-gray-800/50 hover:bg-gray-800/30">
                    <td className="px-4 py-3 font-medium">{p.name}</td>
                    <td className="px-4 py-3"><span className={(ALG_BADGE[p.algorithm] ?? "bg-gray-700 text-gray-300") + " px-2 py-0.5 rounded text-xs"}>{algorithmLabel(p.algorithm)}</span></td>
                    <td className="px-4 py-3 text-gray-300">{p.limit.toLocaleString()}</td>
                    <td className="px-4 py-3 text-gray-300">{(p.windowMs/1000).toFixed(0)} s</td>
                    <td className="px-4 py-3 text-gray-300">{p.priority}</td>
                    <td className="px-4 py-3"><span className={(p.enabled ? "bg-green-900/40 text-green-400" : "bg-gray-700 text-gray-400") + " px-2 py-0.5 rounded text-xs"}>{p.enabled ? "Yes" : "No"}</span></td>
                    <td className="px-4 py-3 text-right space-x-2">
                      <button onClick={() => openEdit(p)} className="text-blue-400 hover:text-blue-300 text-xs">Edit</button>
                      <button onClick={() => remove(p.id)} className="text-red-400 hover:text-red-300 text-xs ml-2">Delete</button>
                    </td>
                  </tr>
                ))}
                {policies.length === 0 && <tr><td colSpan={7} className="px-4 py-8 text-center text-gray-500">No policies yet.</td></tr>}
              </tbody>
            </table>
          </div>
        )}
        {showForm && (
          <div className="fixed inset-0 bg-black/60 flex items-center justify-end z-50">
            <div className="bg-gray-900 border-l border-gray-700 w-full max-w-md h-full overflow-y-auto p-6">
              <h2 className="text-lg font-semibold mb-6">{editId ? "Edit Policy" : "New Policy"}</h2>
              {formErr && <p className="mb-4 text-sm text-red-400 bg-red-900/20 rounded p-2">{formErr}</p>}
              <div className="space-y-4">
                {(["name","clientId","endpoint","method"] as const).map(k => (
                  <label key={k} className="block">
                    <span className="text-xs text-gray-400 mb-1 block capitalize">{k}</span>
                    <input value={(form as Record<string,unknown>)[k] as string}
                      onChange={e => setForm(f => ({...f,[k]:e.target.value}))}
                      className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-2 text-sm focus:outline-none focus:border-blue-500"/>
                  </label>
                ))}
                {(["limit","windowMs","priority","cost"] as const).map(k => (
                  <label key={k} className="block">
                    <span className="text-xs text-gray-400 mb-1 block capitalize">{k}</span>
                    <input type="number" value={(form as Record<string,unknown>)[k] as number}
                      onChange={e => setForm(f => ({...f,[k]:Number(e.target.value)}))}
                      className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-2 text-sm"/>
                  </label>
                ))}
                <label className="block">
                  <span className="text-xs text-gray-400 mb-1 block">Algorithm</span>
                  <select value={form.algorithm}
                    onChange={e => setForm(f => ({...f,algorithm:e.target.value as AlgorithmType}))}
                    className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-2 text-sm">
                    {ALGORITHMS.map(a => <option key={a} value={a}>{algorithmLabel(a)}</option>)}
                  </select>
                </label>
                {form.algorithm === "ALGORITHM_TYPE_TOKEN_BUCKET" && (
                  <>
                    <label className="block"><span className="text-xs text-gray-400 mb-1 block">Bucket size</span>
                      <input type="number" value={form.bucketSize}
                        onChange={e => setForm(f => ({...f,bucketSize:Number(e.target.value)}))}
                        className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-2 text-sm"/>
                    </label>
                    <label className="block"><span className="text-xs text-gray-400 mb-1 block">Refill rate (tokens/s)</span>
                      <input type="number" step="0.1" value={form.refillRate}
                        onChange={e => setForm(f => ({...f,refillRate:Number(e.target.value)}))}
                        className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-2 text-sm"/>
                    </label>
                  </>
                )}
                <label className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={form.enabled}
                    onChange={e => setForm(f => ({...f,enabled:e.target.checked}))} className="rounded"/>
                  Enabled
                </label>
              </div>
              <div className="flex gap-3 mt-8">
                <button onClick={save} disabled={saving}
                  className="flex-1 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white py-2 rounded text-sm font-medium">
                  {saving ? "Saving..." : "Save"}
                </button>
                <button onClick={() => setShowForm(false)}
                  className="flex-1 bg-gray-700 hover:bg-gray-600 text-white py-2 rounded text-sm">Cancel</button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
