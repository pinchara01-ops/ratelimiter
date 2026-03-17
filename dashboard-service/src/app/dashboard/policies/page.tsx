"use client";

import { useCallback, useEffect, useState } from "react";
import type { PolicyDto } from "@/lib/config-client";

const ALGORITHMS = ["FIXED_WINDOW", "SLIDING_WINDOW", "TOKEN_BUCKET"] as const;
type Algorithm = typeof ALGORITHMS[number];

const EMPTY: Omit<PolicyDto, "id" | "createdAtMs" | "updatedAtMs"> = {
  limit: 100,
  windowSeconds: 60,
  algorithm: "FIXED_WINDOW",
  priority: 0,
  clientKeyPattern: "",
  endpointPattern: "",
};

export default function PoliciesPage() {
  const [policies, setPolicies] = useState<PolicyDto[]>([]);
  const [loading, setLoading]   = useState(true);
  const [error, setError]       = useState<string | null>(null);

  // form state — showForm controls visibility; editId=null means create mode
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId]     = useState<string | null>(null);
  const [form, setForm]         = useState({ ...EMPTY, id: "" });
  const [saving, setSaving]     = useState(false);
  const [formErr, setFormErr]   = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/policies");
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      setPolicies(data.policies ?? []);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Load failed");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  function openCreate() {
    setEditId(null);
    setForm({ ...EMPTY, id: "" });
    setFormErr(null);
    setShowForm(true);
  }

  function openEdit(p: PolicyDto) {
    setEditId(p.id);
    setForm({
      id: p.id,
      limit: p.limit,
      windowSeconds: p.windowSeconds,
      algorithm: p.algorithm,
      priority: p.priority,
      clientKeyPattern: p.clientKeyPattern,
      endpointPattern: p.endpointPattern,
    });
    setFormErr(null);
    setShowForm(true);
  }

  async function save() {
    setSaving(true);
    setFormErr(null);
    try {
      const url    = editId ? `/api/policies/${editId}` : "/api/policies";
      const method = editId ? "PUT" : "POST";
      const res    = await fetch(url, {
        method,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(form),
      });
      if (!res.ok) {
        const j = await res.json().catch(() => ({}));
        throw new Error(j.error ?? `HTTP ${res.status}`);
      }
      setShowForm(false);
      setEditId(null);
      setForm({ ...EMPTY, id: "" });
      await load();
    } catch (e) {
      setFormErr(e instanceof Error ? e.message : "Save failed");
    } finally {
      setSaving(false);
    }
  }

  async function del(id: string) {
    if (!confirm(`Delete policy "${id}"?`)) return;
    try {
      const res = await fetch(`/api/policies/${id}`, { method: "DELETE" });
      if (!res.ok && res.status !== 204) throw new Error(`HTTP ${res.status}`);
      await load();
    } catch (e) {
      alert(e instanceof Error ? e.message : "Delete failed");
    }
  }

  const algoBadge: Record<Algorithm, string> = {
    FIXED_WINDOW:   "bg-blue-100 text-blue-800",
    SLIDING_WINDOW: "bg-purple-100 text-purple-800",
    TOKEN_BUCKET:   "bg-green-100 text-green-800",
  };

  return (
    <div className="min-h-screen bg-gray-950 text-gray-100 p-8">
      <div className="max-w-6xl mx-auto space-y-8">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold">Policy Management</h1>
            <p className="text-gray-400 text-sm mt-1">Create and manage rate-limit policies (RAT-18)</p>
          </div>
          <button
            onClick={openCreate}
            className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 rounded-lg text-sm font-medium transition-colors"
          >
            + New Policy
          </button>
        </div>

        {/* Create / Edit form — only shown when user explicitly clicks New/Edit */}
        {showForm && (
          <div className="bg-gray-900 border border-gray-700 rounded-xl p-6 space-y-4">
            <h2 className="text-lg font-semibold">{editId ? `Edit: ${editId}` : "Create Policy"}</h2>
            <div className="grid grid-cols-2 gap-4">
              {!editId && (
                <div>
                  <label className="block text-xs text-gray-400 mb-1">ID (leave blank to auto-generate)</label>
                  <input
                    className="w-full bg-gray-800 border border-gray-600 rounded px-3 py-2 text-sm"
                    value={form.id}
                    onChange={e => setForm(f => ({ ...f, id: e.target.value }))}
                    placeholder="e.g. premium-tier"
                  />
                </div>
              )}
              <div>
                <label className="block text-xs text-gray-400 mb-1">Limit (req / window)</label>
                <input
                  type="number" min={1}
                  className="w-full bg-gray-800 border border-gray-600 rounded px-3 py-2 text-sm"
                  value={form.limit}
                  onChange={e => setForm(f => ({ ...f, limit: Number(e.target.value) }))}
                />
              </div>
              <div>
                <label className="block text-xs text-gray-400 mb-1">Window (seconds)</label>
                <input
                  type="number" min={1}
                  className="w-full bg-gray-800 border border-gray-600 rounded px-3 py-2 text-sm"
                  value={form.windowSeconds}
                  onChange={e => setForm(f => ({ ...f, windowSeconds: Number(e.target.value) }))}
                />
              </div>
              <div>
                <label className="block text-xs text-gray-400 mb-1">Algorithm</label>
                <select
                  className="w-full bg-gray-800 border border-gray-600 rounded px-3 py-2 text-sm"
                  value={form.algorithm}
                  onChange={e => setForm(f => ({ ...f, algorithm: e.target.value as Algorithm }))}
                >
                  {ALGORITHMS.map(a => <option key={a} value={a}>{a.replace(/_/g, " ")}</option>)}
                </select>
              </div>
              <div>
                <label className="block text-xs text-gray-400 mb-1">Priority (higher = matched first)</label>
                <input
                  type="number"
                  className="w-full bg-gray-800 border border-gray-600 rounded px-3 py-2 text-sm"
                  value={form.priority}
                  onChange={e => setForm(f => ({ ...f, priority: Number(e.target.value) }))}
                />
              </div>
              <div>
                <label className="block text-xs text-gray-400 mb-1">Client key pattern (regex, blank = any)</label>
                <input
                  className="w-full bg-gray-800 border border-gray-600 rounded px-3 py-2 text-sm font-mono"
                  value={form.clientKeyPattern}
                  onChange={e => setForm(f => ({ ...f, clientKeyPattern: e.target.value }))}
                  placeholder="e.g. premium-.*"
                />
              </div>
              <div>
                <label className="block text-xs text-gray-400 mb-1">Endpoint pattern (regex, blank = any)</label>
                <input
                  className="w-full bg-gray-800 border border-gray-600 rounded px-3 py-2 text-sm font-mono"
                  value={form.endpointPattern}
                  onChange={e => setForm(f => ({ ...f, endpointPattern: e.target.value }))}
                  placeholder="e.g. /api/search.*"
                />
              </div>
            </div>
            {formErr && <p className="text-red-400 text-sm">{formErr}</p>}
            <div className="flex gap-3">
              <button
                onClick={save}
                disabled={saving}
                className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 rounded-lg text-sm font-medium transition-colors"
              >
                {saving ? "Saving…" : editId ? "Update" : "Create"}
              </button>
              <button
                onClick={() => { setShowForm(false); setEditId(null); }}
                className="px-4 py-2 bg-gray-700 hover:bg-gray-600 rounded-lg text-sm transition-colors"
              >
                Cancel
              </button>
            </div>
          </div>
        )}

        {/* Policy table */}
        {loading ? (
          <p className="text-gray-400">Loading…</p>
        ) : error ? (
          <p className="text-red-400">{error}</p>
        ) : policies.length === 0 ? (
          <p className="text-gray-500">No policies yet. Click "New Policy" to create one.</p>
        ) : (
          <div className="bg-gray-900 border border-gray-700 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-800 text-gray-400 text-xs uppercase">
                <tr>
                  {["ID", "Algorithm", "Limit", "Window", "Priority", "Client pattern", "Endpoint pattern", ""].map(h => (
                    <th key={h} className="px-4 py-3 text-left font-medium">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-800">
                {policies.map(p => (
                  <tr key={p.id} className="hover:bg-gray-800/50 transition-colors">
                    <td className="px-4 py-3 font-mono text-indigo-400">{p.id}</td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${algoBadge[p.algorithm]}`}>
                        {p.algorithm.replace(/_/g, " ")}
                      </span>
                    </td>
                    <td className="px-4 py-3 font-mono">{p.limit.toLocaleString()}</td>
                    <td className="px-4 py-3 text-gray-400">{p.windowSeconds}s</td>
                    <td className="px-4 py-3 text-gray-400">{p.priority}</td>
                    <td className="px-4 py-3 font-mono text-xs text-gray-400">{p.clientKeyPattern || <span className="text-gray-600">any</span>}</td>
                    <td className="px-4 py-3 font-mono text-xs text-gray-400">{p.endpointPattern || <span className="text-gray-600">any</span>}</td>
                    <td className="px-4 py-3">
                      <div className="flex gap-2">
                        <button
                          onClick={() => openEdit(p)}
                          className="px-2 py-1 bg-gray-700 hover:bg-gray-600 rounded text-xs transition-colors"
                        >Edit</button>
                        {p.id !== "default" && (
                          <button
                            onClick={() => del(p.id)}
                            className="px-2 py-1 bg-red-900/50 hover:bg-red-800 rounded text-xs text-red-400 transition-colors"
                          >Delete</button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Nav back */}
        <a href="/dashboard" className="text-indigo-400 hover:text-indigo-300 text-sm">← Back to overview</a>
      </div>
    </div>
  );
}
