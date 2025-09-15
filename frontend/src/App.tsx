import { useEffect, useMemo, useRef, useState } from "react";
import { BudgetCharts } from "./Charts";

const API = import.meta.env.VITE_API_BASE_URL as string;

type BudgetItem = { category: string; monthly_limit: number };
type Insight = { category: string; monthly_limit: number; spent: number; status: "OK"|"WARNING"|"OVER" };
type BudgetUpsert = { client_id: string; items: BudgetItem[] };

function uuid(): string {
  // Use browser UUID if available
  // @ts-ignore
  if (crypto?.randomUUID) return crypto.randomUUID();
  // fallback
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, c => {
    const r = (Math.random() * 16) | 0,
      v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

function useClientId() {
  const [id, setId] = useState("");
  useEffect(() => {
    let cid = localStorage.getItem("client_id");
    if (!cid) {
      cid = uuid();
      localStorage.setItem("client_id", cid);
    }
    setId(cid);
  }, []);
  return id;
}

type Toast = { id: number; kind: "ok" | "err"; msg: string };

function CategoryPicker({
  value,
  onChange,
  options,
}: {
  value: string;
  onChange: (v: string) => void;
  options: string[];
}) {
  const [open, setOpen] = useState(false);
  const [hl, setHl] = useState(0);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const opts = useMemo(
    () => Array.from(new Set(options)).sort((a, b) => a.localeCompare(b)),
    [options]
  );

  const filtered = useMemo(() => {
    const v = value.trim().toLowerCase();
    if (!v) return opts.slice(0, 8);
    return opts.filter(o => o.toLowerCase().includes(v)).slice(0, 8);
  }, [opts, value]);

  const showAddRow =
    value.trim().length > 0 &&
    !opts.some(o => o.toLowerCase() === value.trim().toLowerCase());

  function select(v: string) {
    onChange(v);
    setOpen(false);
    inputRef.current?.focus();
  }

  // close on outside click
  useEffect(() => {
    function onDoc(e: MouseEvent) {
      if (!wrapRef.current) return;
      if (!wrapRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);

  // reset highlight when options change/open toggles
  useEffect(() => setHl(0), [value, open]);

  return (
    <div className="combo" ref={wrapRef}>
      <input
        ref={inputRef}
        className="input"
        placeholder="e.g., Groceries"
        value={value}
        onFocus={() => setOpen(true)}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (!open && (e.key === "ArrowDown" || e.key === "Enter")) setOpen(true);
          const total = filtered.length + (showAddRow ? 1 : 0);
          if (!open || total === 0) return;
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setHl(i => (i + 1) % total);
          } else if (e.key === "ArrowUp") {
            e.preventDefault();
            setHl(i => (i - 1 + total) % total);
          } else if (e.key === "Enter") {
            e.preventDefault();
            if (hl < filtered.length) select(filtered[hl]);
            else if (showAddRow) select(value.trim());
          } else if (e.key === "Escape") {
            setOpen(false);
          }
        }}
      />
      <button
        type="button"
        className="combo-toggle"
        aria-label="Toggle categories"
        onClick={() => setOpen(o => !o)}
      >
        ▾
      </button>

      {open && (filtered.length > 0 || showAddRow) && (
        <ul className="combo-list" role="listbox">
          {filtered.map((opt, i) => (
            <li
              key={opt}
              role="option"
              className={`combo-item ${i === hl ? "hl" : ""}`}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => select(opt)}
              onMouseEnter={() => setHl(i)}
            >
              {opt}
            </li>
          ))}
          {showAddRow && (
            <li
              role="option"
              className={`combo-item add ${hl === filtered.length ? "hl" : ""}`}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => select(value.trim())}
              onMouseEnter={() => setHl(filtered.length)}
            >
              + Add “{value.trim()}”
            </li>
          )}
        </ul>
      )}
    </div>
  );
}

export default function App() {
  const clientId = useClientId();

  const [loading, setLoading] = useState(false);
  const [health, setHealth] = useState<null | { ok: boolean; text: string }>(
    null
  );
  const [rows, setRows] = useState<BudgetItem[]>([]);
  const [form, setForm] = useState<BudgetItem>({ category: "", monthly_limit: 0 });
  const [toasts, setToasts] = useState<Toast[]>([]);

  const [spend, setSpend] = useState<Record<string, number>>({});
  const [insights, setInsights] = useState<Insight[]>([]);

  const total = useMemo(
    () => rows.reduce((sum, r) => sum + Number(r.monthly_limit || 0), 0),
    [rows]
  );

  const chartData = useMemo(
  () => rows.map(r => ({ name: r.category, value: Number(r.monthly_limit || 0) })),
  [rows]
);

  function pushToast(t: Omit<Toast, "id">) {
    const id = Date.now() + Math.random();
    setToasts(prev => [...prev, { id, ...t }]);
    setTimeout(() => setToasts(prev => prev.filter(x => x.id !== id)), 3500);
  }

  async function testAPI() {
    try {
      setHealth(null);
      const r = await fetch(`${API}/health`);
      const t = await r.text();
      setHealth({ ok: r.ok, text: t });
      if (!r.ok) pushToast({ kind: "err", msg: `Health failed: ${r.status}` });
    } catch (e: any) {
      setHealth({ ok: false, text: e?.message ?? "Network error" });
      pushToast({ kind: "err", msg: "Health request failed" });
    }
  }

  async function loadBudgets() {
    if (!clientId) return;
    setLoading(true);
    try {
      const r = await fetch(`${API}/v1/budgets?client_id=${clientId}`);
      const data = (await r.json()) as BudgetItem[];
      setRows(data);
    } catch {
      pushToast({ kind: "err", msg: "Failed to load budgets" });
    } finally {
      setLoading(false);
    }
  }

  async function loadInsights() {
    if (!clientId) return;
    try {
      const r = await fetch(`${API}/v1/insights?client_id=${clientId}`);
      const data = (await r.json()) as Insight[];
      setInsights(data);
      // seed spend map from DB
      setSpend(Object.fromEntries(data.map(d => [d.category, Number(d.spent || 0)])));
    } catch {
      // non-fatal
    }
  }

  useEffect(() => {
    if (clientId) {
      testAPI();
      loadBudgets();
      loadInsights();
    }
  }, [clientId]);

  function onChange<K extends keyof BudgetItem>(k: K, v: string) {
    if (k === "monthly_limit") {
      const num = Number(v);
      setForm(f => ({ ...f, [k]: Number.isFinite(num) ? num : 0 }));
    } else {
      setForm(f => ({ ...f, [k]: v }));
    }
  }

  function addRowLocal() {
    if (!form.category.trim()) {
      pushToast({ kind: "err", msg: "Category is required" });
      return;
    }
    if (form.monthly_limit <= 0) {
      pushToast({ kind: "err", msg: "Monthly limit must be > 0" });
      return;
    }
    // If category exists, replace; else add
    setRows(prev => {
      const idx = prev.findIndex(r => r.category.toLowerCase() === form.category.toLowerCase());
      const next = [...prev];
      if (idx >= 0) next[idx] = { ...form };
      else next.push({ ...form });
      return next;
    });
    setForm({ category: "", monthly_limit: 0 });
  }

  async function saveBudget() {
    if (!clientId) return;
    const payload: BudgetUpsert = { client_id: clientId, items: rows };
    try {
      const r = await fetch(`${API}/v1/budgets`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      if (!r.ok) {
        const t = await r.text();
        pushToast({ kind: "err", msg: `Save failed: ${r.status} ${t}` });
        return;
      }
      pushToast({ kind: "ok", msg: "Budgets saved" });
      loadBudgets();
    } catch {
      pushToast({ kind: "err", msg: "Network error on save" });
    }
  }

  async function saveSpend() {
    if (!clientId) return;
    const items = Object.entries(spend)
      .filter(([, amt]) => Number.isFinite(amt))
      .map(([category, amount]) => ({ category, amount }));
    try {
      const r = await fetch(`${API}/v1/spend`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ client_id: clientId, items }),
      });
      if (!r.ok) {
        const t = await r.text();
        pushToast({ kind: "err", msg: `Save spend failed: ${r.status} ${t}` });
        return;
      }
      pushToast({ kind: "ok", msg: "Spend saved" });
      loadInsights();
    } catch {
      pushToast({ kind: "err", msg: "Network error on save spend" });
    }
  }

  async function saveAll() {
    // Save budgets first, then spend (so alerts reflect any new limits)
    await saveBudget();
    await saveSpend();
  }

  function removeRow(cat: string) {
    setRows(prev => prev.filter(r => r.category !== cat));
  }

  return (
    <div className="page">
      <header className="hdr">
        <div>
          <h1>Spending Insights</h1>
          <p className="sub">Simple budgets with a serverless Java backend</p>
        </div>
        <div className="hdr-actions">
          <button className="btn ghost" onClick={testAPI}>
            Test API
          </button>
          <StatusChip health={health} />
        </div>
      </header>

      <main className="content">
        <section className="card">
          <h2 className="card-title">New Budget Item</h2>
          <div className="form">
            <div className="field">
              <label>Category</label>
              <CategoryPicker
                value={form.category}
                onChange={(v) => onChange("category", v)}
                options={rows.map(r => r.category)}
              />
            </div>
            <div className="field">
              <label>Monthly Limit ($)</label>
              <input
                type="number"
                min={0}
                step={1}
                value={form.monthly_limit}
                onChange={e => onChange("monthly_limit", e.target.value)}
              />
            </div>
            <div className="actions">
              <button className="btn" onClick={addRowLocal}>Add / Update</button>
            </div>
          </div>
        </section>

        <section className="card">
          <div className="card-head">
            <h2 className="card-title">Budgets</h2>
            <div className="muted">{clientId ? `Client: ${clientId}` : ""}</div>
          </div>

          {loading ? (
            <div className="loading">Loading budgets…</div>
          ) : rows.length === 0 ? (
            <div className="empty">No budgets yet. Add your first item above.</div>
          ) : (
            <>
              <div className="table-wrap">
                <table className="tbl">
                  <thead>
                    <tr>
                      <th>Category</th>
                      <th className="num">Monthly Limit</th>
                      <th className="num">Current Spend</th>
                      <th>Status</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map(r => {
                      const amt = spend[r.category] ?? 0;
                      const status =
                        amt >= r.monthly_limit ? "OVER" :
                        amt >= 0.8 * r.monthly_limit ? "WARNING" : "OK";
                      return (
                        <tr key={r.category} className={status !== "OK" ? `row-${status.toLowerCase()}` : ""}>
                          <td>{r.category}</td>
                          <td className="num">${Number(r.monthly_limit).toFixed(2)}</td>
                          <td className="num">
                            <input
                              className="input num-input"
                              type="number"
                              min={0}
                              step={1}
                              value={amt}
                              onChange={e => setSpend(s => ({ ...s, [r.category]: Number(e.target.value || 0) }))}
                            />
                          </td>
                          <td>
                            <span className={`chip ${status === "OK" ? "ok" : status === "WARNING" ? "" : "err"}`}>
                              {status}
                            </span>
                          </td>
                          <td className="right">
                            <button className="btn danger sm" onClick={() => removeRow(r.category)}>Remove</button>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td className="right strong">Total</td>
                      <td className="num strong">${total.toFixed(2)}</td>
                      <td />
                    </tr>
                  </tfoot>
                </table>
              </div>
              <div className="row-end">
                <button className="btn primary" onClick={saveAll}>Save All</button>
              </div>
            </>
          )}
        </section>

        {rows.length > 0 && (
          <section className="card" style={{ gridColumn: "1 / -1" }}>
            <h2 className="card-title" style={{ marginBottom: 8 }}>Visualizations</h2>
            <BudgetCharts data={chartData} />
          </section>
        )}

      </main>

      <Toasts toasts={toasts} />
      <footer className="ftr">Built with React + Vite · API: AWS Lambda · DB: Neon Postgres</footer>
    </div>
  );
}

function StatusChip({ health }: { health: null | { ok: boolean; text: string } }) {
  if (!health) return <span className="chip">No check</span>;
  return (
    <span className={`chip ${health.ok ? "ok" : "err"}`}>
      {health.ok ? "API OK" : "API Error"}
    </span>
  );
}

function Toasts({ toasts }: { toasts: Toast[] }) {
  return (
    <div className="toasts">
      {toasts.map(t => (
        <div key={t.id} className={`toast ${t.kind}`}>
          {t.msg}
        </div>
      ))}
    </div>
  );
}