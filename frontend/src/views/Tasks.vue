<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  Plus,
  Refresh,
  Search,
  VideoPlay,
  Document,
  Edit,
  Clock,
  Check,
  View,
} from "@element-plus/icons-vue";
import { clone, api, errorText, query, listOf, allAssets } from "../lib/api";
import { listTasks, saveTask, type TaskDto } from "../lib/taskApi";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
import ErrorNotice from "../components/ErrorNotice.vue";
import RecordDetail from "../components/RecordDetail.vue";
const props = defineProps<{ kind: string }>(),
  s = useSession(),
  activeKind = ref(props.kind),
  rows = ref<TaskDto[]>([]),
  targets = ref<any[]>([]),
  clusters = ref<TaskDto[]>([]),
  allTasks = ref<TaskDto[]>([]),
  loading = ref(false),
  error = ref(""),
  search = ref(""),
  status = ref(""),
  page = ref(1),
  pageSize = ref(20);
const titles: Record<string, string> = {
  CLUSTER: "集群管理",
  VM: "虚拟机管理",
  ENVIRONMENT: "环境部署",
  DEPLOYMENT: "应用部署",
  DISTRIBUTION: "应用分发",
};
const types: Record<string, [string, string][]> = {
  CLUSTER: [
    ["PVE", "PVE 虚拟化集群"],
    ["K8S", "Kubernetes 集群"],
  ],
  VM: [
    ["GENERAL", "通用型虚拟机"],
    ["COMPUTE", "计算型虚拟机"],
    ["STORAGE", "存储型虚拟机"],
  ],
  ENVIRONMENT: [
    ["CONTAINER_PLATFORM", "容器平台部署"],
    ["MICROSERVICE_FRAMEWORK", "微服务框架安装"],
    ["CLIENT_ENVIRONMENT", "客户端环境配置"],
    ["HOTUPDATE_SERVER", "热更新服务器部署"],
  ],
  DEPLOYMENT: [["APPLICATION", "应用部署计划"]],
  DISTRIBUTION: [
    ["FLATPAK", "Flatpak 应用打包"],
    ["CLIENT_PUSH", "客户端应用推送"],
    ["FLUTTER_HOTUPDATE", "Flutter 热更新及动态加载"],
  ],
};
const typeLabel = (r: any) =>
  types[r.kind || activeKind.value]?.find((x) => x[0] === r.type)?.[1] ||
  r.type;
const filtered = computed(() =>
  rows.value.filter(
    (r) =>
      (!search.value ||
        `${r.name} ${r.id}`
          .toLowerCase()
          .includes(search.value.toLowerCase())) &&
      (!status.value || r.status === status.value),
  ),
);
const paged = computed(() =>
  filtered.value.slice(
    (page.value - 1) * pageSize.value,
    page.value * pageSize.value,
  ),
);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    rows.value = await listTasks(s.projectId, activeKind.value);
    const [a, t] = await Promise.all([
      allAssets(s.projectId, { admission: "ADMITTED" }),
      api("/tasks" + query({ projectId: s.projectId })),
    ]);
    targets.value = listOf(a);
    allTasks.value = listOf(t);
    clusters.value = allTasks.value.filter(
      (t) =>
        t.kind === "CLUSTER" && t.type === "PVE" && t.status === "SUCCEEDED",
    );
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
watch(activeKind, () => {
  page.value = 1;
  load();
});
const editing = ref(false),
  saving = ref(false),
  formError = ref(""),
  model = ref<any>({}),
  view = ref<any>(null),
  viewOpen = ref(false),
  executeOpen = ref(false),
  selectedTask = ref<any>(null),
  executeBusy = ref(false),
  validation = ref<any>(null),
  runIdempotency = ref("");
type Field = {
  key: string;
  label: string;
  kind?: string;
  options?: string[];
  required?: boolean;
  placeholder?: string;
};
const common: Record<string, Field[]> = {
  CLUSTER: [
    { key: "platformVersion", label: "平台版本", required: true },
    {
      key: "roles",
      label: "节点角色",
      required: true,
      placeholder: "3 控制节点 / 3 工作节点",
    },
    {
      key: "network",
      label: "管理网络",
      required: true,
      placeholder: "192.168.20.0/24",
    },
    { key: "serviceNetwork", label: "服务网络" },
    { key: "storage", label: "存储方案", required: true },
    { key: "credentialRef", label: "认证凭据引用", required: true },
  ],
  VM: [
    { key: "template", label: "虚拟机模板", required: true },
    { key: "cpu", label: "CPU 核数", kind: "number", required: true },
    { key: "memoryGb", label: "内存（GB）", kind: "number", required: true },
    { key: "diskGb", label: "磁盘（GB）", kind: "number", required: true },
    { key: "storagePool", label: "存储池", required: true },
    { key: "networkBridge", label: "网络桥接", required: true },
    { key: "ip", label: "IP 分配", required: true, placeholder: "自动分配" },
    { key: "credentialRef", label: "初始化凭据引用", required: true },
  ],
  DEPLOYMENT: [
    { key: "artifact", label: "软件制品引用", required: true },
    {
      key: "artifactDigest",
      label: "制品摘要 SHA-256",
      required: true,
      placeholder: "sha256:64位十六进制摘要",
    },
    { key: "environment", label: "目标环境", required: true },
    { key: "namespace", label: "命名空间", required: true },
    {
      key: "strategy",
      label: "发布策略",
      kind: "select",
      options: ["ROLLING", "CANARY", "BLUE_GREEN", "RECREATE"],
      required: true,
    },
    { key: "canaryRatio", label: "灰度比例（%）", kind: "number" },
    { key: "healthCheck", label: "健康检查地址", required: true },
    {
      key: "timeoutSeconds",
      label: "执行超时（秒）",
      kind: "number",
      required: true,
    },
    {
      key: "failurePolicy",
      label: "失败策略",
      kind: "select",
      options: ["ROLLBACK", "STOP", "CONTINUE"],
      required: true,
    },
    { key: "rollbackBaseline", label: "回滚基线" },
    { key: "parameters", label: "部署参数", kind: "textarea" },
  ],
};
const specific: Record<string, Field[]> = {
  CONTAINER_PLATFORM: [
    {
      key: "platform",
      label: "平台",
      kind: "select",
      options: ["Kubernetes", "Docker Swarm"],
      required: true,
    },
    { key: "version", label: "平台版本", required: true },
    { key: "nodeRoles", label: "节点角色", required: true },
    { key: "network", label: "网络插件", required: true },
    { key: "storage", label: "存储方案", required: true },
  ],
  MICROSERVICE_FRAMEWORK: [
    { key: "version", label: "Entando 版本", required: true },
    { key: "namespace", label: "命名空间", required: true },
    { key: "storage", label: "存储类" },
    { key: "domain", label: "访问域名" },
  ],
  CLIENT_ENVIRONMENT: [
    { key: "version", label: "Flatpak 版本", required: true },
    { key: "repository", label: "仓库地址", required: true },
    { key: "credentialRef", label: "GPG 密钥引用" },
    {
      key: "strategy",
      label: "配置策略",
      kind: "select",
      options: ["BATCH", "SEQUENTIAL"],
    },
  ],
  HOTUPDATE_SERVER: [
    { key: "version", label: "服务版本", required: true },
    { key: "port", label: "监听端口", kind: "number", required: true },
    { key: "directory", label: "更新包目录", required: true },
    { key: "credentialRef", label: "签名凭据引用" },
    {
      key: "strategy",
      label: "高可用方式",
      kind: "select",
      options: ["SINGLE", "LOAD_BALANCED"],
    },
  ],
  FLATPAK: [
    { key: "app", label: "应用 ID", required: true },
    { key: "artifact", label: "软件制品及版本", required: true },
    { key: "version", label: "包版本", required: true },
    {
      key: "architecture",
      label: "目标架构",
      kind: "select",
      options: ["x86_64", "aarch64"],
      required: true,
    },
    { key: "runtime", label: "运行时版本", required: true },
    { key: "repository", label: "目标仓库", required: true },
  ],
  CLIENT_PUSH: [
    { key: "app", label: "应用 ID", required: true },
    { key: "version", label: "应用版本", required: true },
    { key: "repository", label: "Flatpak 仓库", required: true },
    {
      key: "strategy",
      label: "推送策略",
      kind: "select",
      options: ["BATCH", "ALL"],
    },
    { key: "threshold", label: "失败阈值（%）", kind: "number" },
  ],
  FLUTTER_HOTUPDATE: [
    { key: "app", label: "Flutter 应用", required: true },
    { key: "baseVersion", label: "基础版本", required: true },
    { key: "artifact", label: "更新包", required: true },
    { key: "version", label: "补丁版本", required: true },
    { key: "credentialRef", label: "签名凭据引用", required: true },
    { key: "module", label: "动态功能模块", required: true },
    { key: "entry", label: "功能入口标识", required: true },
    {
      key: "strategy",
      label: "加载策略",
      kind: "select",
      options: ["AUTO", "ON_DEMAND", "CANARY"],
      required: true,
    },
    { key: "validation", label: "加载验证要求", required: true },
  ],
};
const requiredKeys: Record<string, string[]> = {
  CLUSTER: ["platformVersion", "roles", "network", "storage", "credentialRef"],
  VM: [
    "template",
    "cpu",
    "memoryGb",
    "diskGb",
    "storagePool",
    "networkBridge",
    "ip",
    "credentialRef",
  ],
  CONTAINER_PLATFORM: [
    "platform",
    "version",
    "nodeRoles",
    "network",
    "storage",
  ],
  MICROSERVICE_FRAMEWORK: ["version", "namespace", "storage", "domain"],
  CLIENT_ENVIRONMENT: ["version", "repository", "credentialRef", "strategy"],
  HOTUPDATE_SERVER: [
    "version",
    "port",
    "directory",
    "credentialRef",
    "strategy",
  ],
  DEPLOYMENT: [
    "artifact",
    "artifactDigest",
    "environment",
    "namespace",
    "strategy",
    "healthCheck",
    "timeoutSeconds",
    "failurePolicy",
  ],
  FLATPAK: [
    "app",
    "artifact",
    "version",
    "architecture",
    "runtime",
    "repository",
  ],
  CLIENT_PUSH: ["app", "version", "repository", "strategy", "threshold"],
  FLUTTER_HOTUPDATE: [
    "app",
    "baseVersion",
    "artifact",
    "version",
    "credentialRef",
    "module",
    "entry",
    "strategy",
    "validation",
  ],
};
const optionLabels: Record<string, string> = {
  ROLLING: "滚动发布",
  CANARY: "灰度发布",
  BLUE_GREEN: "蓝绿发布",
  RECREATE: "全量发布",
  ROLLBACK: "自动停止并回退",
  STOP: "自动停止",
  CONTINUE: "继续执行",
  BATCH: "分批执行",
  SEQUENTIAL: "逐台执行",
  SINGLE: "单节点",
  LOAD_BALANCED: "已有负载均衡",
  ALL: "全量推送",
  AUTO: "发布后自动加载",
  ON_DEMAND: "用户触发加载",
};
const fields = computed(() => {
    const list = common[model.value.kind] || specific[model.value.type] || [];
    const required =
      requiredKeys[model.value.kind] || requiredKeys[model.value.type] || [];
    return list.map((f) => ({
      ...f,
      required:
        required.includes(f.key) ||
        (f.key === "serviceNetwork" && model.value.type === "K8S"),
    }));
  }),
  availableTargets = computed(() =>
    targets.value.filter((a) => a.mode === model.value.mode),
  ),
  host = computed(() =>
    targets.value.find((a) => a.id === model.value.config?.hostId),
  );
function edit(row?: any) {
  model.value = clone(
    row || {
      projectId: s.projectId,
      name: "",
      kind: activeKind.value,
      type: types[activeKind.value][0][0],
      mode: "REAL",
      config: {
        targetIds: [],
        cpu: 8,
        memoryGb: 32,
        diskGb: 120,
        port: 9080,
        timeoutSeconds: 600,
        namespace: "default",
        strategy: activeKind.value === "DEPLOYMENT" ? "ROLLING" : undefined,
        failurePolicy: "STOP",
        architecture: "x86_64",
        schedule: {
          enabled: false,
          intervalMinutes: 60,
          serviceSubject: "asset-scheduler-demo",
        },
      },
    },
  );
  model.value.config ||= {};
  model.value.config.targetIds ||= [];
  model.value.config.dependsOnTaskIds ||= [];
  model.value.config.schedule ||= {
    enabled: false,
    intervalMinutes: 60,
    serviceSubject: "asset-scheduler-demo",
  };
  formError.value = "";
  editing.value = true;
}
async function save() {
  const missing = fields.value.filter(
    (f) =>
      f.required &&
      (model.value.config[f.key] === undefined ||
        model.value.config[f.key] === ""),
  );
  if (!model.value.name.trim()) {
    formError.value = "请填写名称";
    return;
  }
  if (missing.length) {
    formError.value = "请填写：" + missing.map((f) => f.label).join("、");
    return;
  }
  if (
    model.value.kind === "VM" &&
    (!model.value.config.clusterId || !model.value.config.hostId)
  ) {
    formError.value = "请选择已部署集群和指定物理机";
    return;
  }
  if (
    model.value.kind === "DEPLOYMENT" &&
    !/^sha256:[a-f\d]{64}$/i.test(model.value.config.artifactDigest)
  ) {
    formError.value = "制品摘要须为 sha256: 后接 64 位十六进制值";
    return;
  }
  if (model.value.kind !== "VM" && !model.value.config.targetIds?.length) {
    formError.value = "请至少选择一个目标资产";
    return;
  }
  for (const f of fields.value.filter((f) => f.kind === "number")) {
    const value = Number(model.value.config[f.key]);
    if (
      (f.required || model.value.config[f.key] !== undefined) &&
      (!Number.isInteger(value) || value < 1)
    ) {
      formError.value = f.label + "应为大于零的整数";
      return;
    }
  }
  if (
    model.value.type === "CLIENT_PUSH" &&
    model.value.config.threshold > 100
  ) {
    formError.value = "失败阈值应为1–100";
    return;
  }
  if (
    model.value.kind === "DEPLOYMENT" &&
    model.value.config.strategy === "CANARY" &&
    !(
      model.value.config.canaryRatio >= 1 &&
      model.value.config.canaryRatio <= 99
    )
  ) {
    formError.value = "灰度比例应为1–99";
    return;
  }
  if (
    model.value.kind === "DEPLOYMENT" &&
    model.value.config.failurePolicy === "ROLLBACK" &&
    !model.value.config.rollbackBaseline
  ) {
    formError.value = "自动回退需要选择成功部署的运行编号作为回退基线";
    return;
  }
  saving.value = true;
  try {
    const body = { ...model.value, projectId: s.projectId };
    if (body.kind === "CLUSTER")
      body.config.physicalAssetIds = body.config.targetIds;
    if (body.kind === "VM") body.config.targetIds = [body.config.hostId];
    await saveTask(body);
    editing.value = false;
    ElMessage.success("配置已保存，尚未执行");
    load();
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    saving.value = false;
  }
}
async function validateTask(row: any) {
  try {
    view.value = await api(
      `/tasks/${row.id}/validate` + query({ projectId: s.projectId }),
      {
        method: "POST",
        json: { projectId: s.projectId, version: row.version },
      },
    );
    viewOpen.value = true;
    load();
  } catch (e) {
    error.value = errorText(e);
  }
}
async function confirmTask(row: any) {
  try {
    await ElMessageBox.confirm(
      `确认冻结“${row.name}”第 ${row.version} 版配置？冻结后执行仍需单独确认。`,
      "部署配置确认",
      { confirmButtonText: "确认冻结" },
    );
    await api(`/tasks/${row.id}/confirm` + query({ projectId: s.projectId }), {
      method: "POST",
      json: { projectId: s.projectId, version: row.version },
    });
    ElMessage.success("配置版本已确认");
    load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = errorText(e);
  }
}
function prepareExecute(row: any) {
  selectedTask.value = row;
  validation.value = null;
  formError.value = "";
  runIdempotency.value = crypto.randomUUID();
  executeOpen.value = true;
}
async function execute() {
  if (!selectedTask.value) return;
  executeBusy.value = true;
  try {
    const r = await api(
      `/tasks/${selectedTask.value.id}/execute` +
        query({ projectId: s.projectId }),
      {
        method: "POST",
        json: {
          projectId: s.projectId,
          version: selectedTask.value.version,
          idempotencyKey: runIdempotency.value,
        },
      },
    );
    executeOpen.value = false;
    ElMessage.success("已收到运行回执：" + r.id);
    load();
    await showRun(r);
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    executeBusy.value = false;
  }
}
async function remove(row: any) {
  try {
    await ElMessageBox.confirm(
      `确认删除未运行配置“${row.name}”？历史记录会保留。`,
      "删除配置",
      { type: "warning" },
    );
    await api(
      `/tasks/${row.id}` +
        query({ projectId: s.projectId, version: row.version }),
      { method: "DELETE" },
    );
    load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = errorText(e);
  }
}
const rollbackBusy = ref(false),
  reconcileOpen = ref(false),
  reconcile = ref({ outcome: "FAILED", evidenceRef: "", comment: "" });
const rollbackKeys = new Map<string, string>();
async function rollback() {
  if (!run.value) return;
  try {
    await ElMessageBox.confirm(
      "确认对该成功部署执行回退？将创建独立运行并保留现有记录。",
      "回退部署",
      { type: "warning" },
    );
    rollbackBusy.value = true;
    const sourceId = run.value.id;
    if (!rollbackKeys.has(sourceId))
      rollbackKeys.set(sourceId, crypto.randomUUID());
    const r = await api(`/runs/${run.value.id}/rollback`, {
      method: "POST",
      json: {
        version: run.value.taskVersion,
        idempotencyKey: rollbackKeys.get(sourceId),
      },
    });
    rollbackKeys.delete(sourceId);
    await showRun(r);
    load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") formError.value = errorText(e);
  } finally {
    rollbackBusy.value = false;
  }
}
async function reconcileRun() {
  if (!reconcile.value.evidenceRef.trim() || !reconcile.value.comment.trim()) {
    formError.value = "请填写证据引用和核查说明";
    return;
  }
  try {
    run.value = await api(`/runs/${run.value.id}/reconcile`, {
      method: "POST",
      json: reconcile.value,
    });
    reconcileOpen.value = false;
    showRun(run.value);
    load();
  } catch (e) {
    formError.value = errorText(e);
  }
}
const historyOpen = ref(false),
  runs = ref<any[]>([]),
  runOpen = ref(false),
  run = ref<any>(null),
  logs = ref<any[]>([]),
  logLoading = ref(false);
async function history() {
  historyOpen.value = true;
  try {
    runs.value = listOf(
      await api("/runs" + query({ projectId: s.projectId })),
    ).filter((r) => rows.value.some((t) => t.id === r.taskId));
  } catch (e) {
    error.value = errorText(e);
  }
}
async function showRun(r: any) {
  runOpen.value = true;
  run.value = r;
  logLoading.value = true;
  try {
    const [data, log] = await Promise.all([
      api(`/runs/${r.id}` + query({ projectId: s.projectId })),
      api(`/runs/${r.id}/logs` + query({ projectId: s.projectId })),
    ]);
    run.value = data;
    logs.value = listOf(log);
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    logLoading.value = false;
  }
}
function detail(row: any) {
  view.value = row;
  viewOpen.value = true;
}
let refreshTimer: ReturnType<typeof setInterval> | undefined;
watch(
  () => runOpen.value,
  (open) => {
    if (refreshTimer) clearInterval(refreshTimer);
    if (open)
      refreshTimer = setInterval(() => {
        if (
          run.value &&
          ["QUEUED", "RUNNING", "PENDING"].includes(run.value.status) &&
          !logLoading.value
        )
          showRun(run.value);
      }, 3000);
  },
);
onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer);
});
onMounted(load);
</script>
<template>
  <section class="page-header">
    <div>
      <p class="eyebrow">
        {{
          activeKind === "CLUSTER" || activeKind === "VM"
            ? "COMPUTE RESOURCES"
            : "DELIVERY WORKSPACE"
        }}
      </p>
      <h1>{{ props.kind === "CLUSTER" ? "计算资源" : titles[activeKind] }}</h1>
      <p>配置可追踪，执行有回执，版本可核对</p>
    </div>
    <el-button :icon="Clock" @click="history">{{
      activeKind === "DISTRIBUTION" ? "分发记录" : "部署记录"
    }}</el-button>
  </section>
  <el-tabs
    v-if="props.kind === 'CLUSTER'"
    v-model="activeKind"
    class="compute-tabs"
    ><el-tab-pane name="CLUSTER" label="集群管理" /><el-tab-pane
      name="VM"
      label="虚拟机管理" /></el-tabs
  ><ErrorNotice :message="error" @retry="load" />
  <section class="panel">
    <div class="filterbar">
      <el-input
        v-model="search"
        placeholder="搜索名称或编号"
        :prefix-icon="Search"
        clearable
        class="search-input"
        @input="page = 1"
      /><el-select
        v-model="status"
        placeholder="全部状态"
        clearable
        @change="page = 1"
        ><el-option
          v-for="x in [
            'DRAFT',
            'VALIDATED',
            'CONFIRMED',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
          ]"
          :key="x"
          :value="x"
          :label="
            (
              {
                DRAFT: '草稿',
                VALIDATED: '已校验',
                CONFIRMED: '已确认',
                RUNNING: '执行中',
                SUCCEEDED: '成功',
                FAILED: '失败',
              } as any
            )[x]
          " /></el-select
      ><span class="spacer"></span
      ><el-button :icon="Refresh" @click="load" /><el-button
        type="primary"
        :icon="Plus"
        @click="edit()"
        >{{
          activeKind === "CLUSTER"
            ? "创建集群"
            : activeKind === "VM"
              ? "创建虚拟机"
              : "新建" + titles[activeKind]
        }}</el-button
      >
    </div>
    <el-table :data="paged" v-loading="loading" stripe
      ><el-table-column label="名称 / 编号" min-width="225"
        ><template #default="{ row }"
          ><button class="text-link" @click="detail(row)">{{ row.name }}</button
          ><small class="subline mono">{{ row.id }}</small></template
        ></el-table-column
      ><el-table-column label="类型" min-width="155"
        ><template #default="{ row }">{{
          typeLabel(row)
        }}</template></el-table-column
      ><el-table-column
        label="目标 / 配置"
        min-width="190"
        show-overflow-tooltip
        ><template #default="{ row }"
          >{{
            row.config?.targetIds
              ?.map(
                (id: string) => targets.find((t) => t.id === id)?.name || id,
              )
              .join("、") || "未选择目标"
          }}<small class="subline">{{
            row.config?.artifact ||
            row.config?.platformVersion ||
            row.config?.template ||
            row.config?.version ||
            "—"
          }}</small></template
        ></el-table-column
      ><el-table-column label="模式" width="105"
        ><template #default="{ row }"
          ><StatusTag :value="row.mode" /></template></el-table-column
      ><el-table-column label="状态" width="105"
        ><template #default="{ row }"
          ><StatusTag :value="row.status" /></template></el-table-column
      ><el-table-column label="版本" width="65"
        ><template #default="{ row }"
          >v{{ row.version }}</template
        ></el-table-column
      ><el-table-column label="操作" width="270" fixed="right"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="edit(row)">配置</el-button
          ><el-button link type="primary" @click="validateTask(row)"
            >校验</el-button
          ><el-button
            v-if="activeKind === 'DEPLOYMENT'"
            link
            type="primary"
            @click="confirmTask(row)"
            >确认</el-button
          ><el-button
            link
            type="primary"
            @click="prepareExecute(row)"
            :disabled="row.status === 'RUNNING'"
            >执行</el-button
          ><el-button
            link
            type="danger"
            @click="remove(row)"
            :disabled="row.status === 'RUNNING'"
            >删除</el-button
          ></template
        ></el-table-column
      ><template #empty
        ><el-empty
          description="暂无配置，创建后可校验并执行"
          :image-size="76" /></template
    ></el-table>
    <div class="pagination">
      <span>{{ filtered.length }} 条配置</span
      ><el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 30, 50]"
        :total="filtered.length"
        layout="sizes, prev, pager, next"
      />
    </div>
  </section>
  <el-drawer
    v-model="editing"
    :title="(model.id ? '修改' : '新建') + (titles[model.kind] || '配置')"
    size="800px"
    :close-on-click-modal="!saving"
    ><el-alert
      v-if="model.mode === 'SIMULATED'"
      type="warning"
      title="SIMULATED · 模拟执行仅验证流程，不会操作真实设备。"
      :closable="false"
    /><el-form label-position="top" class="form-grid"
      ><h3 class="section-heading">基本配置</h3>
      <el-form-item label="名称 *"
        ><el-input v-model="model.name" /></el-form-item
      ><el-form-item label="类型 *"
        ><el-select v-model="model.type"
          ><el-option
            v-for="[value, label] in types[model.kind]"
            :key="value"
            :value="value"
            :label="label" /></el-select></el-form-item
      ><el-form-item label="执行模式 *"
        ><el-select
          v-model="model.mode"
          @change="
            model.config.targetIds = [];
            model.config.hostId = '';
          "
          ><el-option value="REAL" label="真实执行 REAL" /><el-option
            value="SIMULATED"
            label="模拟执行 SIMULATED" /></el-select></el-form-item
      ><el-form-item v-if="model.kind !== 'VM'" label="目标资产 *" class="full"
        ><el-select
          v-model="model.config.targetIds"
          multiple
          filterable
          placeholder="选择已入库且可用的资产"
          ><el-option
            v-for="t in availableTargets"
            :key="t.id"
            :value="t.id"
            :label="`${t.name} · ${t.id}`" /></el-select
        ><small
          >仅显示与执行模式一致的资产；可用性和权限以后台校验为准。</small
        ></el-form-item
      ><template v-else
        ><el-form-item label="所属已部署集群 *"
          ><el-select v-model="model.config.clusterId" filterable
            ><el-option
              v-for="c in clusters"
              :key="c.id"
              :label="c.name"
              :value="c.id" /></el-select></el-form-item
        ><el-form-item label="指定物理机 *"
          ><el-select v-model="model.config.hostId" filterable
            ><el-option
              v-for="h in availableTargets.filter(
                (t) =>
                  t.type === 'SERVER' &&
                  (!model.config.clusterId ||
                    clusters
                      .find((c) => c.id === model.config.clusterId)
                      ?.config?.physicalAssetIds?.includes(t.id)),
              )"
              :key="h.id"
              :value="h.id"
              :label="`${h.name} · ${h.ip || h.id}`" /></el-select
        ></el-form-item>
        <div v-if="host" class="resource-summary full">
          <b>{{ host.name }}</b
          ><StatusTag :value="host.resourceStatus" /><span
            >CPU {{ host.technical?.cpu || "未采集" }} · 内存
            {{ host.technical?.memory || "未采集" }} · 磁盘
            {{ host.technical?.disk || "未采集" }}</span
          ><small
            >后台执行前重新核验宿主容量与资源占用，不将未知数据视为可用。</small
          >
        </div></template
      >
      <h3 class="section-heading">{{ typeLabel(model) }}参数</h3>
      <el-form-item
        v-for="f in fields"
        :key="f.key"
        :label="f.label + (f.required ? ' *' : '')"
        :class="f.kind === 'textarea' ? 'full' : ''"
        ><el-select
          v-if="f.key === 'environment'"
          v-model="model.config.environment"
          placeholder="选择已成功部署的环境"
          ><el-option
            v-for="t in allTasks.filter(
              (t) =>
                t.kind === 'ENVIRONMENT' &&
                t.status === 'SUCCEEDED' &&
                t.mode === model.mode,
            )"
            :key="t.id"
            :value="t.id"
            :label="t.name" /></el-select
        ><el-select
          v-else-if="f.key === 'template'"
          v-model="model.config.template"
          filterable
          allow-create
          placeholder="选择或输入宿主已登记模板"
          ><el-option
            v-for="t in [
              'ubuntu-24.04',
              'debian-12',
              'rocky-9',
              'openEuler-24.03',
            ]"
            :key="t"
            :value="t" /></el-select
        ><el-select
          v-else-if="f.kind === 'select'"
          v-model="model.config[f.key]"
          ><el-option
            v-for="o in f.options"
            :key="o"
            :value="o"
            :label="optionLabels[o] || o" /></el-select
        ><el-input-number
          v-else-if="f.kind === 'number'"
          v-model="model.config[f.key]"
          :min="0"
          :controls="false" /><el-input
          v-else
          v-model="model.config[f.key]"
          :type="f.kind === 'textarea' ? 'textarea' : 'text'"
          :placeholder="f.placeholder"
          :rows="3" /></el-form-item
      ><el-form-item
        v-if="model.kind === 'DEPLOYMENT'"
        label="生产环境控制"
        class="full"
        ><el-switch
          v-model="model.config.production"
          active-text="生产发布：须冻结并确认配置版本" /></el-form-item
      ><el-form-item
        v-if="model.kind === 'ENVIRONMENT'"
        label="前置任务依赖"
        class="full"
        ><el-select v-model="model.config.dependsOnTaskIds" multiple filterable
          ><el-option
            v-for="t in allTasks.filter((t) => t.id !== model.id)"
            :key="t.id"
            :value="t.id"
            :label="t.name" /></el-select></el-form-item
      ><el-form-item label="说明" class="full"
        ><el-input type="textarea" v-model="model.config.description"
      /></el-form-item>
      <h3 class="section-heading">定时任务</h3>
      <el-form-item label="启用周期执行"
        ><el-switch v-model="model.config.schedule.enabled" /><small
          >启用后由授权服务主体执行，需后台校验服务权限。</small
        ></el-form-item
      ><el-form-item label="执行间隔（分钟）"
        ><el-input-number
          v-model="model.config.schedule.intervalMinutes"
          :min="1" /></el-form-item
      ><el-form-item label="执行服务主体" class="full"
        ><el-input
          v-model="
            model.config.schedule.serviceSubject
          " /></el-form-item></el-form
    ><ErrorNotice :message="formError" @retry="save" /><template #footer
      ><el-button @click="editing = false" :disabled="saving">取消</el-button
      ><el-button type="primary" @click="save" :loading="saving"
        >保存配置</el-button
      ></template
    ></el-drawer
  >
  <el-dialog
    v-model="executeOpen"
    title="执行确认"
    width="720px"
    :close-on-click-modal="!executeBusy"
    ><el-alert
      :type="selectedTask?.mode === 'SIMULATED' ? 'warning' : 'info'"
      :closable="false"
      :title="
        selectedTask?.mode === 'SIMULATED'
          ? 'SIMULATED · 此任务仅执行模拟流程，不操作真实设备。'
          : 'REAL · 本次执行会调用目标连接器，请核对目标与冻结版本。'
      "
    /><el-descriptions v-if="selectedTask" :column="2" border class="mt16"
      ><el-descriptions-item label="任务">{{
        selectedTask.name
      }}</el-descriptions-item
      ><el-descriptions-item label="配置版本"
        >v{{ selectedTask.version }}</el-descriptions-item
      ><el-descriptions-item label="目标" :span="2">{{
        selectedTask.config?.targetIds?.join("、")
      }}</el-descriptions-item
      ><el-descriptions-item label="已确认版本">{{
        selectedTask.confirmedVersion || "—"
      }}</el-descriptions-item
      ><el-descriptions-item label="模式">{{
        selectedTask.mode
      }}</el-descriptions-item></el-descriptions
    ><ErrorNotice :message="formError" @retry="execute" /><template #footer
      ><el-button @click="executeOpen = false" :disabled="executeBusy"
        >取消</el-button
      ><el-button type="primary" :loading="executeBusy" @click="execute"
        >确认执行</el-button
      ></template
    ></el-dialog
  ><el-dialog v-model="reconcileOpen" title="核查未知执行结果" width="600px"
    ><el-form label-position="top"
      ><el-form-item label="核查结论"
        ><el-select v-model="reconcile.outcome"
          ><el-option label="已成功" value="SUCCEEDED" /><el-option
            label="已失败"
            value="FAILED" /></el-select></el-form-item
      ><el-form-item label="证据引用 *"
        ><el-input
          v-model="reconcile.evidenceRef"
          placeholder="受控系统回执或验证证据编号" /></el-form-item
      ><el-form-item label="核查说明 *"
        ><el-input
          v-model="reconcile.comment"
          type="textarea" /></el-form-item></el-form
    ><ErrorNotice :message="formError" @retry="reconcileRun" /><template #footer
      ><el-button @click="reconcileOpen = false">取消</el-button
      ><el-button type="primary" @click="reconcileRun"
        >提交核查结论</el-button
      ></template
    ></el-dialog
  ><el-drawer v-model="viewOpen" title="配置与校验详情" size="760px"
    ><RecordDetail :record="view"
  /></el-drawer>
  <el-drawer
    v-model="historyOpen"
    :title="activeKind === 'DISTRIBUTION' ? '分发记录' : '部署记录'"
    size="920px"
    ><el-table :data="runs"
      ><el-table-column prop="id" label="运行编号" min-width="190"
        ><template #default="{ row }"
          ><button class="text-link" @click="showRun(row)">
            {{ row.id }}
          </button></template
        ></el-table-column
      ><el-table-column
        prop="taskId"
        label="任务"
        min-width="160" /><el-table-column label="模式" width="110"
        ><template #default="{ row }"
          ><StatusTag :value="row.mode" /></template></el-table-column
      ><el-table-column label="状态" width="100"
        ><template #default="{ row }"
          ><StatusTag :value="row.status" /></template></el-table-column
      ><el-table-column
        prop="createdAt"
        label="开始时间"
        min-width="180" /></el-table
    ><el-empty v-if="!runs.length" description="暂无运行记录" /></el-drawer
  ><el-drawer v-model="runOpen" title="运行详情与日志" size="840px"
    ><el-alert
      v-if="run?.mode === 'SIMULATED'"
      type="warning"
      title="SIMULATED · 模拟执行回执"
      :closable="false" />
    <div class="run-heading">
      <StatusTag :value="run?.status" /><span>{{ run?.id }}</span
      ><el-button
        v-if="
          run?.status === 'SUCCEEDED' &&
          run?.snapshot?.kind === 'DEPLOYMENT' &&
          !run?.snapshot?.rollback
        "
        type="warning"
        :loading="rollbackBusy"
        @click="rollback"
        >回退部署</el-button
      ><el-button v-if="run?.status === 'UNKNOWN'" @click="reconcileOpen = true"
        >核查未知结果</el-button
      ><el-button :icon="Refresh" @click="showRun(run)"
        >刷新状态与日志</el-button
      >
    </div>
    <el-tabs
      ><el-tab-pane label="执行日志"
        ><div v-loading="logLoading" class="log-view">
          <div v-for="(line, i) in logs" :key="line.id || i">
            <span>{{ line.createdAt || line.timestamp || "" }}</span
            ><b :class="line.level === 'ERROR' ? 'log-error' : ''">
              {{ line.level || "INFO" }} </b
            >{{ line.message || line.text || JSON.stringify(line) }}
          </div>
          <span v-if="!logs.length">暂无日志，运行开始后可刷新查看。</span>
        </div></el-tab-pane
      ><el-tab-pane label="目标与执行快照"
        ><RecordDetail :record="run" /></el-tab-pane></el-tabs
    ><ErrorNotice :message="formError" @retry="showRun(run)"
  /></el-drawer>
</template>
