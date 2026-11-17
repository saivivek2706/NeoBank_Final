import { createContext, useContext, useState, useCallback, useEffect, type ReactNode } from "react";
import { authApi, type LoginResult } from "../api/auth";
import type { SessionInfo } from "../types";

interface AuthContextValue {
  session: SessionInfo | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<LoginResult>;
  completeMfaLogin: (challengeToken: string, code: string) => Promise<void>;
  register: (email: string, password: string, fullName: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

// Tokens live only in httpOnly cookies now - there is nothing to persist or
// read in localStorage. Session state is purely in-memory React state,
// restored on page load by attempting a silent refresh (the browser sends
// the refresh_token cookie automatically; if it's valid we get a fresh
// session back, otherwise the user just sees the login page).
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<SessionInfo | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    authApi
      .refresh()
      .then((info) => setSession(info))
      .catch(() => setSession(null))
      .finally(() => setLoading(false));
  }, []);

  // Returns the result so LoginPage can show a code prompt when MFA is
  // required, instead of the session being set right away.
  const login = useCallback(async (email: string, password: string) => {
    const result = await authApi.login(email, password);
    if (!result.mfaRequired) {
      setSession(result.session);
    }
    return result;
  }, []);

  const completeMfaLogin = useCallback(async (challengeToken: string, code: string) => {
    const info = await authApi.completeMfaLogin(challengeToken, code);
    setSession(info);
  }, []);

  const register = useCallback(async (email: string, password: string, fullName: string) => {
    const info = await authApi.register(email, password, fullName);
    setSession(info);
  }, []);

  const logout = useCallback(() => {
    authApi.logout().catch(() => {});
    setSession(null);
  }, []);

  return (
    <AuthContext.Provider value={{ session, loading, login, completeMfaLogin, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
