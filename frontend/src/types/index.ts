// Access/refresh tokens live only in httpOnly cookies set by the server -
// they never appear in a JSON response body, so there's nothing token-shaped
// in this type for client-side JS (or an XSS payload) to read.
export interface SessionInfo {
  userId: string;
  role: string;
  expiresInSeconds: number;
}

export interface MfaEnrollInfo {
  secret: string;
  otpauthUrl: string;
}

export interface LedgerEntry {
  id: string;
  transactionId: string;
  entryType: "DEBIT" | "CREDIT";
  amount: number;
  balanceAfter: number;
  description: string;
  createdAt: string;
}

export interface ReconciliationResult {
  accountId: string;
  cachedBalance: number;
  ledgerDerivedBalance: number;
  matches: boolean;
}

export interface Account {
  id: string;
  accountNumber: string;
  ownerName: string;
  balance: number;
  currency: string;
  status: "ACTIVE" | "FROZEN" | "CLOSED";
  accountType: "SAVINGS" | "CURRENT";
}

export type TransactionStatus =
  | "INITIATED"
  | "DEBIT_PENDING"
  | "CREDIT_PENDING"
  | "COMPENSATING"
  | "COMPLETED"
  | "FAILED";

export interface Transaction {
  id: string;
  fromAccountId: string;
  toAccountId: string;
  amount: number;
  currency: string;
  status: TransactionStatus;
  failureReason: string | null;
  createdAt: string;
}

export interface NotificationItem {
  id: string;
  transactionId: string;
  accountId: string;
  type: "DEBIT_ALERT" | "CREDIT_ALERT" | "TRANSFER_FAILED";
  message: string;
  sentAt: string;
}

export interface FraudAlert {
  id: string;
  transactionId: string;
  accountId: string;
  amount: number;
  ruleTriggered: "LARGE_AMOUNT" | "HIGH_FREQUENCY";
  severity: "LOW" | "MEDIUM" | "HIGH";
  details: string;
  flaggedAt: string;
}
