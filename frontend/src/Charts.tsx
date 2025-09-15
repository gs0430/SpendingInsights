import {
  ResponsiveContainer,
  PieChart, Pie, Cell, Tooltip as PieTooltip, Legend as PieLegend
} from "recharts";

const COLORS = ["#61b0ff","#4da3ff","#86e3ce","#ffdd57","#ff9aa2","#b28dff","#ffd6a5","#9ad0f5","#f7aef8","#c1fba4"];

export function BudgetCharts({ data }: { data: { name: string; value: number }[] }) {
  const total = data.reduce((s, d) => s + (Number(d.value) || 0), 0);

  return (
    <div className="chart-grid">
      <div className="card">
        <h2 className="card-title">By Category (Pie)</h2>
        <div className="chart-wrap">
          <ResponsiveContainer width="100%" height={280}>
            <PieChart>
              <Pie
                data={data}
                dataKey="value"
                nameKey="name"
                innerRadius={60}
                outerRadius={100}
                isAnimationActive
              >
                {data.map((_, i) => (
                  <Cell key={i} fill={COLORS[i % COLORS.length]} />
                ))}
              </Pie>
              <PieTooltip formatter={(v: number) => `$${v.toFixed(2)}`} />
              <PieLegend />
            </PieChart>
          </ResponsiveContainer>
        </div>
        <div className="muted" style={{ marginTop: 8 }}>Total: ${total.toFixed(2)}</div>
      </div>
    </div>
  );
}