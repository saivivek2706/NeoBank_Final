import { useEffect, useRef, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { accountApi, transactionApi } from "../api/banking";
import { authApi } from "../api/auth";
import { ApiError } from "../api/client";
import type { Account } from "../types";

export function TransferPage() {
  const { session } = useAuth();
  const navigate = useNavigate();
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [fromAccountId, setFromAccountId] = useState("");
  const [toAccountId, setToAccountId] = useState("");
  const [amount, setAmount] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // Set when the backend rejects a transfer with 428 Precondition Required -
  // i.e. this is a high-value transfer that needs a fresh TOTP code first.
  const [needsStepUp, setNeedsStepUp] = useState(false);
  const [stepUpCode, setStepUpCode] = useState("");

  useEffect(() => {
    if (!session) return;
    accountApi.listByOwner(session.userId).then((accs) => {
      setAccounts(accs);
      if (accs.length > 0) setFromAccountId(accs[0].id);
    });
  }, [session]);

  const selectedAccount = accounts.find((a) => a.id === fromAccountId);

  // One Idempotency-Key per logical transfer. It is kept across retries of the SAME transfer (a failed
  // request, a double click, the step-up round trip) so the backend can never execute it twice, and
  // discarded as soon as the user changes what is being sent.
  const idempotencyKey = useRef<string | null>(null);
  useEffect(() => {
    idempotencyKey.current = null;
  }, [fromAccountId, toAccountId, amount]);

  async function submitTransfer(stepUpToken?: string) {
    if (!idempotencyKey.current) {
      idempotencyKey.current = crypto.randomUUID();
    }
    const tx = await transactionApi.transfer(
      {
        fromAccountId,
        toAccountId,
        amount: Number(amount),
        currency: selectedAccount?.currency ?? "SGD",
        stepUpToken,
      },
      idempotencyKey.current,
    );
    idempotencyKey.current = null;
    navigate(`/transactions/${tx.id}`);
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await submitTransfer();
    } catch (err) {
      if (err instanceof ApiError && err.status === 428) {
        // Backend confirms this transfer is over the step-up threshold - ask for a code.
        setNeedsStepUp(true);
      } else {
        setError(err instanceof ApiError ? err.message : "Transfer could not be started.");
      }
    } finally {
      setLoading(false);
    }
  }

  async function onSubmitStepUp(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const { stepUpToken } = await authApi.mfaStepUp(stepUpCode);
      await submitTransfer(stepUpToken);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Verification failed. Try again.");
    } finally {
      setLoading(false);
    }
  }

  if (needsStepUp) {
    return (
      <div>
        <h2 style={{ fontSize: 18, marginBottom: 20 }}>Verify this transfer</h2>
        <div className="card">
          <p className="empty-state" style={{ padding: "0 0 12px" }}>
            This transfer is large enough to need a fresh code from your authenticator app before it can go
            through.
          </p>
          {error && <div className="error-banner">{error}</div>}
          <form onSubmit={onSubmitStepUp}>
            <div className="form-field">
              <label>6-digit code</label>
              <input
                inputMode="numeric"
                pattern="[0-9]{6}"
                maxLength={6}
                value={stepUpCode}
                onChange={(e) => setStepUpCode(e.target.value)}
                autoFocus
                required
              />
            </div>
            <button className="btn-primary" type="submit" disabled={loading}>
              {loading ? "Verifying..." : "Confirm transfer"}
            </button>
          </form>
        </div>
      </div>
    );
  }

  return (
    <div>
      <h2 style={{ fontSize: 18, marginBottom: 20 }}>Transfer funds</h2>
      <div className="card">
        {error && <div className="error-banner">{error}</div>}
        <form onSubmit={onSubmit}>
          <div className="form-field">
            <label>From account</label>
            <select value={fromAccountId} onChange={(e) => setFromAccountId(e.target.value)} required>
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.accountNumber} — {a.balance.toFixed(2)} {a.currency}
                </option>
              ))}
            </select>
          </div>
          <div className="form-field">
            <label>To account (account ID)</label>
            <input
              value={toAccountId}
              onChange={(e) => setToAccountId(e.target.value)}
              placeholder="Recipient's account UUID"
              required
            />
          </div>
          <div className="form-field">
            <label>Amount {selectedAccount ? `(${selectedAccount.currency})` : ""}</label>
            <input
              type="number"
              min="0.01"
              step="0.01"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              required
            />
          </div>
          <button className="btn-primary" type="submit" disabled={loading || accounts.length === 0}>
            {loading ? "Starting transfer..." : "Send"}
          </button>
        </form>
      </div>
      <p className="empty-state" style={{ padding: "12px 0" }}>
        Transfers run through an async saga - you'll land on a live status page and watch it move through
        each step as Kafka events land. Large transfers may ask for a 2FA code before they're accepted.
      </p>
    </div>
  );
}
