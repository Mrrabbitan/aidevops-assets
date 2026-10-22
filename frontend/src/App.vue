<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Box,
  Grid,
  Cpu,
  Platform,
  Promotion,
  Monitor,
  Bell,
  Setting,
  Refresh,
  SwitchButton,
  ArrowRight,
} from "@element-plus/icons-vue";
import StatusTag from "./components/StatusTag.vue";
import { useSession } from "./stores/session";
import { oidc, api, listOf, errorText, query } from "./lib/api";
const session = useSession(),
  route = useRoute(),
  router = useRouter(),
  busy = ref(true),
  loginError = ref(""),
  notifications = ref<any[]>([]),
  noticeOpen = ref(false);
const nav = [
  ["/overview", "资产总览", Grid],
  ["/assets", "资产管理", Box],
  ["/compute", "计算资源", Cpu],
  ["/environment", "环境部署", Platform],
  ["/deployment", "应用部署", Monitor],
  ["/distribution", "应用分发", Promotion],
  ["/alarms", "告警管理", Bell],
] as const;
onMounted(async () => {
  try {
    if (location.pathname === "/auth/callback") {
      await oidc.signinRedirectCallback();
      await router.replace("/overview");
    }
    await session.load();
  } catch (e) {
    loginError.value = errorText(e);
  } finally {
    busy.value = false;
  }
});
async function login() {
  try {
    await session.login();
  } catch (e) {
    loginError.value = errorText(e);
  }
}
async function showNotifications() {
  noticeOpen.value = true;
  try {
    notifications.value = listOf(
      await api("/notifications" + query({ projectId: session.projectId })),
    );
  } catch (e) {
    loginError.value = errorText(e);
  }
}
async function read(n: any) {
  try {
    await api(`/notifications/${n.id}/read`, {
      method: "POST",
      json: { projectId: session.projectId },
    });
    n.isRead = true;
  } catch (e) {
    loginError.value = errorText(e);
  }
}
</script>
<template>
  <div v-if="busy" class="loading-screen">
    <el-icon class="is-loading"><Refresh /></el-icon>
    <p>正在连接身份认证服务</p>
  </div>
  <main v-else-if="!session.authenticated" class="login-page">
    <div class="login-brand">A /</div>
    <section>
      <p class="eyebrow">AIDEVOPS · SOFTWARE FACTORY</p>
      <h1>资产清晰，<br />交付有据。</h1>
      <p class="login-description">
        软件工厂开发运维一体化平台<br />资产、计算资源与部署全生命周期管理
      </p>
      <div class="login-card">
        <span class="security-dot"></span><b>统一身份认证</b>
        <p>使用项目授权账户进入资产台账</p>
        <el-alert
          v-if="loginError || session.error"
          :title="loginError || session.error"
          type="error"
          :closable="false"
        /><el-button type="primary" size="large" @click="login"
          >登录工作台 <el-icon><ArrowRight /></el-icon></el-button
        ><small>OAuth 2.0 · PKCE · 项目权限隔离</small>
      </div>
    </section>
    <div class="login-decoration">
      <div class="deco-line"></div>
      <div class="deco-box">
        <Box /><span>ASSET<br />INTELLIGENCE</span>
      </div>
      <p>从发现到入库，从配置到交付<br />每一次变化，均有迹可循。</p>
    </div>
    <footer>AIDevOps / Asset Ledger</footer>
  </main>
  <div v-else class="shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">A</span><b>软件工厂开发运维一体化平台</b>
      </div>
      <nav class="top-modules">
        <span>项目管理</span><span>源码仓库</span><span>制品仓库</span
        ><span>集成部署</span><span>质量管控</span><strong>资产台账</strong
        ><span>监测监控</span>
      </nav>
      <div class="top-actions">
        <el-select
          :model-value="session.projectId"
          @update:model-value="session.selectProject"
          aria-label="当前项目"
          class="project-select"
          ><el-option
            v-for="p in session.projects"
            :key="typeof p === 'string' ? p : p.id"
            :label="typeof p === 'string' ? p : p.name || p.id"
            :value="typeof p === 'string' ? p : p.id" /></el-select
        ><el-button text @click="showNotifications" aria-label="通知中心"
          ><el-icon><Bell /></el-icon></el-button
        ><el-dropdown
          ><button class="avatar" aria-label="账户菜单">
            {{ (session.profile?.name || "用").slice(0, 1) }}</button
          ><template #dropdown
            ><el-dropdown-menu
              ><el-dropdown-item disabled>{{
                session.profile?.name
              }}</el-dropdown-item
              ><el-dropdown-item @click="session.logout"
                >退出登录</el-dropdown-item
              ></el-dropdown-menu
            ></template
          ></el-dropdown
        >
      </div>
    </header>
    <aside class="sidebar">
      <div class="sidebar-title">资产台账 <span>ASSETS</span></div>
      <router-link v-for="[path, label, icon] in nav" :key="path" :to="path"
        ><el-icon><component :is="icon" /></el-icon
        ><span>{{ label }}</span></router-link
      >
      <div class="sidebar-footer">
        <router-link to="/settings"
          ><el-icon><Setting /></el-icon>连接配置与审计</router-link
        >
        <div class="session-state"><i></i>项目数据访问受控</div>
      </div>
    </aside>
    <div class="breadcrumb">
      <span>资产台账</span><el-icon><ArrowRight /></el-icon
      ><b>{{ route.meta.title }}</b
      ><span class="breadcrumb-right"
        >{{ session.projectId }} <span class="separator">/</span>
        {{ session.profile?.name }}</span
      >
    </div>
    <main class="workspace">
      <router-view :key="`${route.path}:${session.projectId}`" />
    </main>
    <el-drawer v-model="noticeOpen" title="通知中心" size="480px"
      ><el-alert
        v-if="loginError"
        type="error"
        :title="loginError"
        :closable="false"
      /><el-empty v-if="!notifications.length" description="暂无通知" />
      <article v-for="n in notifications" :key="n.id" class="notification">
        <b>{{ n.title || n.subject || "系统通知" }}</b>
        <p>{{ n.message || n.body }}</p>
        <div class="notification-state">
          <span>{{
            ({ IN_APP: "站内通知", EMAIL: "邮件", WEBHOOK: "Webhook" } as any)[
              n.channel
            ] || n.channel
          }}</span
          ><StatusTag :value="n.status" />
        </div>
        <small v-if="n.lastError" class="subline">{{ n.lastError }}</small>
        <small>{{ n.createdAt }}</small
        ><el-button
          v-if="!n.isRead && !n.readAt"
          link
          type="primary"
          @click="read(n)"
          >标为已读</el-button
        >
      </article></el-drawer
    >
  </div>
</template>
