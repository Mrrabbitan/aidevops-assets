<script setup lang="ts">
import { ref, reactive, computed, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  Search,
  Plus,
  Download,
  Upload,
  Refresh,
  Connection,
  Document,
  Edit,
  View,
} from "@element-plus/icons-vue";
import {
  clone,
  api,
  errorText,
  query,
  download,
  listOf,
  allAssets,
} from "../lib/api";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
import ErrorNotice from "../components/ErrorNotice.vue";
import RecordDetail from "../components/RecordDetail.vue";
const s = useSession(),
  rows = ref<any[]>([]),
  total = ref(0),
  loading = ref(false),
  error = ref(""),
  selected = ref<any[]>([]),
  filters = reactive({
    search: "",
    type: "",
    admission: "",
    page: 1,
    pageSize: 20,
  });
const types: Record<string, string> = {
  SERVER: "服务器",
  CLIENT: "客户端",
  NETWORK: "网络设备",
  SOFTWARE: "软件资产",
};
const admissions: Record<string, string> = {
  DRAFT: "草稿",
  CANDIDATE: "待入库",
  ADMITTED: "已入库",
  INACTIVE: "已停用",
};
const categories: Record<string, string[]> = {
  SERVER: ["通用服务器", "计算服务器", "存储服务器", "虚拟化宿主机"],
  CLIENT: ["研发终端", "办公终端", "测试终端", "专用终端"],
  NETWORK: ["交换机", "路由器", "防火墙", "负载均衡"],
  SOFTWARE: ["操作系统", "数据库", "中间件", "应用软件", "工具软件"],
};
const technical: Record<string, [string, string][]> = {
  SERVER: [
    ["vendor", "厂商"],
    ["model", "型号"],
    ["serial", "序列号"],
    ["cpu", "CPU型号／核数"],
    ["architecture", "架构"],
    ["memory", "内存容量"],
    ["disk", "磁盘容量"],
    ["os", "操作系统"],
    ["managementAddr", "带外管理地址"],
  ],
  CLIENT: [
    ["vendor", "厂商"],
    ["model", "型号"],
    ["serial", "序列号"],
    ["cpu", "CPU型号／核数"],
    ["memory", "内存容量"],
    ["disk", "磁盘容量"],
    ["os", "操作系统"],
    ["agent", "Agent版本"],
  ],
  NETWORK: [
    ["vendor", "厂商"],
    ["model", "型号"],
    ["serial", "序列号"],
    ["mac", "MAC地址"],
    ["firmware", "固件版本"],
    ["ports", "端口说明"],
    ["managementAddr", "管理IP"],
  ],
  SOFTWARE: [
    ["vendor", "软件厂商"],
    ["softwareVersion", "软件版本"],
    ["licenseType", "授权类型"],
    ["licenseId", "许可证编号"],
    ["licenseExpiry", "许可证到期日"],
    ["deployment", "部署位置"],
  ],
};
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const r = await api(
      "/assets" + query({ projectId: s.projectId, ...filters }),
    );
    rows.value = listOf(r);
    total.value = r.total ?? rows.value.length;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
function search() {
  filters.page = 1;
  load();
}
const editing = ref(false),
  saving = ref(false),
  formError = ref(""),
  model = ref<any>({}),
  detail = ref<any>(null),
  detailOpen = ref(false),
  versions = ref<any[]>([]),
  detailTab = ref("basic");
function edit(row?: any) {
  model.value = clone(
    row || {
      projectId: s.projectId,
      name: "",
      type: "SERVER",
      category: "",
      mode: "REAL",
      networkScope: "demo-lan",
      ip: "",
      dept: "",
      owner: "",
      environment: "开发",
      technical: {},
      operation: { collection: "人工维护" },
      lifecycle: {},
    },
  );
  model.value.technical ||= {};
  model.value.operation ||= {};
  model.value.lifecycle ||= {};
  formError.value = "";
  editing.value = true;
}
async function save() {
  if (!model.value.name.trim()) {
    formError.value = "请填写资产名称";
    return;
  }
  if (
    model.value.ip &&
    !/^([\da-fA-F:.]+|[\w-]+(?:\.[\w-]+)*)$/.test(model.value.ip)
  ) {
    formError.value = "请填写有效管理地址";
    return;
  }
  saving.value = true;
  try {
    const body = {
      ...model.value,
      projectId: s.projectId,
      manualFields: [
        "name",
        "type",
        "category",
        "dept",
        "owner",
        "purpose",
        "environment",
        "location",
        "ip",
        ...Object.keys(model.value.technical).map((k) => "technical." + k),
        ...Object.keys(model.value.operation || {}).map(
          (k) => "operation." + k,
        ),
        ...Object.keys(model.value.lifecycle || {}).map(
          (k) => "lifecycle." + k,
        ),
      ],
    };
    await api("/assets" + (body.id ? `/${body.id}` : ""), {
      method: body.id ? "PUT" : "POST",
      json: body,
    });
    editing.value = false;
    ElMessage.success("资产已保存");
    load();
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    saving.value = false;
  }
}
async function show(row: any) {
  detailOpen.value = true;
  detail.value = row;
  versions.value = [];
  detailTab.value = "basic";
  await loadRelations(row.id);
  try {
    detail.value = await api(
      `/assets/${row.id}` + query({ projectId: s.projectId }),
    );
    versions.value = listOf(
      await api(
        `/assets/${row.id}/versions` + query({ projectId: s.projectId }),
      ),
    );
  } catch (e) {
    error.value = errorText(e);
  }
}
async function action(row: any, name: string) {
  try {
    await ElMessageBox.confirm(
      name === "admit"
        ? `确认将“${row.name}”入库？`
        : `确认停用“${row.name}”？`,
      "资产状态变更",
      { type: "warning" },
    );
    await api(`/assets/${row.id}/${name}`, {
      method: "POST",
      json: { projectId: s.projectId, version: row.version },
    });
    ElMessage.success("状态已更新");
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = errorText(e);
  }
}
async function batchAdmit() {
  let failure = "";
  for (const row of selected.value.filter((r) => r.admission !== "ADMITTED")) {
    try {
      await api(`/assets/${row.id}/admit`, {
        method: "POST",
        json: { projectId: s.projectId, version: row.version },
      });
    } catch (e) {
      failure = errorText(e);
      break;
    }
  }
  await load();
  if (failure) error.value = failure;
}
const scanOpen = ref(false),
  scan = reactive({
    mode: "REAL",
    range: "",
    protocol: "SNMP + ICMP",
    credentialRef: "",
    timeoutSeconds: 10,
  }),
  scanBusy = ref(false),
  scanResult = ref<any>(null);
async function discover() {
  scanBusy.value = true;
  formError.value = "";
  try {
    scanResult.value = await api("/assets/discover", {
      method: "POST",
      json: { projectId: s.projectId, ...scan },
    });
    load();
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    scanBusy.value = false;
  }
}
const importOpen = ref(false),
  importFile = ref<File>(),
  preview = ref<any>(null),
  previewKind = ref<"import" | "sync">("import"),
  previewBusy = ref(false),
  syncMode = ref("REAL");
function openImport() {
  previewKind.value = "import";
  preview.value = null;
  formError.value = "";
  importOpen.value = true;
}
function openSync() {
  previewKind.value = "sync";
  preview.value = null;
  formError.value = "";
  importOpen.value = true;
}
async function createPreview() {
  previewBusy.value = true;
  formError.value = "";
  try {
    if (previewKind.value === "import") {
      if (!importFile.value) throw Error("请先选择CSV、JSON或XLSX文件");
      const data = new FormData();
      data.set("projectId", s.projectId);
      data.set("file", importFile.value);
      preview.value = await api("/assets/import/preview", {
        method: "POST",
        body: data,
      });
    } else
      preview.value = await api("/assets/sync/preview", {
        method: "POST",
        json: {
          projectId: s.projectId,
          mode: syncMode.value,
          connectorId: syncMode.value === "SIMULATED" ? "fixture" : undefined,
          assetIds: selected.value.map((r) => r.id),
        },
      });
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    previewBusy.value = false;
  }
}
async function confirmPreview() {
  previewBusy.value = true;
  try {
    const r = await api(`/assets/${previewKind.value}/confirm`, {
      method: "POST",
      json: { projectId: s.projectId, previewId: preview.value.previewId },
    });
    ElMessage.success(
      `已应用 ${r.applied ?? r.items?.length ?? 0} 项，拒绝 ${r.rejected ?? 0} 项`,
    );
    importOpen.value = false;
    load();
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    previewBusy.value = false;
  }
}
async function exportAssets(format = "xlsx") {
  try {
    const blob = await api<Blob>(
      "/assets/export" + query({ projectId: s.projectId, ...filters, format }),
    );
    download(blob, `资产清单.${format}`);
  } catch (e) {
    error.value = errorText(e);
  }
}
const monitoring = ref(false),
  monitorResult = ref<any>(null);
async function monitor(row: any) {
  monitoring.value = true;
  try {
    monitorResult.value = await api(
      `/assets/${row.id}/monitor` + query({ projectId: s.projectId }),
    );
    detail.value = monitorResult.value;
    detailOpen.value = true;
    load();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    monitoring.value = false;
  }
}
const countText = computed(() =>
  selected.value.length
    ? `已选择 ${selected.value.length} 项`
    : "资产分类、配置与归属统一维护",
);
const relationshipRows = ref<any[]>([]),
  relatedAssets = ref<any[]>([]),
  relationError = ref(""),
  relationBusy = ref(false),
  relation = reactive({ targetId: "", type: "USES" });
const relationTypes: Record<string, string> = {
  MEMBER_OF: "成员属于",
  HOSTED_ON: "运行于",
  DEPLOYED_TO: "部署到",
  USES: "使用",
  DEPENDS_ON: "依赖",
};
async function loadRelations(id: string) {
  relationError.value = "";
  try {
    const [records, assets] = await Promise.all([
      api("/relationships" + query({ projectId: s.projectId })),
      allAssets(s.projectId),
    ]);
    relationshipRows.value = listOf(records).filter(
      (r) => r.sourceId === id || r.targetId === id,
    );
    relatedAssets.value = assets;
  } catch (e) {
    relationError.value = errorText(e);
  }
}
async function createRelation() {
  if (!relation.targetId) {
    relationError.value = "请选择关联资产";
    return;
  }
  relationBusy.value = true;
  try {
    await api("/relationships", {
      method: "POST",
      json: {
        projectId: s.projectId,
        sourceId: detail.value.id,
        targetId: relation.targetId,
        type: relation.type,
        mode: detail.value.mode,
      },
    });
    relation.targetId = "";
    await loadRelations(detail.value.id);
    ElMessage.success("资产关系已保存");
  } catch (e) {
    relationError.value = errorText(e);
  } finally {
    relationBusy.value = false;
  }
}
async function deleteRelation(row: any) {
  try {
    await ElMessageBox.confirm(
      "确认删除该关系？资产本身不会删除。",
      "删除资产关系",
    );
    await api(
      `/relationships/${row.id}` +
        query({ projectId: s.projectId, version: row.version }),
      { method: "DELETE" },
    );
    await loadRelations(detail.value.id);
  } catch (e) {
    if (e !== "cancel" && e !== "close") relationError.value = errorText(e);
  }
}
onMounted(load);
</script>
<template>
  <section class="page-header">
    <div>
      <p class="eyebrow">ASSET INVENTORY</p>
      <h1>资产管理</h1>
      <p>发现、采集、入库与日常维护</p>
    </div>
    <div class="page-meta">
      <span class="count-number">{{ total }}</span> 项资产
    </div>
  </section>
  <ErrorNotice :message="error" @retry="load" />
  <section class="panel">
    <div class="filterbar">
      <el-input
        v-model="filters.search"
        placeholder="搜索名称、编号或管理地址"
        :prefix-icon="Search"
        clearable
        @clear="search"
        @keyup.enter="search"
        class="search-input"
      /><el-select
        v-model="filters.type"
        placeholder="全部类型"
        clearable
        @change="search"
        ><el-option
          v-for="(label, value) in types"
          :key="value"
          :label="label"
          :value="value" /></el-select
      ><el-select
        v-model="filters.admission"
        placeholder="全部入库状态"
        clearable
        @change="search"
        ><el-option
          v-for="(label, value) in admissions"
          :key="value"
          :label="label"
          :value="value" /></el-select
      ><el-button @click="search" :icon="Search">查询</el-button
      ><span class="spacer"></span
      ><el-dropdown @command="exportAssets"
        ><el-button :icon="Download">导出清单</el-button
        ><template #dropdown
          ><el-dropdown-menu
            ><el-dropdown-item command="xlsx">Excel 工作簿</el-dropdown-item
            ><el-dropdown-item command="csv">CSV 清单</el-dropdown-item
            ><el-dropdown-item command="json"
              >JSON 数据</el-dropdown-item
            ></el-dropdown-menu
          ></template
        ></el-dropdown
      ><el-button :icon="Refresh" @click="load" aria-label="刷新资产" />
    </div>
    <div class="actionbar">
      <el-button
        type="primary"
        :icon="Connection"
        @click="
          scanOpen = true;
          scanResult = null;
          formError = '';
        "
        >网络扫描</el-button
      ><el-button type="primary" :icon="Plus" @click="edit()"
        >手动新增</el-button
      ><el-button type="primary" :icon="Upload" @click="openImport"
        >Excel 导入</el-button
      ><el-button
        type="primary"
        @click="
          selected.length
            ? batchAdmit()
            : ElMessage.info('请先勾选需要入库的资产')
        "
        >资产入库</el-button
      ><el-button type="primary" :icon="Refresh" @click="openSync"
        >同步资产</el-button
      ><span class="spacer"></span><small>{{ countText }}</small>
    </div>
    <el-table
      :data="rows"
      v-loading="loading"
      @selection-change="selected = $event"
      row-key="id"
      stripe
      ><el-table-column type="selection" width="38" /><el-table-column
        label="资产名称 / 编号"
        min-width="190"
        ><template #default="{ row }"
          ><button class="text-link" @click="show(row)">{{ row.name }}</button
          ><small class="subline mono">{{ row.id }}</small></template
        ></el-table-column
      ><el-table-column label="类型" min-width="90"
        ><template #default="{ row }"
          >{{ types[row.type] || row.type
          }}<small class="subline">{{
            row.category || "未分类"
          }}</small></template
        ></el-table-column
      ><el-table-column
        prop="ip"
        label="管理地址"
        min-width="125"
        show-overflow-tooltip /><el-table-column
        prop="source"
        label="来源"
        width="100" /><el-table-column
        prop="dept"
        label="所属部门"
        width="105" /><el-table-column label="入库状态" width="95"
        ><template #default="{ row }"
          ><StatusTag
            :value="
              admissions[row.admission] || row.admission
            " /></template></el-table-column
      ><el-table-column label="资源状态" width="95"
        ><template #default="{ row }"
          ><StatusTag :value="row.resourceStatus" /></template></el-table-column
      ><el-table-column label="执行模式" width="105"
        ><template #default="{ row }"
          ><StatusTag :value="row.mode" /></template></el-table-column
      ><el-table-column label="操作" fixed="right" width="198"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="edit(row)">维护</el-button
          ><el-button
            link
            type="primary"
            @click="monitor(row)"
            :loading="monitoring"
            >监控</el-button
          ><el-button
            v-if="row.admission !== 'ADMITTED'"
            link
            type="primary"
            @click="action(row, 'admit')"
            >入库</el-button
          ><el-button
            v-else
            link
            type="danger"
            @click="action(row, 'deactivate')"
            >停用</el-button
          ></template
        ></el-table-column
      ><template #empty
        ><el-empty
          description="暂无符合条件的资产，可新增或扫描发现"
          :image-size="74" /></template
    ></el-table>
    <div class="pagination">
      <span>共 {{ total }} 条资产</span
      ><el-pagination
        v-model:current-page="filters.page"
        v-model:page-size="filters.pageSize"
        :page-sizes="[10, 20, 30, 50]"
        :total="total"
        layout="sizes, prev, pager, next"
        @change="load"
      />
    </div>
  </section>
  <el-drawer
    v-model="editing"
    :title="model.id ? '维护资产' : '手动新增资产'"
    size="760px"
    :close-on-click-modal="!saving"
    ><el-alert
      v-if="model.mode === 'SIMULATED'"
      type="warning"
      title="模拟资产：仅用于验证流程，不代表真实设备或采集结果。"
      :closable="false"
    /><el-form label-position="top" class="form-grid" @submit.prevent="save"
      ><h3 class="section-heading">基本与归属</h3>
      <el-form-item label="资产名称 *"
        ><el-input v-model="model.name" maxlength="160" /></el-form-item
      ><el-form-item label="资产类型 *"
        ><el-select v-model="model.type" :disabled="!!model.id"
          ><el-option
            v-for="(label, value) in types"
            :key="value"
            :label="label"
            :value="value" /></el-select></el-form-item
      ><el-form-item label="二级分类"
        ><el-select
          v-model="model.category"
          filterable
          allow-create
          default-first-option
          ><el-option
            v-for="c in categories[model.type]"
            :key="c"
            :value="c" /></el-select></el-form-item
      ><el-form-item label="所属部门"
        ><el-input v-model="model.dept" /></el-form-item
      ><el-form-item label="负责人"
        ><el-input v-model="model.owner" /></el-form-item
      ><el-form-item label="网络范围"
        ><el-input
          v-model="model.networkScope"
          placeholder="demo-lan" /></el-form-item
      ><el-form-item label="管理地址 / IP"
        ><el-input v-model="model.ip" /></el-form-item
      ><el-form-item label="环境"
        ><el-select v-model="model.environment"
          ><el-option
            v-for="t in ['生产', '测试', '开发', '办公', '其他']"
            :key="t"
            :value="t" /></el-select></el-form-item
      ><el-form-item label="物理 / 部署位置"
        ><el-input v-model="model.location" /></el-form-item
      ><el-form-item label="用途"
        ><el-input v-model="model.purpose" /></el-form-item
      ><el-form-item label="执行模式 *"
        ><el-select v-model="model.mode" :disabled="!!model.id"
          ><el-option label="真实资源 REAL" value="REAL" /><el-option
            label="模拟资源 SIMULATED"
            value="SIMULATED" /></el-select></el-form-item
      ><el-form-item label="连接器编号"
        ><el-input
          v-model="model.connectorId"
          placeholder="真实资源须配置受控连接器" /></el-form-item
      ><el-form-item label="外部资源编号"
        ><el-input v-model="model.externalId"
      /></el-form-item>
      <h3 class="section-heading">{{ types[model.type] }}技术信息</h3>
      <el-form-item
        v-for="[key, label] in technical[model.type]"
        :key="key"
        :label="label"
        ><el-input v-model="model.technical[key]"
      /></el-form-item>
      <h3 class="section-heading">运维信息</h3>
      <el-form-item label="运维负责人"
        ><el-input v-model="model.operation.owner" /></el-form-item
      ><el-form-item label="维护状态"
        ><el-select v-model="model.operation.status"
          ><el-option
            v-for="x in ['正常维护', '维护中', '停用', '待维护']"
            :key="x"
            :value="x" /></el-select></el-form-item
      ><el-form-item label="采集方式"
        ><el-select v-model="model.operation.collection"
          ><el-option
            v-for="x in ['SNMP', 'ICMP', 'SNMP + ICMP', '人工维护']"
            :key="x"
            :value="x" /></el-select></el-form-item
      ><el-form-item label="凭据引用"
        ><el-input
          v-model="model.operation.credentialRef"
          placeholder="只填写平台引用，不填写明文密钥" /></el-form-item
      ><el-form-item label="监控策略"
        ><el-input v-model="model.operation.monitorPolicy" /></el-form-item
      ><el-form-item label="维护窗口"
        ><el-input v-model="model.operation.window" /></el-form-item
      ><el-form-item label="备注" class="full"
        ><el-input type="textarea" v-model="model.operation.notes"
      /></el-form-item>
      <h3 class="section-heading">生命周期</h3>
      <el-form-item
        v-for="[key, label] in [
          ['purchaseDate', '采购日期'],
          ['enabledDate', '启用日期'],
          ['warrantyUntil', '保修期限'],
        ]"
        :key="key"
        :label="label"
        ><el-date-picker
          v-model="model.lifecycle[key]"
          type="date"
          value-format="YYYY-MM-DD" /></el-form-item
      ><el-form-item label="供应商"
        ><el-input v-model="model.lifecycle.supplier" /></el-form-item></el-form
    ><ErrorNotice :message="formError" @retry="save" /><template #footer
      ><el-button @click="editing = false" :disabled="saving">取消</el-button
      ><el-button type="primary" @click="save" :loading="saving"
        >保存资产</el-button
      ></template
    ></el-drawer
  >
  <el-drawer
    v-model="detailOpen"
    :title="detail?.name || '资产详情'"
    size="760px"
    ><el-tabs v-model="detailTab"
      ><el-tab-pane label="基本信息与配置" name="basic"
        ><RecordDetail :record="detail" /></el-tab-pane
      ><el-tab-pane label="版本记录" name="versions"
        ><el-empty
          v-if="!versions.length"
          description="暂无版本记录" /><RecordDetail
          v-for="v in versions"
          :key="v.id || v.version"
          :record="v" /></el-tab-pane
      ><el-tab-pane v-if="detail?.id" label="资产关系" name="relationships"
        ><el-table :data="relationshipRows"
          ><el-table-column label="来源" min-width="155"
            ><template #default="{ row }">{{
              relatedAssets.find((a) => a.id === row.sourceId)?.name ||
              row.sourceId
            }}</template></el-table-column
          ><el-table-column label="关系" width="100"
            ><template #default="{ row }">{{
              relationTypes[row.type] || row.type
            }}</template></el-table-column
          ><el-table-column label="目标" min-width="155"
            ><template #default="{ row }">{{
              relatedAssets.find((a) => a.id === row.targetId)?.name ||
              row.targetId
            }}</template></el-table-column
          ><el-table-column label="操作" width="70"
            ><template #default="{ row }"
              ><el-button
                v-if="s.can('asset.write')"
                link
                type="danger"
                @click="deleteRelation(row)"
                >删除</el-button
              ></template
            ></el-table-column
          ></el-table
        ><el-form v-if="s.can('asset.write')" label-position="top" class="mt16"
          ><el-form-item label="关系类型"
            ><el-select v-model="relation.type"
              ><el-option
                v-for="(label, value) in relationTypes"
                :key="value"
                :value="value"
                :label="label" /></el-select></el-form-item
          ><el-form-item label="关联目标"
            ><el-select v-model="relation.targetId" filterable
              ><el-option
                v-for="a in relatedAssets.filter(
                  (a) => a.id !== detail.id && a.mode === detail.mode,
                )"
                :key="a.id"
                :value="a.id"
                :label="a.name" /></el-select></el-form-item
          ><el-button
            type="primary"
            :loading="relationBusy"
            @click="createRelation"
            >创建关系</el-button
          ></el-form
        ><ErrorNotice
          :message="relationError"
          @retry="loadRelations(detail.id)" /></el-tab-pane></el-tabs
  ></el-drawer>
  <el-dialog v-model="scanOpen" title="网络扫描" width="760px"
    ><p class="dialog-description">
      核对发现结果后补全信息，待入库记录经确认后进入资产台账。
    </p>
    <el-form label-position="top" class="form-grid"
      ><el-form-item label="执行模式"
        ><el-select v-model="scan.mode"
          ><el-option value="REAL" label="真实扫描 REAL" /><el-option
            value="SIMULATED"
            label="模拟发现 SIMULATED" /></el-select></el-form-item
      ><el-form-item label="扫描网段 / 地址"
        ><el-input
          v-model="scan.range"
          placeholder="192.168.10.0/24" /></el-form-item
      ><el-form-item label="发现协议"
        ><el-select v-model="scan.protocol"
          ><el-option
            v-for="x in ['SNMP + ICMP', 'SNMP', 'ICMP']"
            :key="x"
            :value="x" /></el-select></el-form-item
      ><el-form-item label="采集凭据引用"
        ><el-input v-model="scan.credentialRef" /></el-form-item></el-form
    ><el-alert
      v-if="scan.mode === 'SIMULATED'"
      type="warning"
      :closable="false"
      title="模拟发现仅创建演示候选记录，不访问真实网络。"
    /><ErrorNotice :message="formError" @retry="discover" /><template
      v-if="scanResult"
      ><el-alert
        :title="scanResult.message || '已获取发现结果'"
        type="info"
        :closable="false"
        class="mt16" /><el-table :data="scanResult.items || []"
        ><el-table-column
          prop="name"
          label="设备名称"
          min-width="170" /><el-table-column label="类型" width="90"
          ><template #default="{ row }">{{
            types[row.type] || row.type
          }}</template></el-table-column
        ><el-table-column
          prop="ip"
          label="管理地址"
          width="125" /><el-table-column label="入库状态" width="105"
          ><template #default="{ row }"
            ><StatusTag
              :value="
                admissions[row.admission] || row.admission
              " /></template></el-table-column
        ><el-table-column label="模式" width="105"
          ><template #default="{ row }"
            ><StatusTag
              :value="
                row.mode
              " /></template></el-table-column></el-table></template
    ><template #footer
      ><el-button @click="scanOpen = false">关闭</el-button
      ><el-button type="primary" :loading="scanBusy" @click="discover"
        >开始扫描</el-button
      ></template
    ></el-dialog
  >
  <el-dialog
    v-model="importOpen"
    :title="
      previewKind === 'import' ? '导入资产 · 预览与确认' : '同步资产 · 差异确认'
    "
    width="860px"
    :close-on-click-modal="!previewBusy"
    ><template v-if="!preview"
      ><p class="dialog-description">
        {{
          previewKind === "import"
            ? "支持 CSV、JSON、XLSX。先校验并逐行显示结果，确认后仅导入有效记录。"
            : "先采集并核对差异，确认后更新。人工保护字段不会被静默覆盖。"
        }}
      </p>
      <input
        v-if="previewKind === 'import'"
        type="file"
        accept=".csv,.json,.xlsx"
        aria-label="选择资产导入文件"
        @change="
          importFile = ($event.target as HTMLInputElement).files?.[0]
        " /><el-select v-else v-model="syncMode"
        ><el-option value="REAL" label="真实采集 REAL" /><el-option
          value="SIMULATED"
          label="模拟采集 SIMULATED" /></el-select></template
    ><template v-else
      ><el-alert
        :closable="false"
        type="info"
        :title="`可应用 ${preview.validCount} 行，异常 ${preview.errorCount} 行；预览于 ${preview.expiresAt} 过期`"
      /><el-table :data="preview.rows" max-height="420"
        ><el-table-column
          prop="rowNumber"
          label="行"
          width="55"
        /><el-table-column
          prop="data.name"
          label="资产"
          min-width="130"
        /><el-table-column
          prop="status"
          label="校验"
          width="100"
        /><el-table-column label="变化 / 原因" min-width="250"
          ><template #default="{ row }">
            <pre class="json-block">{{
              JSON.stringify(row.changes || row.errors || [], null, 2)
            }}</pre>
          </template></el-table-column
        ></el-table
      ></template
    ><ErrorNotice
      :message="formError"
      @retry="preview ? confirmPreview() : createPreview()"
    /><template #footer
      ><el-button @click="importOpen = false">取消</el-button
      ><el-button
        v-if="!preview"
        type="primary"
        :loading="previewBusy"
        @click="createPreview"
        >开始校验预览</el-button
      ><el-button
        v-else
        type="primary"
        :loading="previewBusy"
        :disabled="!preview.validCount"
        @click="confirmPreview"
        >确认应用 {{ preview.validCount }} 行</el-button
      ></template
    ></el-dialog
  >
</template>
