<script setup lang="ts">
import { ref, reactive, computed, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Search, Plus, Refresh, Setting, Bell } from "@element-plus/icons-vue";
import { clone, api, errorText, query, listOf, allAssets } from "../lib/api";
import { useSession } from "../stores/session";
import ErrorNotice from "../components/ErrorNotice.vue";
import StatusTag from "../components/StatusTag.vue";
import RecordDetail from "../components/RecordDetail.vue";
const s = useSession(),
  rows = ref<any[]>([]),
  rules = ref<any[]>([]),
  targets = ref<any[]>([]),
  loading = ref(false),
  error = ref(""),
  formError = ref(""),
  tab = ref("alarms"),
  filters = reactive({ search: "", severity: "", status: "" });
const filtered = computed(() =>
  rows.value.filter(
    (r) =>
      (!filters.search ||
        JSON.stringify(r)
          .toLowerCase()
          .includes(filters.search.toLowerCase())) &&
      (!filters.severity || r.severity === filters.severity) &&
      (!filters.status || r.status === filters.status),
  ),
);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [a, r, t] = await Promise.all([
      api("/alarms" + query({ projectId: s.projectId })),
      api("/alarm-rules" + query({ projectId: s.projectId })),
      allAssets(s.projectId),
    ]);
    rows.value = listOf(a);
    rules.value = listOf(r);
    targets.value = listOf(t);
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
const handleOpen = ref(false),
  current = ref<any>({}),
  handling = ref(false),
  handle = reactive({ status: "ACKNOWLEDGED", note: "" });
function openHandle(row: any) {
  current.value = row;
  handle.status = row.status === "OPEN" ? "ACKNOWLEDGED" : "RESOLVED";
  handle.note = "";
  formError.value = "";
  handleOpen.value = true;
}
async function saveHandle() {
  if (!handle.note.trim()) {
    formError.value = "请填写处理说明";
    return;
  }
  handling.value = true;
  try {
    await api(`/alarms/${current.value.id}/handle`, {
      method: "POST",
      json: {
        projectId: s.projectId,
        version: current.value.version,
        ...handle,
      },
    });
    handleOpen.value = false;
    ElMessage.success("处理记录已保存；设备恢复状态仍以观测结果为准");
    load();
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    handling.value = false;
  }
}
const editing = ref(false),
  saving = ref(false),
  model = ref<any>({}),
  testReceipt = ref<any>(null),
  detailOpen = ref(false),
  detail = ref<any>(null);
function edit(row?: any) {
  model.value = clone(
    row || {
      projectId: s.projectId,
      name: "",
      source: "HARDWARE",
      severity: "WARNING",
      metric: "CPU",
      operator: "GT",
      threshold: 80,
      durationSeconds: 300,
      enabled: false,
      targetIds: [],
      notificationChannels: ["IN_APP"],
      description: "",
    },
  );
  formError.value = "";
  testReceipt.value = null;
  editing.value = true;
}
function valid() {
  if (!model.value.name.trim()) {
    formError.value = "请填写规则名称";
    return false;
  }
  if (!model.value.notificationChannels.length) {
    formError.value = "请选择至少一种通知方式";
    return false;
  }
  return true;
}
async function save() {
  if (!valid()) return;
  saving.value = true;
  try {
    await api("/alarm-rules" + (model.value.id ? `/${model.value.id}` : ""), {
      method: model.value.id ? "PUT" : "POST",
      json: { ...model.value, projectId: s.projectId },
    });
    editing.value = false;
    ElMessage.success("规则已保存");
    load();
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    saving.value = false;
  }
}
async function toggle(row: any, value: boolean) {
  try {
    await ElMessageBox.confirm(
      `确认${value ? "启用" : "停用"}规则“${row.name}”？`,
      "规则状态变更",
    );
    await api(`/alarm-rules/${row.id}`, {
      method: "PUT",
      json: { ...row, projectId: s.projectId, enabled: value },
    });
    load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = errorText(e);
  }
}
async function deleteRule(row: any) {
  try {
    await ElMessageBox.confirm(
      `确认删除规则“${row.name}”？已有告警和通知保留。`,
      "删除告警规则",
      { type: "warning" },
    );
    await api(
      `/alarm-rules/${row.id}` +
        query({ projectId: s.projectId, version: row.version }),
      { method: "DELETE" },
    );
    load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = errorText(e);
  }
}
async function test(row: any) {
  if (!row.id) {
    formError.value = "请先保存规则，再执行通知测试";
    return;
  }
  try {
    testReceipt.value = await api(`/alarm-rules/${row.id}/test`, {
      method: "POST",
      json: { projectId: s.projectId, version: row.version },
    });
  } catch (e) {
    formError.value = errorText(e);
  }
}
async function show(row: any) {
  detail.value = row;
  detailOpen.value = true;
  try {
    detail.value = await api(
      `/alarms/${row.id}` + query({ projectId: s.projectId }),
    );
  } catch (e) {
    error.value = errorText(e);
  }
}
const observationOpen = ref(false),
  observation = reactive({ assetId: "", metric: "CPU", value: 95 }),
  observationBusy = ref(false);
async function submitObservation() {
  if (!observation.assetId) {
    formError.value = "请选择模拟资产";
    return;
  }
  observationBusy.value = true;
  try {
    const r = await api("/observations", {
      method: "POST",
      json: {
        projectId: s.projectId,
        assetId: observation.assetId,
        mode: "SIMULATED",
        externalEventId: crypto.randomUUID(),
        observedAt: new Date().toISOString(),
        metrics: { [observation.metric]: observation.value },
      },
    });
    ElMessage.success(
      `模拟观测已接收，本次命中 ${r.evaluatedAlarms ?? 0} 条规则`,
    );
    observationOpen.value = false;
    load();
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    observationBusy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section class="page-header">
    <div>
      <p class="eyebrow">ALERT OPERATIONS</p>
      <h1>告警管理</h1>
      <p>聚合异常，明确责任，跟踪处理与恢复</p>
    </div>
    <div class="header-tools">
      <el-button
        v-if="s.projectId === 'demo' && s.can('observation.write')"
        @click="
          observationOpen = true;
          formError = '';
        "
        >提交模拟观测</el-button
      ><el-button :icon="Refresh" @click="load" :loading="loading"
        >刷新</el-button
      >
    </div>
  </section>
  <ErrorNotice :message="error" @retry="load" /><el-tabs v-model="tab"
    ><el-tab-pane label="告警记录" name="alarms" /><el-tab-pane
      label="告警规则"
      name="rules"
  /></el-tabs>
  <section v-if="tab === 'alarms'" class="panel">
    <div class="filterbar">
      <el-input
        v-model="filters.search"
        :prefix-icon="Search"
        placeholder="搜索告警内容或来源"
        clearable
        class="search-input"
      /><el-select v-model="filters.severity" clearable placeholder="全部级别"
        ><el-option label="严重" value="CRITICAL" /><el-option
          label="重要"
          value="WARNING" /><el-option label="一般" value="INFO" /></el-select
      ><el-select v-model="filters.status" clearable placeholder="全部状态"
        ><el-option label="待处理" value="OPEN" /><el-option
          label="处理中"
          value="ACKNOWLEDGED" /><el-option
          label="已处理"
          value="RESOLVED" /></el-select
      ><span class="spacer"></span
      ><el-button type="primary" :icon="Setting" @click="tab = 'rules'"
        >告警规则配置</el-button
      >
    </div>
    <el-table :data="filtered" v-loading="loading" stripe
      ><el-table-column label="级别" width="90"
        ><template #default="{ row }"
          ><StatusTag :value="row.severity" /></template></el-table-column
      ><el-table-column label="告警内容" min-width="260"
        ><template #default="{ row }"
          ><button class="text-link" @click="show(row)">
            {{ row.message || row.content || row.name }}</button
          ><small class="subline mono">{{ row.id }}</small></template
        ></el-table-column
      ><el-table-column
        prop="source"
        label="来源类型"
        width="120" /><el-table-column
        prop="targetId"
        label="来源对象"
        min-width="150" /><el-table-column label="状态" width="110"
        ><template #default="{ row }"
          ><StatusTag :value="row.status" /></template></el-table-column
      ><el-table-column label="设备恢复" width="100"
        ><template #default="{ row }">{{
          row.observedRecovery ? "已观测恢复" : "未确认"
        }}</template></el-table-column
      ><el-table-column
        prop="createdAt"
        label="发生时间"
        min-width="185" /><el-table-column label="操作" width="80" fixed="right"
        ><template #default="{ row }"
          ><el-button
            link
            type="primary"
            @click="row.status === 'RESOLVED' ? show(row) : openHandle(row)"
            >{{ row.status === "RESOLVED" ? "查看" : "处理" }}</el-button
          ></template
        ></el-table-column
      ><template #empty
        ><el-empty
          description="暂无符合条件的告警"
          :image-size="70" /></template
    ></el-table>
  </section>
  <section v-else class="panel">
    <div class="filterbar">
      <p class="muted">
        {{ rules.length }} 条规则 · 生效条件与通知方式独立配置
      </p>
      <span class="spacer"></span
      ><el-button type="primary" :icon="Plus" @click="edit()"
        >新增规则</el-button
      >
    </div>
    <el-table :data="rules" v-loading="loading" stripe
      ><el-table-column label="规则名称" min-width="220"
        ><template #default="{ row }"
          ><button class="text-link" @click="edit(row)">{{ row.name }}</button
          ><small class="subline mono">{{ row.id }}</small></template
        ></el-table-column
      ><el-table-column label="触发条件" min-width="210"
        ><template #default="{ row }"
          >{{ row.metric }} {{ row.operator }} {{ row.threshold
          }}<small class="subline"
            >持续 {{ row.durationSeconds }} 秒 · {{ row.source }}</small
          ></template
        ></el-table-column
      ><el-table-column label="级别" width="90"
        ><template #default="{ row }"
          ><StatusTag :value="row.severity" /></template></el-table-column
      ><el-table-column label="通知" min-width="150"
        ><template #default="{ row }">{{
          row.notificationChannels?.join(" / ") || "未配置"
        }}</template></el-table-column
      ><el-table-column label="启用" width="85"
        ><template #default="{ row }"
          ><el-switch
            :model-value="row.enabled"
            @change="toggle(row, Boolean($event))"
            :aria-label="row.name + '启停'" /></template></el-table-column
      ><el-table-column label="操作" width="200"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="edit(row)">编辑</el-button
          ><el-button
            link
            type="primary"
            @click="
              edit(row);
              test(row);
            "
            >测试通知</el-button
          ><el-button link type="danger" @click="deleteRule(row)"
            >删除</el-button
          ></template
        ></el-table-column
      ><template #empty
        ><el-empty description="暂无告警规则" :image-size="70" /></template
    ></el-table>
  </section>
  <el-dialog v-model="observationOpen" title="提交模拟观测" width="600px"
    ><el-alert
      type="warning"
      title="SIMULATED · 仅用于演示项目的规则判定与通知闭环，不代表真实设备监控。"
      :closable="false"
    /><el-form label-position="top" class="mt16"
      ><el-form-item label="模拟资产"
        ><el-select v-model="observation.assetId" filterable
          ><el-option
            v-for="a in targets.filter((a) => a.mode === 'SIMULATED')"
            :key="a.id"
            :value="a.id"
            :label="a.name" /></el-select></el-form-item
      ><el-form-item label="观测指标"
        ><el-select v-model="observation.metric"
          ><el-option
            v-for="m in ['CPU', 'MEMORY', 'DISK', 'ONLINE']"
            :key="m"
            :value="m" /></el-select></el-form-item
      ><el-form-item label="观测数值"
        ><el-input-number
          v-model="observation.value"
          :min="0"
          :max="100" /></el-form-item></el-form
    ><ErrorNotice :message="formError" @retry="submitObservation" /><template
      #footer
      ><el-button @click="observationOpen = false">取消</el-button
      ><el-button
        type="primary"
        :loading="observationBusy"
        @click="submitObservation"
        >提交模拟观测</el-button
      ></template
    ></el-dialog
  ><el-dialog v-model="handleOpen" title="处理告警" width="600px"
    ><el-alert
      type="info"
      :title="current.message || current.content || '告警处理'"
      :closable="false"
    /><el-form label-position="top" class="mt16"
      ><el-form-item label="处理状态"
        ><el-radio-group v-model="handle.status"
          ><el-radio value="ACKNOWLEDGED">处理中</el-radio
          ><el-radio value="RESOLVED">处置完成</el-radio></el-radio-group
        ></el-form-item
      ><el-form-item label="处理说明 *"
        ><el-input
          type="textarea"
          :rows="4"
          v-model="handle.note"
          placeholder="记录分析、处理动作及验证依据" /></el-form-item
      ><small
        >处置完成不会自动将设备状态修改为恢复；恢复状态由新的观测结果确认。</small
      ></el-form
    ><ErrorNotice :message="formError" @retry="saveHandle" /><template #footer
      ><el-button @click="handleOpen = false">取消</el-button
      ><el-button type="primary" :loading="handling" @click="saveHandle"
        >提交处理</el-button
      ></template
    ></el-dialog
  >
  <el-drawer
    v-model="editing"
    :title="model.id ? '编辑告警规则' : '新增告警规则'"
    size="760px"
    ><el-form label-position="top" class="form-grid"
      ><h3 class="section-heading">基本信息</h3>
      <el-form-item label="规则名称 *"
        ><el-input v-model="model.name" /></el-form-item
      ><el-form-item label="来源类型"
        ><el-select v-model="model.source"
          ><el-option label="硬件监控" value="HARDWARE" /><el-option
            label="部署失败"
            value="DEPLOYMENT" /><el-option
            label="分发失败"
            value="DISTRIBUTION" /></el-select></el-form-item
      ><el-form-item label="告警级别"
        ><el-select v-model="model.severity"
          ><el-option label="严重" value="CRITICAL" /><el-option
            label="重要"
            value="WARNING" /><el-option
            label="一般"
            value="INFO" /></el-select></el-form-item
      ><el-form-item label="启用状态"
        ><el-switch v-model="model.enabled" /></el-form-item
      ><el-form-item label="监控对象" class="full"
        ><el-select
          v-model="model.targetIds"
          multiple
          filterable
          placeholder="留空匹配项目范围内符合条件的对象"
          ><el-option
            v-for="a in targets"
            :key="a.id"
            :label="`${a.name} · ${a.id}`"
            :value="a.id" /></el-select
      ></el-form-item>
      <h3 class="section-heading">触发条件</h3>
      <el-form-item label="指标"
        ><el-select v-model="model.metric"
          ><el-option
            v-for="[value, label] in [
              ['CPU', 'CPU使用率'],
              ['MEMORY', '内存使用率'],
              ['DISK', '磁盘使用率'],
              ['ONLINE', '在线状态'],
              ['FAILURE_COUNT', '失败次数'],
            ]"
            :key="value"
            :value="value"
            :label="label" /></el-select></el-form-item
      ><el-form-item label="比较条件"
        ><el-select v-model="model.operator"
          ><el-option
            v-for="[value, label] in [
              ['GT', '大于'],
              ['GTE', '大于等于'],
              ['LT', '小于'],
              ['LTE', '小于等于'],
              ['EQ', '等于'],
            ]"
            :key="value"
            :value="value"
            :label="label" /></el-select></el-form-item
      ><el-form-item label="阈值"
        ><el-input-number
          v-model="model.threshold"
          :min="0"
          :controls="false" /></el-form-item
      ><el-form-item label="持续时间（秒）"
        ><el-input-number
          v-model="model.durationSeconds"
          :min="0"
          :controls="false"
      /></el-form-item>
      <h3 class="section-heading">通知配置</h3>
      <el-form-item label="通知方式 *" class="full"
        ><el-checkbox-group v-model="model.notificationChannels"
          ><el-checkbox value="IN_APP">站内通知</el-checkbox
          ><el-checkbox value="EMAIL">邮件</el-checkbox
          ><el-checkbox value="WEBHOOK">Webhook</el-checkbox></el-checkbox-group
        ></el-form-item
      >
      <p class="full muted">
        站内通知发送给本项目有通知查看权限的用户；邮件与 Webhook
        以连接器实际回执为准。
      </p>
      <el-form-item label="说明" class="full"
        ><el-input v-model="model.description" type="textarea" /></el-form-item
    ></el-form>
    <div class="drawer-tools">
      <el-button @click="test(model)" :disabled="!model.id">测试通知</el-button
      ><span class="muted">以通知服务回执判定发送结果</span>
    </div>
    <RecordDetail v-if="testReceipt" :record="testReceipt" /><ErrorNotice
      :message="formError"
      @retry="save"
    /><template #footer
      ><el-button @click="editing = false">取消</el-button
      ><el-button type="primary" @click="save" :loading="saving"
        >保存规则</el-button
      ></template
    ></el-drawer
  ><el-drawer v-model="detailOpen" title="告警详情与处理记录" size="760px"
    ><RecordDetail :record="detail"
  /></el-drawer>
</template>
