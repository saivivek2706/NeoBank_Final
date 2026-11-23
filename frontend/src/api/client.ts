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
let csrfTokenPromise: Promise<string> | null = null;

async function getCsrfToken(): Promise<string> {
  if (!csrfTokenPromise) {
    csrfTokenPromise = fetch(`${BASE}/csrf`, { credentials: "include" })
      .then(async (res) => {
        if (!res.ok) throw new Error("Unable to obtain CSRF token");
        const body = await res.json();
        if (!body.token) throw new Error("CSRF endpoint returned no token");
        return body.token as string;
      })
      .catch((error) => {
        csrfTokenPromise = null;
        throw error;
      });
  }
  return csrfTokenPromise;
}


async function rawRequest(path: string, options: RequestInit = {}): Promise<Response> {
  const method = (options.method || "GET").toUpperCase();

  // Normalize all supported HeadersInit formats.
  const headers = new Headers(options.headers);

  if (!headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  if (!["GET", "HEAD", "OPTIONS", "TRACE"].includes(method)) {
    headers.set("X-XSRF-TOKEN", await getCsrfToken());
  }

  return fetch(`${BASE}${path}`, {
    ...options,
    credentials: "include",
    headers,
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
  put: <T>(path: string, body: unknown) => request<T>(path, { method: "PUT", body: JSON.stringify(body) }),
  delete: <T>(path: string) => request<T>(path, { method: "DELETE" }),
  upload: async <T>(path: string, form: FormData): Promise<T> => {
    const csrfToken = await getCsrfToken();
    const res = await fetch(`${BASE}${path}`, { method: "POST", credentials: "include", headers: { "X-XSRF-TOKEN": csrfToken }, body: form });
    return handle<T>(res);
  },

};
