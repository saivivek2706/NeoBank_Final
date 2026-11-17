import { useEffect, useState } from "react";
import { useParams, Link } from "react-router-dom";
import { accountApi, transactionApi, notificationApi } from "../api/banking";
import { useStompTopic } from "../api/useStompTopic";
import { StatusStamp } from "../components/StatusStamp";
import { ApiError } from "../api/client";
import type { Account, Transaction, NotificationItem, LedgerEntry, ReconciliationResult } from "../types";

export function AccountDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [account, setAccount] = useState<Account | null>(null);
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [ledger, setLedger] = useState<LedgerEntry[]>([]);
  const [reconciliation, setReconciliation] = useState<ReconciliationResult | null>(null);
  const [reconciling, setReconciling] = useState(false);

  const { lastMessage, connected } = useStompTopic<Transaction>(
    "/ws/transactions",
    id ? `/topic/accounts/${id}` : null
  );

  async function loadAll() {
    if (!id) return;
    const [acc, txs, notifs, ledgerPage] = await Promise.all([
      accountApi.getById(id),
      transactionApi.forAccount(id),
      notificationApi.forAccount(id),
      accountApi.ledger(id),
    ]);
    setAccount(acc);
    setTransactions(txs);
    setNotifications(notifs);
    setLedger(ledgerPage.content);
  }

  async function runReconciliation() {
    if (!id) return;
    setReconciling(true);
    try {
      setReconciliation(await accountApi.reconcile(id));
    } catch (err) {
      // Non-critical audit tool - a failure here shouldn't disrupt the rest of the page.
      console.error(err instanceof ApiError ? err.message : err);
    } finally {
      setReconciling(false);
    }
  }

  useEffect(() => {
    loadAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // Any push over the socket means something changed for this account -
  // refetch the authoritative state rather than trying to merge the partial
  // event client-side.
  useEffect(() => {
    if (lastMessage) loadAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lastMessage]);

  if (!account) return <p className="empty-state">Loading account...</p>;

  return (
    <div>
      <Link to="/" className="link-row" style={{ display: "block", textAlign: "left", marginBottom: 16 }}>
        ← Back to accounts
      </Link>

      <div className="account-card" style={{ marginBottom: 24 }}>
        <div>
          <div className="account-number">{account.accountNumber}</div>
          <a className="link-row" href={`/api/v1/accounts/${account.id}/statement`}>Download PDF statement</a>
          <div className="account-owner">
            {connected && <span className="live-dot" />}
            {account.accountType} · {account.status}
          </div>
        </div>
        <div>
          <span className="account-balance">{account.balance.toFixed(2)}</span>
          <span className="account-currency">{account.currency}</span>
        </div>
      </div>

      <div className="card">
        <p className="card-title">Transaction history</p>
        {transactions.length === 0 ? (
          <div className="empty-state">No transactions yet.</div>
        ) : (
          transactions.map((tx) => (
            <Link key={tx.id} to={`/transactions/${tx.id}`} style={{ textDecoration: "none", color: "inherit" }}>
              <div className="ledger-row">
                <div className="ledger-main">
                  <span>{tx.fromAccountId === account.id ? "Sent" : "Received"}</span>
                  <span className="ledger-meta">{new Date(tx.createdAt).toLocaleString()}</span>
                </div>
                <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                  <span className="ledger-amount">
                    {tx.fromAccountId === account.id ? "-" : "+"}
                    {tx.amount.toFixed(2)} {tx.currency}
                  </span>
                  <StatusStamp status={tx.status} />
                </div>
              </div>
            </Link>
          ))
        )}
      </div>

      <div className="card">
        <p className="card-title">Notifications</p>
        {notifications.length === 0 ? (
          <div className="empty-state">No notifications yet.</div>
        ) : (
          notifications.map((n) => (
            <div className="ledger-row" key={n.id}>
              <div className="ledger-main">
                <span>{n.message}</span>
                <span className="ledger-meta">{new Date(n.sentAt).toLocaleString()}</span>
              </div>
            </div>
          ))
        )}
      </div>

      <div className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <p className="card-title" style={{ marginBottom: 0 }}>
            Ledger (audit trail)
          </p>
          <button className="btn-ghost" onClick={runReconciliation} disabled={reconciling}>
            {reconciling ? "Checking..." : "Reconcile"}
          </button>
        </div>
        {reconciliation && (
          <p
            className="empty-state"
            style={{ padding: "8px 0", color: reconciliation.matches ? undefined : "var(--stamp-red, #c0392b)" }}
          >
            {reconciliation.matches
              ? `Balance matches the ledger: ${reconciliation.ledgerDerivedBalance.toFixed(2)}.`
              : `Mismatch! Cached balance ${reconciliation.cachedBalance.toFixed(2)} vs ledger-derived ${reconciliation.ledgerDerivedBalance.toFixed(2)}.`}
          </p>
        )}
        {ledger.length === 0 ? (
          <div className="empty-state">No ledger entries yet.</div>
        ) : (
          ledger.map((entry) => (
            <div className="ledger-row" key={entry.id}>
              <div className="ledger-main">
                <span>{entry.description}</span>
                <span className="ledger-meta">{new Date(entry.createdAt).toLocaleString()}</span>
              </div>
              <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                <span className="ledger-amount">
                  {entry.entryType === "DEBIT" ? "-" : "+"}
                  {entry.amount.toFixed(2)}
                </span>
                <span className="ledger-meta">bal. {entry.balanceAfter.toFixed(2)}</span>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
