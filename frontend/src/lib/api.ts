import { UserManager, WebStorageStateStore } from "oidc-client-ts";
export const oidc = new UserManager({
  authority:
    import.meta.env.VITE_OIDC_AUTHORITY ||
    "http://localhost:19383/realms/assets",
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID || "assets-web",
  redirect_uri:
    import.meta.env.VITE_OIDC_REDIRECT_URI ||
    `${window.location.origin}/auth/callback`,
  post_logout_redirect_uri: window.location.origin,
  response_type: "code",
  scope: "openid profile email",
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
});
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public traceId?: string,
    public status?: number,
  ) {
    super(message);
  }
}
export const errorText = (e: unknown) =>
  e instanceof ApiError
    ? `${e.message}${e.traceId ? ` · 追踪 ${e.traceId}` : ""}`
    : e instanceof Error
      ? e.message
      : String(e);
export function query(values: Record<string, unknown> = {}) {
  const p = new URLSearchParams();
  Object.entries(values).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
  });
  return p.size ? `?${p}` : "";
}
export async function api<T = any>(
  path: string,
  options: RequestInit & { json?: unknown } = {},
): Promise<T> {
  const user = await oidc.getUser();
  if (!user || user.expired)
    throw new ApiError(
      "UNAUTHENTICATED",
      "登录已失效，请重新登录",
      undefined,
      401,
    );
  const headers = new Headers(options.headers);
  headers.set("Authorization", `Bearer ${user.access_token}`);
  let body = options.body;
  if (options.json !== undefined) {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(options.json);
  }
  let res: Response;
  try {
    res = await fetch(`/api/v1${path}`, { ...options, headers, body });
  } catch {
    throw new ApiError("NETWORK_ERROR", "服务连接失败，请检查服务状态后重试");
  }
  if (!res.ok) {
    let data: any = {};
    try {
      data = await res.json();
    } catch {}
    throw new ApiError(
      data.code || `HTTP_${res.status}`,
      data.message || `请求失败 (${res.status})`,
      data.traceId,
      res.status,
    );
  }
  if (res.status === 204) return undefined as T;
  const kind = res.headers.get("content-type") || "";
  return (kind.includes("json") ? await res.json() : await res.blob()) as T;
}
export const listOf = (value: any): any[] =>
  Array.isArray(value) ? value : value?.items || [];
export function download(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob),
    a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 500);
}
export function csvSafe(v: unknown) {
  let value = String(v ?? "");
  if (/^[=+@-]/.test(value)) value = "'" + value;
  return '"' + value.replaceAll('"', '""') + '"';
}

/** Fetch every page using the backend's supported maximum; selectors never truncate silently. */
export async function allAssets(
  projectId: string,
  filters: Record<string, unknown> = {},
) {
  const result: any[] = [];
  for (let page = 1; ; page++) {
    const response = await api(
      "/assets" + query({ ...filters, projectId, page, pageSize: 200 }),
    );
    const items = listOf(response);
    result.push(...items);
    if (!items.length || result.length >= (response.total ?? result.length))
      break;
  }
  return result;
}

export const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value));
