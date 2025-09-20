const API_BASE = import.meta.env.VITE_API_BASE_URL || ''

export async function apiGet(path: string) {
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'GET',
    headers: { 'Content-Type': 'application/json' }
  })
  if (!res.ok) throw new Error(`GET ${path} failed: ${res.status}`)
  return res.json()
}

export async function apiPost(path: string, body: any) {
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  if (!res.ok) throw new Error(`POST ${path} failed: ${res.status}`)
  return res.json()
}

export async function apiDelete(path: string) {
  const res = await fetch(`${API_BASE}${path}`, { 
    method: "DELETE", 
    headers: { "Content-Type": "application/json" } });
  if (!res.ok) throw new Error(`DELETE ${path} failed: ${res.status}`);
  return res.json();
}


// -------- Plaid-specific helpers --------

export async function createLinkToken(clientId: string) {
  const res = await apiPost('/api/plaid/link-token/create', { clientId });
  return res;  // should contain { linkToken: "..." }
}

export function exchangePublicToken(
  clientId: string,
  publicToken: string
): Promise<{ ok: boolean; itemId: string; institutionId: string }> {
  return apiPost('/api/plaid/item/public_token/exchange', {
    clientId,
    publicToken
  });
}

export function syncTransactions(clientId: string): Promise<{ upserted: number }> {
  // Backend: POST /api/plaid/transactions/sync { clientId }
  return apiPost('/api/plaid/transactions/sync', { clientId });
}

export function listTransactions(clientId: string) {
  return apiGet(`/v1/transactions?client_id=${encodeURIComponent(clientId)}&limit=50`);
}

export function upsertBudgetItem(clientId: string, item: { category: string; monthly_limit: number }) {
  return apiPost("/v1/budgets", { client_id: clientId, items: [item] });
}
export function deleteBudgetItem(clientId: string, category: string) {
  const qs = new URLSearchParams({ client_id: clientId, category }).toString();
  return apiDelete(`/v1/budgets?${qs}`);
}