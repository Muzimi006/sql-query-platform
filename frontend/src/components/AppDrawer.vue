<script setup>
import AppIcon from './AppIcon.vue'

// 右侧抽屉：用来放"详情 + 操作"，不打断用户对列表的上下文。
// v-model:open 控制开关；点遮罩、按 Esc 都关。
const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' },
  width: { type: String, default: '720px' }
})
const emit = defineEmits(['update:open'])

function close() { emit('update:open', false) }
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="mask right" @click.self="close" @keydown.esc="close" tabindex="-1">
      <div class="drawer" :style="{ width }">
        <div class="drawer-head">
          <h2>{{ title }}</h2>
          <span class="spacer"></span>
          <button class="icon-btn" @click="close" title="关闭"><AppIcon name="close" :size="16" /></button>
        </div>
        <div class="drawer-body"><slot /></div>
        <div class="drawer-foot" v-if="$slots.footer"><slot name="footer" /></div>
      </div>
    </div>
  </Teleport>
</template>