<script setup>
import AppIcon from './AppIcon.vue'

// 空态：列表没有数据时**不要留一片白** ——
// 用户看到空白的第一反应是"是不是坏了"，而不是"这里本来就没东西"。
defineProps({
  icon: { type: String, default: 'inbox' },
  title: { type: String, default: '暂无数据' },
  desc: { type: String, default: '' }
})
</script>

<template>
  <div class="empty">
    <AppIcon :name="icon" :size="40" class="ico" />
    <div class="t">{{ title }}</div>
    <div v-if="desc" class="d">{{ desc }}</div>
    <div v-if="$slots.default" class="mt-2"><slot /></div>
  </div>
</template>