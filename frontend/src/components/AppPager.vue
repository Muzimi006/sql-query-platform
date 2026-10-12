<script setup>
import AppIcon from './AppIcon.vue'

// 分页条。后端的分页返回是 MyBatis-Plus 的 IPage 结构：
//   { records, total, size, current, pages }
// 这里只依赖 total / current / size 三个字段，pages 自己算 ——
// 免得后端换实现时前端跟着坏。
const props = defineProps({
  total: { type: Number, default: 0 },
  current: { type: Number, default: 1 },
  size: { type: Number, default: 10 },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['update:current'])

function pages() {
  return Math.max(1, Math.ceil(props.total / props.size))
}
function go(p) {
  const max = pages()
  const next = Math.min(Math.max(1, p), max)
  if (next !== props.current) emit('update:current', next)
}
</script>

<template>
  <div class="pager" v-if="total > 0">
    <span class="info">共 {{ total }} 条 · 第 {{ current }} / {{ pages() }} 页</span>
    <span class="spacer"></span>
    <button class="btn btn-sm" :disabled="disabled || current <= 1" @click="go(1)">首页</button>
    <button class="btn btn-sm" :disabled="disabled || current <= 1" @click="go(current - 1)">
      <AppIcon name="arrowLeft" :size="14" />上一页
    </button>
    <button class="btn btn-sm" :disabled="disabled || current >= pages()" @click="go(current + 1)">
      下一页<AppIcon name="arrowRight" :size="14" />
    </button>
    <button class="btn btn-sm" :disabled="disabled || current >= pages()" @click="go(pages())">末页</button>
  </div>
</template>