import { NavLink } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export function Navbar() {
  const { session, logout } = useAuth();

  return (
    <div className="navbar">
      <div className="brand">
        <span className="brand-mark">
          Neo<span>Bank</span>
        </span>
        <span className="brand-tag">Ledger</span>
      </div>
      {session && (
        <div className="nav-links">
          <NavLink to="/" end>
            Accounts
          </NavLink>
          <NavLink to="/transfer">Transfer</NavLink>
          <NavLink to="/security">Security</NavLink>
          {session.role === "ADMIN" && <NavLink to="/fraud">Fraud Watch</NavLink>}
          <button className="btn-ghost" onClick={logout}>
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
