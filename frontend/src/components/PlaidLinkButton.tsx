import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createLinkToken, exchangePublicToken, syncTransactions } from '../lib/api';

declare global {
  interface Window {
    Plaid?: {
      create: (opts: any) => { open: () => void; exit: () => void };
    };
  }
}

type Props = {
  clientId: string;
  onLinked?: () => void;
  onSynced?: (n: number) => void;
};

export default function PlaidLinkButton({ clientId, onLinked, onSynced }: Props) {
  const [linkToken, setLinkToken] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [scriptReady, setScriptReady] = useState(false);
  const handlerRef = useRef<{ open: () => void } | null>(null);

  // 1) Load the Plaid Link script (once)
  useEffect(() => {
    if (window.Plaid) { setScriptReady(true); return; }
    const s = document.createElement('script');
    s.src = 'https://cdn.plaid.com/link/v2/stable/link-initialize.js';
    s.async = true;
    s.onload = () => setScriptReady(true);
    s.onerror = () => setError('Failed to load Plaid script');
    document.body.appendChild(s);
    return () => { /* leave script attached */ };
  }, []);

  // 2) Fetch link_token from backend
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const { linkToken } = await createLinkToken(clientId);
        if (alive) setLinkToken(linkToken);
      } catch (e: any) {
        if (alive) setError(e?.message ?? 'Failed to create link token');
      }
    })();
    return () => { alive = false; };
  }, [clientId]);

  const onSuccess = useCallback(async (public_token: string) => {
    try {
      await exchangePublicToken(clientId, public_token);
      onLinked?.();
      const { upserted } = await syncTransactions(clientId);
      onSynced?.(upserted);
    } catch (e: any) {
      setError(e?.message ?? 'Exchange/sync failed');
    }
  }, [clientId, onLinked, onSynced]);

  // 3) Initialize Link handler once token + script are ready
  useEffect(() => {
    if (!scriptReady || !linkToken || !window.Plaid || handlerRef.current) return;
    handlerRef.current = window.Plaid.create({
      token: linkToken,
      onSuccess,
      onExit: (err: any) => { if (err?.error_message) setError(err.error_message); }
    });
  }, [scriptReady, linkToken, onSuccess]);

  if (error) return <div style={{ color: '#b91c1c' }}>Plaid error: {error}</div>;
  if (!linkToken || !scriptReady) return <button disabled>Loading…</button>;

  return (
    <button
      onClick={() => handlerRef.current?.open()}
      style={{ padding: '10px 16px', borderRadius: 8, background: '#111', color: '#fff' }}
    >
      Connect a credit card
    </button>
  );
}
