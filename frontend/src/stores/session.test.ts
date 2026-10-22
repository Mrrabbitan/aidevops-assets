import { beforeEach, describe, it, expect } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useSession } from "./session";
describe("project permission boundary", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });
  it("does not infer privileges from admin role or another project", () => {
    const store = useSession();
    store.profile = {
      roles: ["admin"],
      permissions: ["*"],
      projectPermissions: { demo: ["asset.read"], sandbox: ["*"] },
    };
    store.projectId = "demo";
    expect(store.can("asset.write")).toBe(false);
    expect(store.can("asset.read")).toBe(true);
    store.projectId = "sandbox";
    expect(store.can("asset.write")).toBe(true);
  });
  it("denies write when the selected project has no grant", () => {
    const store = useSession();
    store.profile = { projectPermissions: { demo: ["asset.write"] } };
    store.projectId = "unknown";
    expect(store.can("asset.write")).toBe(false);
  });
});
