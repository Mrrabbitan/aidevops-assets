import { beforeEach, afterEach, describe, it, expect, vi } from "vitest";
import { api, oidc, ApiError, errorText, query, csvSafe } from "./api";
describe("authenticated API boundary", () => {
  beforeEach(() => {
    vi.spyOn(oidc, "getUser").mockResolvedValue({
      access_token: "test-access-token",
      expired: false,
    } as any);
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
  it("requires login before any request", async () => {
    vi.mocked(oidc.getUser).mockResolvedValue(null);
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await expect(api("/assets")).rejects.toMatchObject({
      code: "UNAUTHENTICATED",
    });
    expect(fetch).not.toHaveBeenCalled();
  });
  it("sends the bearer token and preserves project query", async () => {
    const fetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ items: [] }), {
        headers: { "content-type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetch);
    await api("/assets?projectId=demo");
    expect(fetch.mock.calls[0][0]).toBe("/api/v1/assets?projectId=demo");
    expect(fetch.mock.calls[0][1].headers.get("Authorization")).toBe(
      "Bearer test-access-token",
    );
  });
  it("shows service errors and trace identifiers without fabricating success", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "ADAPTER_NOT_CONFIGURED",
            message: "真实资源未接入",
            traceId: "trace-123",
          }),
          { status: 503, headers: { "content-type": "application/json" } },
        ),
      ),
    );
    try {
      await api("/tasks/1/execute");
      throw Error("expected failure");
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError);
      expect(errorText(e)).toContain("trace-123");
      expect(errorText(e)).toContain("真实资源未接入");
    }
  });
  it("leaves multipart boundaries to the browser", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response("{}", { headers: { "content-type": "application/json" } }),
      );
    vi.stubGlobal("fetch", fetch);
    const body = new FormData();
    body.set("projectId", "demo");
    await api("/assets/import/preview", { method: "POST", body });
    expect(fetch.mock.calls[0][1].headers.has("content-type")).toBe(false);
  });
  it("encodes query strings and protects spreadsheet CSV cells", () => {
    expect(query({ projectId: "a b", empty: "", page: 1 })).toBe(
      "?projectId=a+b&page=1",
    );
    expect(csvSafe("=cmd")).toBe('"\'=cmd"');
    expect(csvSafe('a"b')).toBe('"a""b"');
  });
});

describe("asset selector pagination", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
  it("loads all pages without exceeding the server page-size limit", async () => {
    vi.spyOn(oidc, "getUser").mockResolvedValue({
      access_token: "test",
      expired: false,
    } as any);
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            items: Array.from({ length: 200 }, (_, i) => ({ id: String(i) })),
            total: 201,
          }),
          { headers: { "content-type": "application/json" } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ items: [{ id: "200" }], total: 201 }), {
          headers: { "content-type": "application/json" },
        }),
      );
    vi.stubGlobal("fetch", fetch);
    const { allAssets } = await import("./api");
    const records = await allAssets("demo", { admission: "ADMITTED" });
    expect(records).toHaveLength(201);
    expect(fetch.mock.calls[0][0]).toContain("pageSize=200");
    expect(fetch.mock.calls[1][0]).toContain("page=2");
    expect(fetch.mock.calls[1][0]).toContain("projectId=demo");
  });
});
