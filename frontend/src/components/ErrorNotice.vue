<script setup lang="ts">
defineProps<{ message?: string }>();
defineEmits(["retry"]);
</script>
<template>
  <el-alert
    v-if="message"
    class="error-notice"
    type="error"
    :closable="false"
    show-icon
    ><template #title>{{ message }}</template
    ><el-button link type="danger" @click="$emit('retry')"
      >重试</el-button
    ></el-alert
  >
</template>
