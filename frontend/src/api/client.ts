const BASE = "/api/v1";

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function handle<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = res.statusText;
    try {
      const body = await res.json();
      message = body.message || message;
    } catch {
      /* response had no JSON body */
    }
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}

// The access token lives in an httpOnly cookie set by auth-service, so the
// browser attaches it automatically - there's no Authorization header for
// this client to manage, and no token value JS ever touches.
async function rawRequest(path: string, options: RequestInit = {}): Promise<Response> {
  return fetch(`${BASE}${path}`, {
    ...options,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...options.headers,
    },
  });
}

let refreshInFlight: Promise<boolean> | null = null;

/** Attempts a silent refresh using the httpOnly refresh_token cookie. Coalesces concurrent callers into one request. */
async function trySilentRefresh(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = rawRequest("/auth/refresh", { method: "POST" })
      .then((res) => res.ok)
      .catch(() => false)
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

async function request<T>(path: string, options: RequestInit = {}, isRetry = false): Promise<T> {
  const res = await rawRequest(path, options);

  // A single automatic retry after a silent refresh - covers the case where
  // the access token expired mid-session but the refresh token is still
  // valid, without ever exposing token handling to the rest of the app.
  if (res.status === 401 && !isRetry && !path.startsWith("/auth/")) {
    const refreshed = await trySilentRefresh();
    if (refreshed) {
      return request<T>(path, options, true);
    }
  }

  return handle<T>(res);
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown, headers?: Record<string, string>) =>
    request<T>(path, { method: "POST", body: body ? JSON.stringify(body) : undefined, headers }),
};
