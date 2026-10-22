import { defineStore } from "pinia";
import { ref, computed } from "vue";
import { api, oidc, errorText } from "../lib/api";
export const useSession = defineStore("session", () => {
  const profile = ref<any>(null),
    projectId = ref(sessionStorage.getItem("asset-project") || "demo"),
    ready = ref(false),
    error = ref("");
  const authenticated = computed(() => !!profile.value);
  const projects = computed(() =>
    profile.value?.projects?.length
      ? profile.value.projects
      : [{ id: "demo", name: "示范项目" }],
  );
  async function load() {
    try {
      const user = await oidc.getUser();
      if (user && !user.expired) {
        profile.value = await api("/auth/me");
        if (profile.value.name === profile.value.subject)
          profile.value.name =
            user.profile.name ||
            user.profile.preferred_username ||
            profile.value.name;
        const ids = projects.value.map((p: any) =>
          typeof p === "string" ? p : p.id,
        );
        if (!ids.includes(projectId.value)) projectId.value = ids[0] || "demo";
      }
    } catch (e) {
      error.value = errorText(e);
      profile.value = null;
    } finally {
      ready.value = true;
    }
  }
  function selectProject(id: string) {
    projectId.value = id;
    sessionStorage.setItem("asset-project", id);
  }
  const can = (permission: string) => {
    const permissions =
      profile.value?.projectPermissions?.[projectId.value] || [];
    return permissions.includes("*") || permissions.includes(permission);
  };
  return {
    profile,
    projectId,
    ready,
    error,
    authenticated,
    projects,
    load,
    selectProject,
    can,
    login: () => oidc.signinRedirect(),
    logout: () => oidc.signoutRedirect(),
  };
});
