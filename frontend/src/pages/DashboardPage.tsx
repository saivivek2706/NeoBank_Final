import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { accountApi } from "../api/banking";
import { ApiError } from "../api/client";
import type { Account } from "../types";

export function DashboardPage() {
  const { session } = useAuth();
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    if (!session) return;
    setLoading(true);
    try {
      const data = await accountApi.listByOwner(session.userId);
      setAccounts(data);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not load accounts.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session]);

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 20 }}>
        <h2 style={{ margin: 0, fontSize: 18 }}>Your accounts</h2>
        <button className="btn-ghost" onClick={() => setShowForm((s) => !s)}>
          {showForm ? "Cancel" : "+ Open account"}
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {showForm && (
        <OpenAccountForm
          onOpened={() => {
            setShowForm(false);
            refresh();
          }}
        />
      )}

      {loading ? (
        <p className="empty-state">Loading accounts...</p>
      ) : accounts.length === 0 ? (
        <div className="empty-state">You don't have any accounts yet. Open one to get started.</div>
      ) : (
        accounts.map((a) => (
          <Link key={a.id} to={`/accounts/${a.id}`} style={{ textDecoration: "none", color: "inherit" }}>
            <div className="account-card">
              <div>
                <div className="account-number">{a.accountNumber}</div>
                <div className="account-owner">
                  {a.accountType === "SAVINGS" ? "Savings" : "Current"} · {a.status}
                </div>
              </div>
              <div>
                <span className="account-balance">{a.balance.toFixed(2)}</span>
                <span className="account-currency">{a.currency}</span>
              </div>
            </div>
          </Link>
        ))
      )}
    </div>
  );
}

function OpenAccountForm({ onOpened }: { onOpened: () => void }) {
  const { session } = useAuth();
  const [ownerName, setOwnerName] = useState("");
  const [accountType, setAccountType] = useState<"SAVINGS" | "CURRENT">("SAVINGS");
  const [currency, setCurrency] = useState("SGD");
  const [openingBalance, setOpeningBalance] = useState("0");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!session) return;
    setError(null);
    setLoading(true);
    try {
      await accountApi.open({
        ownerId: session.userId,
        ownerName,
        accountType,
        currency,
        openingBalance: Number(openingBalance),
      });
      onOpened();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not open the account.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="card">
      <p className="card-title">Open a new account</p>
      {error && <div className="error-banner">{error}</div>}
      <form onSubmit={onSubmit}>
        <div className="form-field">
          <label>Account holder name</label>
          <input value={ownerName} onChange={(e) => setOwnerName(e.target.value)} required />
        </div>
        <div className="grid-2">
          <div className="form-field">
            <label>Account type</label>
            <select value={accountType} onChange={(e) => setAccountType(e.target.value as "SAVINGS" | "CURRENT")}>
              <option value="SAVINGS">Savings</option>
              <option value="CURRENT">Current</option>
            </select>
          </div>
          <div className="form-field">
            <label>Currency</label>
            <select value={currency} onChange={(e) => setCurrency(e.target.value)}>
              <option value="SGD">SGD</option>
              <option value="USD">USD</option>
              <option value="EUR">EUR</option>
            </select>
          </div>
        </div>
        <div className="form-field">
          <label>Opening balance</label>
          <input
            type="number"
            min="0"
            step="0.01"
            value={openingBalance}
            onChange={(e) => setOpeningBalance(e.target.value)}
          />
        </div>
        <button className="btn-primary" type="submit" disabled={loading}>
          {loading ? "Opening..." : "Open account"}
        </button>
      </form>
    </div>
  );
}
