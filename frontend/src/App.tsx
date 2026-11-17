import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AuthProvider } from "./context/AuthContext";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { Navbar } from "./components/Navbar";
import { LoginPage } from "./pages/LoginPage";
import { RegisterPage } from "./pages/RegisterPage";
import { DashboardPage } from "./pages/DashboardPage";
import { AccountDetailPage } from "./pages/AccountDetailPage";
import { TransferPage } from "./pages/TransferPage";
import { TransactionStatusPage } from "./pages/TransactionStatusPage";
import { FraudDashboardPage } from "./pages/FraudDashboardPage";
import { SecuritySettingsPage } from "./pages/SecuritySettingsPage";
import { KycPage } from "./pages/KycPage";
import { BeneficiariesPage } from "./pages/BeneficiariesPage";

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <div className="app-shell">
          <Navbar />
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
            <Route
              path="/"
              element={
                <ProtectedRoute>
                  <DashboardPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/accounts/:id"
              element={
                <ProtectedRoute>
                  <AccountDetailPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/transfer"
              element={
                <ProtectedRoute>
                  <TransferPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/transactions/:id"
              element={
                <ProtectedRoute>
                  <TransactionStatusPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/fraud"
              element={
                <ProtectedRoute>
                  <FraudDashboardPage />
                </ProtectedRoute>
              }
            />
            <Route path="/kyc" element={<ProtectedRoute><KycPage /></ProtectedRoute>} />
            <Route path="/beneficiaries" element={<ProtectedRoute><BeneficiariesPage /></ProtectedRoute>} />
            <Route
              path="/security"
              element={
                <ProtectedRoute>
                  <SecuritySettingsPage />
                </ProtectedRoute>
              }
            />
          </Routes>
        </div>
      </AuthProvider>
    </BrowserRouter>
  );
}
