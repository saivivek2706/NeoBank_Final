import { api } from "./client";
import type { SessionInfo, MfaEnrollInfo } from "../types";

export type LoginResult =
  | { mfaRequired: false; session: SessionInfo }
  | { mfaRequired: true; challengeToken: string };

export const authApi = {
  register: (email: string, password: string, fullName: string) =>
    api.post<SessionInfo>("/auth/register", { email, password, fullName }),

  login: async (email: string, password: string): Promise<LoginResult> => {
    const res = await api.post<SessionInfo | { mfaRequired: true; challengeToken: string }>("/auth/login", {
      email,
      password,
    });
    if ("mfaRequired" in res && res.mfaRequired) {
      return { mfaRequired: true, challengeToken: res.challengeToken };
    }
    return { mfaRequired: false, session: res as SessionInfo };
  },

  /** Second step of login when MFA is enabled. */
  completeMfaLogin: (challengeToken: string, code: string) =>
    api.post<SessionInfo>("/auth/mfa/login-challenge", { challengeToken, code }),

  // No refresh token to pass - it's read server-side from the httpOnly cookie.
  refresh: () => api.post<SessionInfo>("/auth/refresh"),

  logout: () => api.post<void>("/auth/logout"),

  mfaEnroll: () => api.post<MfaEnrollInfo>("/auth/mfa/enroll"),

  mfaEnrollVerify: (code: string) => api.post<void>("/auth/mfa/enroll/verify", { code }),

  /** Confirms a fresh TOTP code and returns a short-lived token required for high-value transfers. */
  mfaStepUp: (code: string) => api.post<{ stepUpToken: string }>("/auth/mfa/step-up", { code }),
};
