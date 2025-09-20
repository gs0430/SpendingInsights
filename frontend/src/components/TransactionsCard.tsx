type TxRow = {
  id: number;
  account_id: number;
  account_name: string;
  merchant: string | null;
  category: string | null;
  amount_cents: number;
  post_date: string | null; // "YYYY-MM-DD"
  status: string | null;
};

type NextCursor = { beforeDate: string; beforeId: string } | null;

function clipProps(full: string | null | undefined) {
  return {
    className: "cell-clip",
    "data-full": full ?? undefined,
    onMouseMove: (e: React.MouseEvent<HTMLElement>) => {
      const t = e.currentTarget as HTMLElement;
      t.style.setProperty("--tt-x", `${e.clientX}px`);
      t.style.setProperty("--tt-y", `${e.clientY}px`);
    },
    tabIndex: 0, // accessibility: also allow focus-with-keyboard
  } as React.HTMLAttributes<HTMLElement>;
}

function money(cents: number) {
  return (cents / 100).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

export default function TransactionsCard(props: {
  rows: TxRow[];
  next: NextCursor;
  loading: boolean;
  initialized: boolean;
  onRefresh: () => void;
  onLoadMore: () => void;
}) {
  const { rows, next, loading, initialized, onRefresh, onLoadMore } = props;

  return (
    <section className="card">
      <div className="card-head">
        <h2 className="card-title">Transactions</h2>
        <div className="row-end" style={{ display: "flex", gap: 8 }}>
          <button className="btn ghost" onClick={onRefresh} disabled={loading}>
            {loading && !initialized ? "Loading…" : "Refresh"}
          </button>
        </div>
      </div>

      <div className="table-wrap">
        <table className="tbl">
          <colgroup>
            <col style={{ width: '12%' }} />  {/* 110px  Post Date */}
            <col style={{ width: '20%' }} />  {/* 200px  Merchant */}
            <col style={{ width: '23%' }} />  {/* 250px  Category */}
            <col style={{ width: '25%' }} />  {/* 150px  Account */}
            <col style={{ width: '10%' }} />  {/* 90px   Amount */}
            <col style={{ width: '10%' }} />  {/* 90px   Status */}
          </colgroup>
          <thead>
            <tr>
              <th>Post Date</th>
              <th>Merchant</th>
              <th>Category</th>
              <th>Account</th>
              <th className="num">Amount</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {/* Skeleton while first load */}
            {!initialized &&
              Array.from({ length: 6 }).map((_, i) => (
                <tr key={`tx-sk-${i}`}>
                  <td colSpan={6}>
                    <div className="h-5 w-full animate-pulse bg-gray-100 rounded" />
                  </td>
                </tr>
              ))}

            {/* Empty state */}
            {initialized && rows.length === 0 && (
              <tr>
                <td colSpan={6} className="empty">
                  No transactions yet. Link an account and sync to see data here.
                </td>
              </tr>
            )}

            {/* Data rows */}
            {rows.map((tx) => (
              <tr key={tx.id}>
                <td>{tx.post_date ?? "—"}</td>
                <td><span {...clipProps(tx.merchant ?? "")}>{tx.merchant ?? "—"}</span></td>
                <td><span {...clipProps(tx.category ?? "Uncategorized")}>{tx.category ?? "Uncategorized"}</span></td>
                <td><span {...clipProps(tx.account_name)}>{tx.account_name}</span></td>
                <td className="num">{money((tx.amount_cents || 0))}</td>
                <td>{tx.status ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      <div className="row-end" style={{ marginTop: 8, display: "flex", gap: 8 }}>
        {next ? (
          <button className="btn" onClick={onLoadMore} disabled={loading}>
            {loading ? "Loading…" : "Load more"}
          </button>
        ) : (
          initialized && rows.length > 0 && <span className="muted">End of results</span>
        )}
      </div>
    </section>
  );
}