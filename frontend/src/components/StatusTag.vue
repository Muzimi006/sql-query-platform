<script setup>
import { computed } from 'vue'
import { STATUS } from '../format'

// 状态标签：把后端的裸字符串（PENDING / EXECUTED / ...）翻译成中文 + 颜色。
//
// 为什么要有这么一个组件：状态在**五个页面**上都要显示，
// 如果每个页面自己写一遍 v-if 配色，早晚会出现"工单页说执行成功、导出页说失败"这种不一致。
const props = defineProps({
  value: { type: [String, Number], default: '' },
  kind: { type: String, default: 'review' }, // review | export | history | source | bool
  dot: { type: Boolean, default: true }
})

const meta = computed(() => {
  const map = STATUS[props.kind] || {}
  const key = String(props.value == null ? '' : props.value)
  return map[key] || { label: key || '—', tone: '' }
})
</script>

<template>
  <span class="tag" :class="meta.tone ? 'tag-' + meta.tone : ''">
    <i v-if="dot" class="dot"></i>{{ meta.label }}
  </span>
</template>