<script setup lang="ts">
import { computed } from "vue";
defineOptions({ name: "RecordDetail" });
const props = withDefaults(defineProps<{ record: any; depth?: number }>(), {
  depth: 0,
});
const entries = computed(() => Object.entries(props.record || {}));
const labels: Record<string, string> = {
  id: "编号",
  name: "名称",
  projectId: "项目",
  type: "类型",
  kind: "业务类型",
  status: "状态",
  mode: "执行模式",
  version: "版本",
  createdAt: "创建时间",
  updatedAt: "更新时间",
  startedAt: "开始时间",
  finishedAt: "完成时间",
  owner: "负责人",
  department: "部门",
  dept: "所属部门",
  source: "来源",
  address: "管理地址",
  ip: "管理地址",
  location: "位置",
  category: "二级分类",
  config: "保存的配置",
  configuration: "配置",
  technical: "技术参数",
  operation: "运维信息",
  lifecycle: "生命周期",
  collected: "采集快照",
  error: "失败原因",
  progress: "进度",
  targets: "目标执行结果",
  targetIds: "目标编号",
  snapshot: "执行快照",
  result: "执行结果",
  notes: "备注",
  description: "说明",
  message: "结果说明",
  items: "记录",
  action: "动作",
  actor: "操作人",
  observedAt: "观测时间",
  monitor: "监控状态",
  resourceStatus: "资源状态",
  admission: "入库状态",
  connectorId: "连接器",
  externalId: "外部资源编号",
  networkScope: "网络范围",
  purpose: "用途",
  environment: "环境",
  manualFields: "人工保护字段",
  reason: "原因",
  metrics: "性能指标",
  cpu: "CPU",
  cpuCores: "CPU核数",
  memoryGb: "内存（GB）",
  diskGb: "磁盘（GB）",
  os: "操作系统",
  vendor: "厂商",
  model: "型号",
  serial: "序列号",
  architecture: "架构",
  memory: "内存容量",
  disk: "磁盘容量",
  agent: "Agent版本",
  managementAddr: "管理地址",
  firmware: "固件版本",
  ports: "端口说明",
  softwareVersion: "软件版本",
  licenseType: "授权类型",
  licenseId: "许可证编号",
  licenseExpiry: "许可证到期日",
  deployment: "部署位置",
  collection: "采集方式",
  credentialRef: "凭据引用",
  monitorPolicy: "监控策略",
  window: "维护窗口",
  purchaseDate: "采购日期",
  enabledDate: "启用日期",
  warrantyUntil: "保修期限",
  supplier: "供应商",
  platformVersion: "平台版本",
  roles: "节点角色",
  network: "管理网络",
  serviceNetwork: "服务网络",
  storage: "存储方案",
  clusterId: "所属集群",
  hostId: "宿主编号",
  template: "虚拟机模板",
  storagePool: "存储池",
  networkBridge: "网络桥接",
  namespace: "命名空间",
  strategy: "发布策略",
  healthCheck: "健康检查",
  timeoutSeconds: "超时（秒）",
  failurePolicy: "失败策略",
  artifact: "制品引用",
  artifactDigest: "制品摘要",
  rollbackBaseline: "回退基线",
  production: "生产环境",
  serviceSubject: "执行服务主体",
  schedule: "定时任务",
  enabled: "启用状态",
  intervalMinutes: "执行间隔（分钟）",
  dependsOnTaskIds: "前置依赖",
  observedRecovery: "已观测恢复",
  content: "告警内容",
  severity: "告警级别",
  actions: "处置记录",
  note: "处理说明",
  ruleId: "关联规则",
  targetId: "来源对象",
  confirmedVersion: "已确认版本",
  taskVersion: "执行配置版本",
  taskId: "任务编号",
  runId: "运行编号",
  previewId: "预览编号",
  validCount: "校验通过",
  errorCount: "校验异常",
  expiresAt: "过期时间",
  rows: "逐行结果",
  changes: "字段变化",
  protectedFields: "人工保护字段",
  before: "变更前",
  after: "变更后",
  errorCode: "错误代码",
  errorMessage: "失败原因",
  receipts: "通知回执",
  channel: "通知渠道",
  observed: "观测结果",
  observationMode: "观测来源模式",
  reservedCpu: "已预留CPU",
  reservedMemoryGb: "已预留内存（GB）",
  reservedDiskGb: "已预留磁盘（GB）",
  rollback: "回退执行",
  withdraw: "撤销部署",
  restoreConfig: "恢复配置",
  reconciliation: "人工核查记录",
  outcome: "核查结论",
  evidenceRef: "证据引用",
  comment: "核查说明",
};
const values: Record<string, string> = {
  REAL: "真实执行",
  SIMULATED: "模拟执行",
  SIMULATED_FIXTURE: "模拟发现来源",
  MANUAL: "手工录入",
  IMPORT: "文件导入",
  UNKNOWN: "未知",
  READY: "可用",
  DRAFT: "草稿",
  CANDIDATE: "待入库",
  ADMITTED: "已入库",
  INACTIVE: "已停用",
  SUCCEEDED: "成功",
  FAILED: "失败",
  RUNNING: "执行中",
  QUEUED: "排队中",
  CONFIRMED: "已确认",
  VALIDATED: "已校验",
  SERVER: "服务器",
  CLIENT: "客户端",
  NETWORK: "网络设备",
  SOFTWARE: "软件资产",
  SIMULATED_SOURCE: "模拟来源，不能据此确认真实设备健康",
  NO_OBSERVATION: "尚无有效观测",
  NOT_CONFIGURED: "未配置真实适配",
  AVAILABLE: "可用",
  IN_APP: "站内通知",
  EMAIL: "邮件",
  WEBHOOK: "Webhook",
  PREVIEW_READY: "预览已生成，待确认",
  OBSERVED: "已有观测",
  ALERT: "告警",
  OFFLINE: "离线",
};
const object = (v: any) => v !== null && typeof v === "object";
const objectArray = (v: any) => Array.isArray(v) && v.some(object);
const display = (v: any): string =>
  v == null || v === ""
    ? "—"
    : typeof v === "boolean"
      ? v
        ? "是"
        : "否"
      : Array.isArray(v)
        ? v.map(display).join("、")
        : values[String(v)] || String(v);
</script>
<template>
  <el-descriptions :column="1" border class="record-detail"
    ><el-descriptions-item
      v-for="[key, value] in entries"
      :key="key"
      :label="labels[key] || key"
      ><template v-if="objectArray(value)"
        ><details class="detail-section" :open="depth < 1">
          <summary>{{ (value as any[]).length }} 条记录</summary>
          <div v-for="(item, i) in value as any[]" :key="i" class="detail-item">
            <RecordDetail
              v-if="object(item)"
              :record="item"
              :depth="depth + 1"
            /><span v-else>{{ display(item) }}</span>
          </div>
        </details></template
      ><template v-else-if="object(value) && !Array.isArray(value)"
        ><RecordDetail
          v-if="Object.keys(value as object).length"
          :record="value"
          :depth="depth + 1"
        /><span v-else>暂无记录</span></template
      ><span v-else>{{ display(value) }}</span></el-descriptions-item
    ></el-descriptions
  >
</template>
<style scoped>
.record-detail {
  margin: 8px 0;
}
.detail-section summary {
  font-size: 11px;
  color: #4c23ba;
  cursor: pointer;
  padding: 5px 0;
}
.detail-item + .detail-item {
  margin-top: 12px;
}
.record-detail :deep(.el-descriptions__body) {
  background: transparent;
}
.record-detail :deep(.el-descriptions__cell) {
  overflow-wrap: anywhere;
}
</style>
