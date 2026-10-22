import { createRouter, createWebHistory } from "vue-router";
const Dashboard = () => import("./views/Dashboard.vue");
const Assets = () => import("./views/Assets.vue");
const Tasks = () => import("./views/Tasks.vue");
const Alarms = () => import("./views/Alarms.vue");
const Settings = () => import("./views/Settings.vue");
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/overview" },
    {
      path: "/auth/callback",
      component: { template: '<div class="callback">正在完成安全登录…</div>' },
    },
    { path: "/overview", component: Dashboard, meta: { title: "资产总览" } },
    { path: "/assets", component: Assets, meta: { title: "资产管理" } },
    {
      path: "/compute",
      component: Tasks,
      props: { kind: "CLUSTER" },
      meta: { title: "计算资源" },
    },
    {
      path: "/environment",
      component: Tasks,
      props: { kind: "ENVIRONMENT" },
      meta: { title: "环境部署" },
    },
    {
      path: "/deployment",
      component: Tasks,
      props: { kind: "DEPLOYMENT" },
      meta: { title: "应用部署" },
    },
    {
      path: "/distribution",
      component: Tasks,
      props: { kind: "DISTRIBUTION" },
      meta: { title: "应用分发" },
    },
    { path: "/alarms", component: Alarms, meta: { title: "告警管理" } },
    {
      path: "/settings",
      component: Settings,
      meta: { title: "连接配置与审计" },
    },
    { path: "/:pathMatch(.*)*", redirect: "/overview" },
  ],
});
