import { useEffect, useMemo, useRef, useState } from "react";
import { BudgetCharts } from "./Charts";
import PlaidLinkButton from './components/PlaidLinkButton';
import { listTransactions, upsertBudgetItem, deleteBudgetItem } from './lib/api';
import TransactionsCard from "./components/TransactionsCard";

const API = import.meta.env.VITE_API_BASE_URL as string;


type BudgetItem = { category: string; monthly_limit: number; current_spend?: number };
type BudgetUpsert = { client_id: string; items: BudgetItem[] };
type TxRow = {
  id: number;
  account_id: number;
  account_name: string;
  merchant: string | null;
  category: string | null;
  amount_cents: number;
  post_date: string | null;  // "YYYY-MM-DD"
  status: string | null;
};
type TxList = {
  items: TxRow[];
  next: { beforeDate: string; beforeId: string } | null;
};

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

function formatCurrency(n: number) {
  try {
    return new Intl.NumberFormat(undefined, {
      style: "currency",
      currency: "USD",
      maximumFractionDigits: 2,
    }).format(Number.isFinite(n) ? n : 0);
  } catch {
    return `$${(Number.isFinite(n) ? n : 0).toFixed(2)}`;
  }
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

  const [savingBudget, setSavingBudget] = useState(false);
  const [loading, setLoading] = useState(false);
  const [health, setHealth] = useState<null | { ok: boolean; text: string }>(
    null
  );
  const [rows, setRows] = useState<BudgetItem[]>([]);
  const [form, setForm] = useState<BudgetItem>({ category: "", monthly_limit: 0 });
  const [toasts, setToasts] = useState<Toast[]>([]);

  const total = useMemo(
    () => rows.reduce((sum, r) => sum + Number(r.monthly_limit || 0), 0),
    [rows]
  );

  const chartData = useMemo(
  () => rows.map(r => ({ name: r.category, value: Number(r.monthly_limit || 0) })),
  [rows]
  );

  const [txRows, setTxRows] = useState<TxRow[]>([]);
  const [txNext, setTxNext] = useState<TxList["next"]>(null);
  const [txLoading, setTxLoading] = useState(false);
  const [txInit, setTxInit] = useState(false);

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
  
  async function loadTxFirstPage() {
  if (!clientId) return;
  setTxLoading(true);
  try {
    const page = (await listTransactions(clientId)) as TxList; // api.ts returns untyped JSON
    setTxRows(page.items);
    setTxNext(page.next);
  } catch (e) {
    pushToast({ kind: "err", msg: "Failed to load transactions" });
  } finally {
    setTxLoading(false);
    setTxInit(true);
  }
}

async function loadTxMore() {
  if (!txNext) return;
  setTxLoading(true);
  try {
    // keeping api.ts minimal; use fetch directly for "next" for now
    const qs = new URLSearchParams({
      client_id: clientId,
      beforeDate: txNext.beforeDate,
      beforeId: String(txNext.beforeId),
    }).toString();
    const r = await fetch(`${API}/v1/transactions?${qs}`);
    const page = (await r.json()) as TxList;
    setTxRows(prev => [...prev, ...page.items]);
    setTxNext(page.next);
  } catch (e) {
    pushToast({ kind: "err", msg: "Failed to load more" });
  } finally {
    setTxLoading(false);
  }
}

  useEffect(() => {
    if (clientId) {
      testAPI();
      loadBudgets();
      loadTxFirstPage();
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

  async function addRowLocal() {
    if (!clientId) return;
    if (!form.category.trim()) { pushToast({ kind: "err", msg: "Category is required" }); return; }
    if (form.monthly_limit <= 0) { pushToast({ kind: "err", msg: "Monthly limit must be > 0" }); return; }

    setSavingBudget(true);

    // Optimistic UI update
    setRows(prev => {
      const next = [...prev];
      const i = next.findIndex(r => r.category.toLowerCase() === form.category.toLowerCase());
      if (i >= 0) next[i] = { ...form };
      else next.push({ ...form });
      return next;
    });

    try {
      await upsertBudgetItem(clientId, { category: form.category, monthly_limit: Number(form.monthly_limit) });
      await loadBudgets(); // refresh so current_spend is accurate
      pushToast({ kind: "ok", msg: "Budget saved" });
      setForm({ category: "", monthly_limit: 0 });
    } catch (e: any) {
      pushToast({ kind: "err", msg: e?.message || "Failed to save budget" });
      await loadBudgets(); // rollback to server truth
    } finally {
      setSavingBudget(false);
    }
  }

  async function removeRow(cat: string) {
    if (!clientId) return;
    const prev = rows;

    // Optimistic remove
    setRows(prev.filter(r => r.category !== cat));

    try {
      await deleteBudgetItem(clientId, cat);
      await loadBudgets(); // keep current_spend fresh
      pushToast({ kind: "ok", msg: `Removed ${cat}` });
    } catch (e: any) {
      setRows(prev); // rollback
      pushToast({ kind: "err", msg: e?.message || `Failed to remove ${cat}` });
    }
  }


  return (
    <div className="page">
      <header className="hdr">
        <div>
          <h1>Spending Insights</h1>
          <p className="sub">Simple budgets with a serverless Java backend</p>
        </div>
        <div className="hdr-actions" style={{ display: "flex", gap: 8, alignItems: "center" }}>
          {clientId ? (
            <PlaidLinkButton
              clientId={clientId}
              onLinked={() => pushToast({ kind: "ok", msg: "Plaid link successful" })}
              onSynced={(n) => {
                pushToast({ kind: "ok", msg: `Synced ${n} transactions` });
                loadTxFirstPage();
                loadBudgets();
              }}
            />
          ) : (
            <button className="btn" disabled>
              Loading…
            </button>
          )}
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
                options={rows.map((r) => r.category)}
              />
            </div>
            <div className="field">
              <label>Monthly Limit ($)</label>
              <input
                type="number"
                min={0}
                step={1}
                value={form.monthly_limit}
                onChange={(e) => onChange("monthly_limit", e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") addRowLocal();
                }}
              />
            </div>
            <div className="actions">
              <button className="btn" onClick={addRowLocal} disabled={savingBudget}>
                {savingBudget ? "Saving…" : "Add / Update"}
              </button>
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
                      <th className="num">Current Spend (auto)</th>
                      <th>Status</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((r) => {
                      const amt = Number(r.current_spend ?? 0);          // ← MTD spend from API (dollars)
                      const status =
                        amt >= r.monthly_limit ? "OVER" :
                        amt >= 0.8 * r.monthly_limit ? "WARNING" : "OK";

                      return (
                        <tr key={r.category} className={status !== "OK" ? `row-${status.toLowerCase()}` : ""}>
                          <td>{r.category}</td>
                          <td className="num">{formatCurrency(Number(r.monthly_limit))}</td>
                          <td className="num">{formatCurrency(amt)}</td>   {/* ← show current spend */}
                          <td>
                            <span className={`chip ${status === "OK" ? "ok" : status === "WARNING" ? "" : "err"}`} title={`Local calc: ${status}`}>
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
                      <td className="num strong">{formatCurrency(total)}</td>
                      <td />
                    </tr>
                  </tfoot>
                </table>
              </div>
            </>
          )}
        </section>

        <TransactionsCard
          rows={txRows}
          next={txNext}
          loading={txLoading}
          initialized={txInit}
          onRefresh={loadTxFirstPage}
          onLoadMore={loadTxMore}
        />

        {rows.length > 0 && (
          <section className="card" style={{ gridColumn: "1 / -1" }}>
            <h2 className="card-title" style={{ marginBottom: 8 }}>
              Visualizations
            </h2>
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
  return <span className={`chip ${health.ok ? "ok" : "err"}`}>{health.ok ? "API OK" : "API Error"}</span>;
}

function Toasts({ toasts }: { toasts: Toast[] }) {
  return (
    <div className="toasts">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.kind}`}>
          {t.msg}
        </div>
      ))}
    </div>
  );
}