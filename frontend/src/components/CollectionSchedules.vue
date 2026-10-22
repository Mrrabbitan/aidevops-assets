<script setup lang="ts">
import { ref, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Plus, Refresh } from "@element-plus/icons-vue";
import { api, errorText, query, listOf, allAssets, clone } from "../lib/api";
import { useSession } from "../stores/session";
import ErrorNotice from "./ErrorNotice.vue";
import StatusTag from "./StatusTag.vue";
import RecordDetail from "./RecordDetail.vue";
const s = useSession(),
  schedules = ref<any[]>([]),
  runs = ref<any[]>([]),
  assets = ref<any[]>([]),
  error = ref(""),
  loading = ref(false),
  editing = ref(false),
  saving = ref(false),
  model = ref<any>({}),
  detailOpen = ref(false),
  detail = ref<any>(null),
  confirming = ref(false);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [plans, history, targets] = await Promise.all([
      api("/collection-schedules" + query({ projectId: s.projectId })),
      api("/collection-runs" + query({ projectId: s.projectId })),
      allAssets(s.projectId, { admission: "ADMITTED" }),
    ]);
    schedules.value = listOf(plans);
    runs.value = listOf(history);
    assets.value = targets;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
function edit(row?: any) {
  model.value = clone(
    row || {
      projectId: s.projectId,
      name: "",
      kind: "DISCOVERY",
      mode: "REAL",
      enabled: false,
      intervalMinutes: 60,
      timezone: "Asia/Shanghai",
      serviceSubject: "asset-scheduler-demo",
      config: {
        connectorId: "fixture",
        networkScope: "demo-lan",
        assetIds: [],
      },
    },
  );
  error.value = "";
  editing.value = true;
}
async function save() {
  if (!model.value.name.trim() || !model.value.serviceSubject.trim()) {
    error.value = "请填写计划名称和服务主体";
    return;
  }
  if (
    model.value.kind === "DISCOVERY" &&
    !model.value.config.networkScope.trim()
  ) {
    error.value = "请填写已审批的网络范围";
    return;
  }
  if (model.value.kind === "SYNC" && !model.value.config.assetIds?.length) {
    error.value = "请明确选择需要同步的资产范围";
    return;
  }
  saving.value = true;
  try {
    await api(
      "/collection-schedules" + (model.value.id ? `/${model.value.id}` : ""),
      {
        method: model.value.id ? "PUT" : "POST",
        json: { ...model.value, projectId: s.projectId },
      },
    );
    editing.value = false;
    ElMessage.success("采集计划已保存");
    load();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    saving.value = false;
  }
}
async function execute(row: any) {
  try {
    await ElMessageBox.confirm(
      `确认手动执行“${row.name}”？同步任务只生成预览，确认前不覆盖台账。`,
      "执行采集计划",
    );
    detail.value = await api(`/collection-schedules/${row.id}/execute`, {
      method: "POST",
      json: { version: row.version },
    });
    detailOpen.value = true;
    load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = errorText(e);
  }
}
function show(run: any) {
  detail.value = run;
  detailOpen.value = true;
}
async function confirmPreview() {
  try {
    await ElMessageBox.confirm(
      "确认应用本次同步预览？后台将重新核对资产版本与人工保护字段。",
      "确认采集变化",
    );
    confirming.value = true;
    const result = await api("/assets/sync/confirm", {
      method: "POST",
      json: { projectId: s.projectId, previewId: detail.value.previewId },
    });
    ElMessage.success(`已应用 ${result.applied} 项变化`);
    detail.value = { ...detail.value, appliedResult: result };
    load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = errorText(e);
  } finally {
    confirming.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section class="panel" v-loading="loading">
    <div class="panel-title">
      <h2>采集计划</h2>
      <div>
        <el-button :icon="Refresh" @click="load">刷新</el-button
        ><el-button
          type="primary"
          :icon="Plus"
          @click="edit()"
          :disabled="!s.can('asset.execute')"
          >新增计划</el-button
        >
      </div>
    </div>
    <ErrorNotice :message="error" @retry="load" /><el-table :data="schedules"
      ><el-table-column
        prop="name"
        label="计划名称"
        min-width="180" /><el-table-column label="类型" width="110"
        ><template #default="{ row }">{{
          row.kind === "DISCOVERY" ? "资源发现" : "资产同步"
        }}</template></el-table-column
      ><el-table-column label="模式" width="110"
        ><template #default="{ row }"
          ><StatusTag :value="row.mode" /></template></el-table-column
      ><el-table-column label="周期" width="110"
        ><template #default="{ row }"
          >每 {{ row.intervalMinutes }} 分钟</template
        ></el-table-column
      ><el-table-column label="状态" width="90"
        ><template #default="{ row }">{{
          row.enabled ? "已启用" : "已停用"
        }}</template></el-table-column
      ><el-table-column
        prop="nextRunAt"
        label="下次执行"
        min-width="185" /><el-table-column label="操作" width="155"
        ><template #default="{ row }"
          ><el-button
            link
            type="primary"
            @click="edit(row)"
            :disabled="!s.can('asset.execute')"
            >配置</el-button
          ><el-button
            link
            type="primary"
            @click="execute(row)"
            :disabled="!s.can('asset.execute')"
            >执行一次</el-button
          ></template
        ></el-table-column
      ><template #empty
        ><el-empty description="暂无采集计划" :image-size="60" /></template
    ></el-table>
  </section>
  <section class="panel">
    <div class="panel-title">
      <h2>采集执行记录</h2>
      <small class="muted">同步结果待人工确认后应用</small>
    </div>
    <el-table :data="runs"
      ><el-table-column label="运行编号" min-width="210"
        ><template #default="{ row }"
          ><button class="text-link" @click="show(row)">
            {{ row.id }}
          </button></template
        ></el-table-column
      ><el-table-column label="类型" width="110"
        ><template #default="{ row }">{{
          row.kind === "DISCOVERY" ? "资源发现" : "资产同步"
        }}</template></el-table-column
      ><el-table-column label="模式" width="110"
        ><template #default="{ row }"
          ><StatusTag :value="row.mode" /></template></el-table-column
      ><el-table-column label="状态" width="150"
        ><template #default="{ row }"
          ><StatusTag :value="row.status" /></template></el-table-column
      ><el-table-column
        prop="errorMessage"
        label="结果 / 原因"
        min-width="180" /><el-table-column
        prop="startedAt"
        label="开始时间"
        min-width="185" /><template #empty
        ><el-empty description="暂无采集执行记录" :image-size="60" /></template
    ></el-table>
  </section>
  <el-drawer
    v-model="editing"
    :title="model.id ? '配置采集计划' : '新增采集计划'"
    size="700px"
    ><el-alert
      v-if="model.mode === 'SIMULATED'"
      type="warning"
      title="SIMULATED · 仅使用模拟来源，不访问真实设备。"
      :closable="false"
    /><el-form label-position="top" class="form-grid mt16"
      ><el-form-item label="计划名称 *"
        ><el-input v-model="model.name" /></el-form-item
      ><el-form-item label="类型"
        ><el-select v-model="model.kind"
          ><el-option value="DISCOVERY" label="资源发现" /><el-option
            value="SYNC"
            label="资产同步" /></el-select></el-form-item
      ><el-form-item label="执行模式"
        ><el-select v-model="model.mode"
          ><el-option value="REAL" label="真实采集 REAL" /><el-option
            value="SIMULATED"
            label="模拟采集 SIMULATED" /></el-select></el-form-item
      ><el-form-item label="连接器引用"
        ><el-input v-model="model.config.connectorId" /></el-form-item
      ><el-form-item
        v-if="model.kind === 'DISCOVERY'"
        label="已审批网络范围 *"
        class="full"
        ><el-input
          v-model="model.config.networkScope"
          placeholder="明确范围，不允许默认全网" /></el-form-item
      ><el-form-item v-else label="明确同步对象 *" class="full"
        ><el-select v-model="model.config.assetIds" multiple filterable
          ><el-option
            v-for="a in assets.filter(
              (a) =>
                a.mode === model.mode &&
                a.connectorId === model.config.connectorId,
            )"
            :key="a.id"
            :value="a.id"
            :label="a.name" /></el-select
        ><small>最多100项，后台重新核对服务主体授权范围。</small></el-form-item
      ><el-form-item label="执行间隔（分钟）"
        ><el-input-number
          v-model="model.intervalMinutes"
          :min="1"
          :max="525600"
          :controls="false" /></el-form-item
      ><el-form-item label="时区"
        ><el-input v-model="model.timezone" /></el-form-item
      ><el-form-item label="授权服务主体 *" class="full"
        ><el-input v-model="model.serviceSubject" /></el-form-item
      ><el-form-item label="启用计划"
        ><el-switch v-model="model.enabled" /><small
          >真实适配未接入时可保存停用计划，启用或执行会返回未配置错误。</small
        ></el-form-item
      ></el-form
    ><ErrorNotice :message="error" @retry="save" /><template #footer
      ><el-button @click="editing = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="save"
        >保存计划</el-button
      ></template
    ></el-drawer
  ><el-drawer v-model="detailOpen" title="采集结果" size="840px"
    ><el-alert
      v-if="detail?.mode === 'SIMULATED'"
      title="SIMULATED · 模拟采集记录"
      type="warning"
      :closable="false"
    /><RecordDetail :record="detail" /><ErrorNotice
      :message="error"
      @retry="load"
    /><template #footer
      ><el-button @click="detailOpen = false">关闭</el-button
      ><el-button
        v-if="
          detail?.status === 'PREVIEW_READY' &&
          detail.previewId &&
          !detail.appliedResult
        "
        type="primary"
        :loading="confirming"
        @click="confirmPreview"
        :disabled="!s.can('asset.write')"
        >确认应用同步预览</el-button
      ></template
    ></el-drawer
  >
</template>
