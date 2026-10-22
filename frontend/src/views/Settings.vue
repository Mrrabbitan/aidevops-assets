<script setup lang="ts">
import { ref, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { Refresh, Connection } from "@element-plus/icons-vue";
import { clone, api, errorText, query, listOf } from "../lib/api";
import { useSession } from "../stores/session";
import CollectionSchedules from "../components/CollectionSchedules.vue";
import StatusTag from "../components/StatusTag.vue";
import ErrorNotice from "../components/ErrorNotice.vue";
import RecordDetail from "../components/RecordDetail.vue";
const s = useSession(),
  tab = ref("connections"),
  connections = ref<any[]>([]),
  events = ref<any[]>([]),
  error = ref(""),
  loading = ref(false),
  page = ref(1),
  total = ref(0),
  editing = ref(false),
  model = ref<any>({}),
  saving = ref(false),
  receipt = ref<any>(null);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [c, a] = await Promise.allSettled([
      api("/connections" + query({ projectId: s.projectId })),
      api(
        "/audit" +
          query({ projectId: s.projectId, page: page.value, pageSize: 20 }),
      ),
    ]);
    if (c.status === "fulfilled") connections.value = listOf(c.value);
    else error.value = errorText(c.reason);
    if (a.status === "fulfilled") {
      events.value = listOf(a.value);
      total.value = a.value.total ?? events.value.length;
    } else
      error.value = [error.value, errorText(a.reason)]
        .filter(Boolean)
        .join("；");
  } finally {
    loading.value = false;
  }
}
function edit(row: any) {
  model.value = clone(row);
  receipt.value = null;
  editing.value = true;
}
async function save() {
  saving.value = true;
  try {
    await api(`/connections/${model.value.id}`, {
      method: "PUT",
      json: { ...model.value, projectId: s.projectId },
    });
    ElMessage.success("连接配置已保存");
    editing.value = false;
    load();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    saving.value = false;
  }
}
async function test() {
  try {
    receipt.value = await api(`/connections/${model.value.id}/test`, {
      method: "POST",
      json: { projectId: s.projectId },
    });
  } catch (e) {
    error.value = errorText(e);
  }
}
onMounted(load);
</script>
<template>
  <section class="page-header">
    <div>
      <p class="eyebrow">CONTROL CENTER</p>
      <h1>连接配置与审计</h1>
      <p>管理集成入口，核对业务操作与变更记录</p>
    </div>
    <el-button :icon="Refresh" @click="load">刷新</el-button>
  </section>
  <ErrorNotice :message="error" @retry="load" /><el-tabs v-model="tab"
    ><el-tab-pane label="集成连接" name="connections" /><el-tab-pane
      label="审计日志"
      name="audit" /><el-tab-pane label="采集计划" name="collection"
  /></el-tabs>
  <CollectionSchedules v-if="tab === 'collection'" />
  <section v-else class="panel" v-loading="loading">
    <template v-if="tab === 'connections'"
      ><div class="panel-title">
        <h2>连接器状态</h2>
        <small class="muted">凭据以平台引用存储</small>
      </div>
      <el-table :data="connections"
        ><el-table-column
          prop="name"
          label="连接名称"
          min-width="180" /><el-table-column
          prop="kind"
          label="类型"
          width="150" /><el-table-column label="模式" width="115"
          ><template #default="{ row }"
            ><StatusTag :value="row.mode" /></template></el-table-column
        ><el-table-column label="状态" width="130"
          ><template #default="{ row }"
            ><StatusTag :value="row.status" /></template></el-table-column
        ><el-table-column
          prop="endpoint"
          label="服务端点"
          min-width="220"
          show-overflow-tooltip /><el-table-column
          prop="credentialRef"
          label="凭据引用"
          min-width="170" /><el-table-column label="操作" width="90"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="edit(row)"
              >配置</el-button
            ></template
          ></el-table-column
        ><template #empty
          ><el-empty
            description="暂无已登记连接器" /></template></el-table></template
    ><template v-else
      ><div class="panel-title">
        <h2>操作审计</h2>
        <small class="muted">审计事件由服务端记录</small>
      </div>
      <el-table :data="events"
        ><el-table-column
          prop="occurred_at"
          label="发生时间"
          min-width="180" /><el-table-column
          prop="actor"
          label="操作主体"
          min-width="160" /><el-table-column
          prop="action"
          label="动作"
          min-width="160" /><el-table-column
          prop="object_id"
          label="对象"
          min-width="210" /><el-table-column
          prop="details"
          label="详情"
          min-width="220"
          show-overflow-tooltip
      /></el-table>
      <div class="pagination">
        <span>共 {{ total }} 条审计记录</span
        ><el-pagination
          v-model:current-page="page"
          :total="total"
          :page-size="20"
          layout="prev,pager,next"
          @current-change="load"
        /></div
    ></template>
  </section>
  <el-drawer v-model="editing" title="连接配置" size="640px"
    ><el-alert
      title="真实连接需要现场适配与联通验证。保存端点与凭据引用不会自动证明联通。"
      type="info"
      :closable="false"
    /><el-form label-position="top" class="mt16"
      ><el-form-item label="连接名称"
        ><el-input v-model="model.name" /></el-form-item
      ><el-form-item label="执行模式"
        ><el-select v-model="model.mode"
          ><el-option label="真实适配 REAL" value="REAL" /><el-option
            label="模拟适配 SIMULATED"
            value="SIMULATED" /></el-select></el-form-item
      ><el-form-item label="启用"
        ><el-switch v-model="model.enabled" /></el-form-item
      ><el-form-item label="服务端点"
        ><el-input
          v-model="model.endpoint"
          placeholder="https://service.internal" /></el-form-item
      ><el-form-item label="凭据引用"
        ><el-input
          v-model="model.credentialRef"
          placeholder="vault/path/to/credential" /></el-form-item></el-form
    ><el-button :icon="Connection" @click="test">测试已保存连接</el-button
    ><RecordDetail v-if="receipt" :record="receipt" /><ErrorNotice
      :message="error"
      @retry="save"
    /><template #footer
      ><el-button @click="editing = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="save"
        >保存配置</el-button
      ></template
    ></el-drawer
  >
</template>
