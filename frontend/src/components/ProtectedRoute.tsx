import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { session, loading } = useAuth();
  // Session is restored asynchronously on mount via a silent refresh (the
  // refresh_token cookie isn't readable by JS, so we can't know synchronously
  // whether the user is logged in). Wait for that check before redirecting,
  // otherwise every page reload would briefly bounce a logged-in user to /login.
  if (loading) return null;
  if (!session) return <Navigate to="/login" replace />;
  return <>{children}</>;
}
