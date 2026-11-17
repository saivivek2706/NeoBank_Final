import { useState, type FormEvent } from "react";
import { authApi } from "../api/auth";
import { ApiError } from "../api/client";
import type { MfaEnrollInfo } from "../types";

export function SecuritySettingsPage() {
  const [enrollInfo, setEnrollInfo] = useState<MfaEnrollInfo | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [loading, setLoading] = useState(false);

  async function startEnrollment() {
    setError(null);
    setLoading(true);
    try {
      const info = await authApi.mfaEnroll();
      setEnrollInfo(info);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not start 2FA enrollment.");
    } finally {
      setLoading(false);
    }
  }

  async function onVerify(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await authApi.mfaEnrollVerify(code);
      setSuccess(true);
      setEnrollInfo(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "That code didn't match. Try again.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div>
      <h2 style={{ fontSize: 18, marginBottom: 20 }}>Security</h2>
      <div className="card">
        <h3 style={{ fontSize: 15, marginBottom: 8 }}>Two-factor authentication</h3>
        {success && (
          <p className="empty-state" style={{ padding: "0 0 12px" }}>
            2FA is now enabled. You'll be asked for a code on every login, and before any large transfer.
          </p>
        )}
        {!enrollInfo && !success && (
          <>
            <p className="empty-state" style={{ padding: "0 0 12px" }}>
              Add an authenticator app (Google Authenticator, Authy, 1Password, etc.) as a second factor for
              login and for high-value transfers.
            </p>
            {error && <div className="error-banner">{error}</div>}
            <button className="btn-primary" onClick={startEnrollment} disabled={loading}>
              {loading ? "Starting..." : "Enable 2FA"}
            </button>
          </>
        )}
        {enrollInfo && (
          <>
            <p className="empty-state" style={{ padding: "0 0 12px" }}>
              Scan this into your authenticator app, or enter the key manually, then confirm with the code
              it shows you.
            </p>
            <div className="form-field">
              <label>Manual entry key</label>
              <input value={enrollInfo.secret} readOnly onFocus={(e) => e.target.select()} />
            </div>
            <div className="form-field">
              <label>Provisioning URI</label>
              <input value={enrollInfo.otpauthUrl} readOnly onFocus={(e) => e.target.select()} />
            </div>
            {error && <div className="error-banner">{error}</div>}
            <form onSubmit={onVerify}>
              <div className="form-field">
                <label>6-digit code</label>
                <input
                  inputMode="numeric"
                  pattern="[0-9]{6}"
                  maxLength={6}
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  required
                />
              </div>
              <button className="btn-primary" type="submit" disabled={loading}>
                {loading ? "Confirming..." : "Confirm & enable"}
              </button>
            </form>
          </>
        )}
      </div>
    </div>
  );
}
