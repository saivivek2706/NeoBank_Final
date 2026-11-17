import { api } from "./client";
import type { Account, Transaction, NotificationItem, FraudAlert, LedgerEntry, ReconciliationResult } from "../types";

export const accountApi = {
  open: (params: {
    ownerId: string;
    ownerName: string;
    accountType: "SAVINGS" | "CURRENT";
    currency: string;
    openingBalance: number;
  }) => api.post<Account>("/accounts", params),

  getById: (id: string) => api.get<Account>(`/accounts/${id}`),

  listByOwner: (ownerId: string) => api.get<Account[]>(`/accounts?ownerId=${ownerId}`),

  /** Raw append-only ledger entries backing this account's balance - the audit trail, not the same as the transaction saga history. */
  ledger: (id: string) => api.get<{ content: LedgerEntry[] }>(`/accounts/${id}/ledger?size=20`),

  /** Recomputes the balance purely from the ledger and compares to the cached value - flags any drift. */
  reconcile: (id: string) => api.get<ReconciliationResult>(`/accounts/${id}/reconcile`),
};

export const transactionApi = {
  /**
   * `idempotencyKey` identifies ONE logical transfer. Re-send the same key when retrying (timeout, double
   * click, step-up round trip) and the server returns the original transaction instead of moving the money
   * twice; use a new key only for a genuinely new transfer.
   */
  transfer: (
    params: {
      fromAccountId: string;
      toAccountId: string;
      amount: number;
      currency: string;
      stepUpToken?: string;
    },
    idempotencyKey: string,
  ) => api.post<Transaction>("/transactions/transfer", params, { "Idempotency-Key": idempotencyKey }),

  getById: (id: string) => api.get<Transaction>(`/transactions/${id}`),

  forAccount: (accountId: string) => api.get<Transaction[]>(`/transactions/account/${accountId}`),
};

export const notificationApi = {
  forAccount: (accountId: string) => api.get<NotificationItem[]>(`/notifications/account/${accountId}`),
};

export const fraudApi = {
  all: () => api.get<FraudAlert[]>("/fraud-alerts"),
};
