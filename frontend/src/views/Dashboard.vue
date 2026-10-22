<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from "vue";
import { useRouter } from "vue-router";
import {
  Refresh,
  ArrowRight,
  Box,
  Cpu,
  Promotion,
  Bell,
} from "@element-plus/icons-vue";
import * as echarts from "echarts/core";
import { PieChart } from "echarts/charts";
import { TooltipComponent, GraphicComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
echarts.use([PieChart, TooltipComponent, GraphicComponent, CanvasRenderer]);
import { api, errorText, query } from "../lib/api";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
import ErrorNotice from "../components/ErrorNotice.vue";
const s = useSession(),
  router = useRouter(),
  data = ref<any>({
    counts: {},
    assetTypes: [],
    recentAssets: [],
    recentRuns: [],
    alarms: [],
  }),
  loading = ref(false),
  error = ref(""),
  chartElement = ref<HTMLElement>(),
  updated = ref("");
let chart: echarts.ECharts | undefined;
const names: Record<string, string> = {
  SERVER: "服务器",
  CLIENT: "客户端",
  NETWORK: "网络设备",
  SOFTWARE: "软件资产",
};
const cards = computed(() => [
  {
    label: "资产总数",
    value: data.value.counts.assets,
    sub: `已入库 ${data.value.counts.admitted ?? 0} · 待入库 ${data.value.counts.pending ?? 0}`,
    icon: Box,
    path: "/assets",
  },
  {
    label: "计算资源",
    value: (data.value.counts.clusters ?? 0) + (data.value.counts.vms ?? 0),
    sub: `集群 ${data.value.counts.clusters ?? 0} · 虚拟机 ${data.value.counts.vms ?? 0}`,
    icon: Cpu,
    path: "/compute",
  },
  {
    label: "部署与分发任务",
    value:
      (data.value.counts.environments ?? 0) +
      (data.value.counts.deployments ?? 0) +
      (data.value.counts.distributions ?? 0),
    sub: `执行中 ${data.value.counts.runningTasks ?? 0}`,
    icon: Promotion,
    path: "/deployment",
  },
  {
    label: "待处理告警",
    value: data.value.counts.openAlarms,
    sub: "查看并跟踪处理进展",
    icon: Bell,
    path: "/alarms",
  },
]);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    data.value = await api("/dashboard" + query({ projectId: s.projectId }));
    updated.value = new Date().toLocaleTimeString("zh-CN");
    await nextTick();
    draw();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
function draw() {
  if (!chartElement.value) return;
  chart ||= echarts.init(chartElement.value);
  chart.setOption({
    color: ["#4C23BA", "#9473D5", "#BAB0DA", "#E3DDF0"],
    tooltip: { trigger: "item", formatter: "{b}: {c} 项" },
    series: [
      {
        type: "pie",
        radius: ["65%", "86%"],
        center: ["50%", "50%"],
        label: { show: false },
        itemStyle: { borderColor: "#fff", borderWidth: 3 },
        data: data.value.assetTypes.map((x: any) => ({
          name: names[x.type] || x.type,
          value: x.count,
        })),
      },
    ],
    graphic: {
      type: "text",
      left: "center",
      top: "41%",
      style: {
        text: String(data.value.counts.assets ?? 0),
        font: "600 31px sans-serif",
        fill: "#24212C",
      },
    },
  });
}
const resize = () => chart?.resize();
onMounted(() => {
  load();
  window.addEventListener("resize", resize);
});
onBeforeUnmount(() => {
  chart?.dispose();
  window.removeEventListener("resize", resize);
});
</script>
<template>
  <section class="page-header">
    <div>
      <p class="eyebrow">ASSET INTELLIGENCE</p>
      <h1>资产总览</h1>
      <p>掌握资产全貌，追踪每一次交付</p>
    </div>
    <div class="header-tools">
      <small v-if="updated">更新于 {{ updated }}</small
      ><el-button :icon="Refresh" @click="load" :loading="loading"
        >刷新数据</el-button
      >
    </div>
  </section>
  <ErrorNotice :message="error" @retry="load" />
  <div class="stats-grid" v-loading="loading">
    <button
      v-for="c in cards"
      :key="c.label"
      class="stat-card"
      @click="router.push(c.path)"
    >
      <div class="stat-top">
        <span>{{ c.label }}</span
        ><el-icon><component :is="c.icon" /></el-icon>
      </div>
      <strong>{{ c.value ?? "—" }}</strong>
      <div class="stat-foot">
        <span>{{ c.sub }}</span
        ><el-icon><ArrowRight /></el-icon>
      </div>
    </button>
  </div>
  <div class="dashboard-columns">
    <section class="panel">
      <div class="panel-title">
        <h2>资产情况</h2>
        <router-link to="/assets"
          >查看全部 <el-icon><ArrowRight /></el-icon
        ></router-link>
      </div>
      <div class="asset-chart-content">
        <div
          ref="chartElement"
          class="asset-chart"
          role="img"
          aria-label="资产类型分布环形图"
        ></div>
        <div class="asset-legend">
          <div v-for="(row, i) in data.assetTypes" :key="row.type">
            <i
              :style="{
                background: ['#4C23BA', '#9473D5', '#BAB0DA', '#E3DDF0'][
                  Number(i) % 4
                ],
              }"
            ></i
            ><span>{{ names[row.type] || row.type }}</span
            ><strong>{{ row.count }}</strong>
          </div>
          <el-empty
            v-if="!data.assetTypes.length"
            description="暂无资产"
            :image-size="45"
          />
        </div>
      </div>
      <div class="panel-bottom-note">统一分类维护 · 资源模式与来源可追溯</div>
    </section>
    <section class="panel">
      <div class="panel-title">
        <h2>部署任务情况</h2>
        <router-link to="/deployment"
          >查看全部 <el-icon><ArrowRight /></el-icon
        ></router-link>
      </div>
      <div class="deployment-summary">
        <router-link
          v-for="item in [
            { name: '环境部署', key: 'environments', path: '/environment' },
            { name: '应用部署', key: 'deployments', path: '/deployment' },
            { name: '应用分发', key: 'distributions', path: '/distribution' },
          ]"
          :key="item.key"
          :to="item.path"
          ><span>{{ item.name }}</span
          ><strong>{{ data.counts[item.key] ?? 0 }}</strong
          ><el-progress
            :percentage="
              Math.min(
                100,
                (100 * (data.counts[item.key] || 0)) /
                  Math.max(
                    1,
                    (data.counts.environments || 0) +
                      (data.counts.deployments || 0) +
                      (data.counts.distributions || 0),
                  ),
              )
            "
            :show-text="false"
            :stroke-width="6"
        /></router-link>
      </div>
      <div class="panel-bottom-note">
        <i class="live-dot"></i>执行以服务回执为准；模拟任务始终保留模式标识。
      </div>
    </section>
  </div>
  <section class="panel">
    <div class="panel-title">
      <h2>告警记录 <small>按发生时间倒序</small></h2>
      <router-link to="/alarms"
        >查看全部 <el-icon><ArrowRight /></el-icon
      ></router-link>
    </div>
    <el-table :data="data.alarms" stripe
      ><el-table-column label="级别" width="90"
        ><template #default="{ row }"
          ><StatusTag :value="row.severity" /></template></el-table-column
      ><el-table-column prop="message" label="告警内容" min-width="250"
        ><template #default="{ row }">{{
          row.message || row.content
        }}</template></el-table-column
      ><el-table-column
        prop="targetId"
        label="来源对象"
        min-width="170" /><el-table-column label="状态" width="110"
        ><template #default="{ row }"
          ><StatusTag :value="row.status" /></template></el-table-column
      ><el-table-column
        prop="owner"
        label="负责人"
        width="100" /><el-table-column
        prop="createdAt"
        label="发生时间"
        min-width="180" /><template #empty
        ><el-empty description="暂无告警记录" :image-size="58" /></template
    ></el-table>
  </section>
  <div class="dashboard-columns">
    <section class="panel">
      <div class="panel-title">
        <h2>最近更新资产</h2>
        <router-link to="/assets"
          >资产管理 <el-icon><ArrowRight /></el-icon
        ></router-link>
      </div>
      <div v-for="row in data.recentAssets" :key="row.id" class="recent-row">
        <span>{{ row.name }}</span
        ><small>{{ names[row.type] || row.type }}</small
        ><StatusTag :value="row.mode" />
      </div>
      <el-empty
        v-if="!data.recentAssets.length"
        description="暂无更新记录"
        :image-size="45"
      />
    </section>
    <section class="panel">
      <div class="panel-title">
        <h2>最近运行</h2>
        <router-link to="/deployment"
          >部署工作台 <el-icon><ArrowRight /></el-icon
        ></router-link>
      </div>
      <div v-for="row in data.recentRuns" :key="row.id" class="recent-row">
        <span class="mono">{{ row.taskId || row.id }}</span
        ><StatusTag :value="row.mode" /><StatusTag :value="row.status" />
      </div>
      <el-empty
        v-if="!data.recentRuns.length"
        description="暂无运行回执"
        :image-size="45"
      />
    </section>
  </div>
</template>
