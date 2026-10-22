<script setup lang="ts">
import { computed } from "vue";
const props = defineProps<{ value?: string }>();
const names: Record<string, string> = {
  DRAFT: "草稿",
  PENDING: "待执行",
  QUEUED: "排队中",
  RUNNING: "执行中",
  SUCCEEDED: "成功",
  SUCCESS: "成功",
  FAILED: "失败",
  CANCELED: "已取消",
  CANCELLED: "已取消",
  PENDING_ADMISSION: "待入库",
  ADMITTED: "已入库",
  ONLINE: "在线",
  OFFLINE: "离线",
  UNKNOWN: "未知",
  NORMAL: "正常",
  WARNING: "重要",
  OPEN: "待处理",
  ACKNOWLEDGED: "处理中",
  RESOLVED: "已处理",
  CRITICAL: "严重",
  MAJOR: "重要",
  MINOR: "一般",
  HIGH: "严重",
  MEDIUM: "重要",
  LOW: "一般",
  SIMULATED: "模拟执行",
  REAL: "真实执行",
  READY: "可用",
  CANDIDATE: "待入库",
  INACTIVE: "已停用",
  NOT_CONFIGURED: "未接入",
  AVAILABLE: "可用",
  INFO: "一般",
  VALIDATED: "已校验",
  CONFIRMED: "已确认",
  WAITING_CONFIRMATION: "待确认",
};
const label = computed(() => names[props.value || ""] || props.value || "—");
const type = computed(() =>
  /FAILED|OFFLINE|CRITICAL|HIGH|失败|严重|离线/.test(props.value || "")
    ? "danger"
    : /RUNNING|QUEUED|CONFIRMED|执行中|部署中/.test(props.value || "")
      ? "primary"
      : /SUCCEEDED|SUCCESS|ADMITTED|ONLINE|NORMAL|RESOLVED|成功|正常|已入库|在线|可用|已处理/.test(
            props.value || "",
          )
        ? "success"
        : /WARNING|PENDING|DRAFT|SIMULATED|MAJOR|MEDIUM|待|重要|告警/.test(
              props.value || "",
            )
          ? "warning"
          : "info",
);
</script>
<template>
  <!-- Status is part of the row data: show it immediately, without ElTag appear transitions. -->
  <el-tag :type="type" effect="light" size="small" disable-transitions>{{
    label
  }}</el-tag>
</template>
