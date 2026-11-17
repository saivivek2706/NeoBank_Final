import { useEffect, useState } from "react";
import { useParams, Link } from "react-router-dom";
import { transactionApi } from "../api/banking";
import { useStompTopic } from "../api/useStompTopic";
import { StatusStamp } from "../components/StatusStamp";
import type { Transaction } from "../types";

const STEPS = ["INITIATED", "DEBIT_PENDING", "CREDIT_PENDING", "COMPLETED"];

export function TransactionStatusPage() {
  const { id } = useParams<{ id: string }>();
  const [tx, setTx] = useState<Transaction | null>(null);

  const { lastMessage, connected } = useStompTopic<Transaction>(
    "/ws/transactions",
    id ? `/topic/transactions/${id}` : null
  );

  useEffect(() => {
    if (id) transactionApi.getById(id).then(setTx);
  }, [id]);

  useEffect(() => {
    if (lastMessage) setTx(lastMessage);
  }, [lastMessage]);

  if (!tx) return <p className="empty-state">Loading transaction...</p>;

  const failed = tx.status === "FAILED" || tx.status === "COMPENSATING";
  const currentStepIndex = STEPS.indexOf(tx.status);

  return (
    <div>
      <Link to="/" className="link-row" style={{ display: "block", textAlign: "left", marginBottom: 16 }}>
        ← Back to accounts
      </Link>

      <div className="card">
        <p className="card-title">
          {connected && <span className="live-dot" />}
          Transfer status
        </p>

        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 20 }}>
          <span className="ledger-amount" style={{ fontSize: 22 }}>
            {tx.amount.toFixed(2)} {tx.currency}
          </span>
          <StatusStamp status={tx.status} />
        </div>

        {!failed ? (
          <div style={{ display: "flex", gap: 8, marginBottom: 20 }}>
            {STEPS.map((step, i) => (
              <div
                key={step}
                style={{
                  flex: 1,
                  height: 6,
                  borderRadius: 3,
                  background: i <= currentStepIndex ? "var(--stamp-teal)" : "var(--ink-600)",
                  transition: "background 0.3s",
                }}
                title={step}
              />
            ))}
          </div>
        ) : (
          <div className="error-banner">
            {tx.failureReason ?? "This transfer could not be completed."}
            {tx.status === "COMPENSATING" && " The original debit is being reversed."}
          </div>
        )}

        <div className="ledger-meta">Transaction ID: {tx.id}</div>
        <div className="ledger-meta">Started: {new Date(tx.createdAt).toLocaleString()}</div>
      </div>
    </div>
  );
}
