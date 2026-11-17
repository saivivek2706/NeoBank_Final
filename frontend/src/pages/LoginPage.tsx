import { useState, type FormEvent } from "react";
import { useNavigate, Link } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { ApiError } from "../api/client";

export function LoginPage() {
  const { login, completeMfaLogin } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // Set only when the account has MFA enabled and the password step just succeeded.
  const [challengeToken, setChallengeToken] = useState<string | null>(null);
  const [code, setCode] = useState("");

  async function onSubmitPassword(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const result = await login(email, password);
      if (result.mfaRequired) {
        setChallengeToken(result.challengeToken);
      } else {
        navigate("/");
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not sign in. Try again.");
    } finally {
      setLoading(false);
    }
  }

  async function onSubmitCode(e: FormEvent) {
    e.preventDefault();
    if (!challengeToken) return;
    setError(null);
    setLoading(true);
    try {
      await completeMfaLogin(challengeToken, code);
      navigate("/");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "That code didn't work. Try again.");
    } finally {
      setLoading(false);
    }
  }

  if (challengeToken) {
    return (
      <div className="auth-shell">
        <h1>Enter your 2FA code</h1>
        <p className="subtitle">Open your authenticator app and enter the current 6-digit code.</p>
        {error && <div className="error-banner">{error}</div>}
        <form onSubmit={onSubmitCode}>
          <div className="form-field">
            <label htmlFor="code">6-digit code</label>
            <input
              id="code"
              inputMode="numeric"
              pattern="[0-9]{6}"
              maxLength={6}
              value={code}
              onChange={(e) => setCode(e.target.value)}
              autoFocus
              required
            />
          </div>
          <button className="btn-primary" type="submit" disabled={loading}>
            {loading ? "Verifying..." : "Verify & sign in"}
          </button>
        </form>
        <div className="link-row">
          <button type="button" className="link-button" onClick={() => setChallengeToken(null)}>
            Back
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-shell">
      <h1>Sign in</h1>
      <p className="subtitle">Access your NeoBank ledger.</p>
      {error && <div className="error-banner">{error}</div>}
      <form onSubmit={onSubmitPassword}>
        <div className="form-field">
          <label htmlFor="email">Email</label>
          <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </div>
        <div className="form-field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </div>
        <button className="btn-primary" type="submit" disabled={loading}>
          {loading ? "Signing in..." : "Sign in"}
        </button>
      </form>
      <div className="link-row">
        New here? <Link to="/register">Create an account</Link>
      </div>
    </div>
  );
}
