<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi, nl2sqlApi, sqlReviewApi } from './api'
import { clearToken, getUser, isLoggedIn } from './auth'
import { confirmState, resolveConfirm, toasts, dismissToast } from './ui'
import AppIcon from './components/AppIcon.vue'

const route = useRoute()
const router = useRouter()

// 登录态来自 auth.js 里的 ref（响应式）。
// ⚠️ 这里踩过一个真实的坑，改之前先看 auth.js 顶部那段注释：
//    直接读 localStorage 不是响应式的，而且 `a() && b()` 的短路会吃掉依赖收集 ——
//    症状是「登录成功、路由也跳了，但侧边栏不出现，刷新一下才好」。
const loggedIn = computed(() => isLoggedIn() && route.name !== 'login')
const user = computed(() => getUser())
const isAdmin = computed(() => (user.value?.role || '') === 'ADMIN')

// 主题：默认浅色，选择存在 localStorage，刷新后保持
const theme = ref(localStorage.getItem('sqp_theme') || 'light')
function toggleTheme() {
  theme.value = theme.value === 'light' ? 'dark' : 'light'
}
watch(theme, (t) => {
  document.documentElement.setAttribute('data-theme', t)
  localStorage.setItem('sqp_theme', t)
}, { immediate: true })

// 用户菜单
const menuOpen = ref(false)
function closeMenu() { menuOpen.value = false }

// 待审批数量（只对 ADMIN 拉）。它不是"实时"的，只是一个提示，所以 30 秒拉一次足够。
const pendingCount = ref(0)
let pendingTimer = null
async function loadPendingCount() {
  if (!isAdmin.value) { pendingCount.value = 0; return }
  try {
    const page = await sqlReviewApi.pending(1, 1)
    pendingCount.value = page?.total || 0
  } catch (e) {
    // 拉不到就不显示角标：这是一个"锦上添花"的提示，不该在界面上报错打扰用户
    pendingCount.value = 0
  }
}

// NL2SQL 是否可用：默认关闭，前端据此决定要不要把入口点亮。
// 为什么不是"直接隐藏"：藏起来之后，用户永远不知道这个功能存在、也不知道怎么开。
// 现在是「显示但标注未启用」，点进去页面会解释怎么打开。
const nl2sqlAvailable = ref(false)
async function loadNl2sqlStatus() {
  if (!loggedIn.value) return
  try {
    const s = await nl2sqlApi.status()
    nl2sqlAvailable.value = Boolean(s && s.available)
  } catch (e) {
    nl2sqlAvailable.value = false
  }
}

function startPolling() {
  stopPolling()
  if (!loggedIn.value) return
  loadPendingCount()
  loadNl2sqlStatus()
  pendingTimer = setInterval(loadPendingCount, 30000)
}
function stopPolling() {
  if (pendingTimer) { clearInterval(pendingTimer); pendingTimer = null }
}

onMounted(startPolling)
onUnmounted(stopPolling)
watch(() => route.fullPath, () => { closeMenu(); startPolling() })

async function handleLogout() {
  closeMenu()
  try {
    // 登出必须打到服务端：它会把 token 加进 Redis 黑名单，否则这个 token 在过期前仍然有效
    await authApi.logout()
  } catch (e) {
    // 服务端登出失败也要清本地状态，否则用户会卡在"看起来已登录"的状态
  }
  clearToken()
  stopPolling()
  router.push({ name: 'login' })
}

const NAV = [
  { group: '查询', items: [
    { to: '/query', icon: 'query', text: '查询工作台' },
    { to: '/history', icon: 'history', text: '查询历史' },
    { to: '/favorite', icon: 'star', text: '收藏的 SQL' }
  ] },
  { group: '数据变更', items: [
    { to: '/review', icon: 'review', text: '审核工单', badge: 'pending' },
    { to: '/nl2sql', icon: 'magic', text: '自然语言生成', tag: 'nl2sql' }
  ] },
  { group: '数据与文件', items: [
    { to: '/datasource', icon: 'database', text: '数据源管理' },
    { to: '/export', icon: 'export', text: '导出任务' }
  ] }
]

const pageTitle = computed(() => route.meta?.title || 'SQL 查询与审核平台')
</script>

<template>
  <div class="shell" v-if="loggedIn">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="sidebar-brand">
        <div class="logo">SQ</div>
        <div>
          <div class="name">SQL 查询与审核</div>
          <div class="sub">查询 · 申请 · 审批 · 追责</div>
        </div>
      </div>

      <nav class="nav-group">
        <template v-for="g in NAV" :key="g.group">
          <div class="label">{{ g.group }}</div>
          <RouterLink
            v-for="item in g.items" :key="item.to"
            class="nav-item" :class="{ active: route.path === item.to }" :to="item.to"
          >
            <AppIcon :name="item.icon" :size="17" />
            <span class="txt">{{ item.text }}</span>
            <span v-if="item.badge === 'pending' && isAdmin && pendingCount > 0" class="badge">{{ pendingCount > 99 ? '99+' : pendingCount }}</span>
            <span v-else-if="item.tag === 'nl2sql' && !nl2sqlAvailable" class="tag tiny" style="margin-left:auto">未启用</span>
          </RouterLink>
        </template>
      </nav>

      <div class="sidebar-foot">
        <div class="tiny muted-2" style="padding:4px 6px 8px">
          <AppIcon name="shield" :size="12" /> 写操作需审批后执行
        </div>
      </div>
    </aside>

    <!-- 主区域 -->
    <div class="main" @click="closeMenu">
      <header class="topbar">
        <span class="title">{{ pageTitle }}</span>
        <span class="spacer"></span>
        <button class="icon-btn" :title="theme === 'light' ? '切换到深色' : '切换到浅色'" @click.stop="toggleTheme">
          <AppIcon :name="theme === 'light' ? 'moon' : 'sun'" :size="17" />
        </button>
        <div class="user-chip" @click.stop="menuOpen = !menuOpen">
          <div class="avatar">{{ (user?.nickname || user?.username || '?').slice(0, 1).toUpperCase() }}</div>
          <div class="hide-sm">
            <div class="small strong">{{ user?.nickname || user?.username }}</div>
            <div class="tiny muted-2">{{ user?.role === 'ADMIN' ? '管理员' : '普通用户' }}</div>
          </div>
        </div>
        <div v-if="menuOpen" class="menu" @click.stop>
          <div class="item" @click="router.push('/history')"><AppIcon name="clock" :size="15" />我的查询历史</div>
          <div class="item" @click="router.push('/favorite')"><AppIcon name="star" :size="15" />我的收藏</div>
          <div class="sep"></div>
          <div class="item" @click="handleLogout"><AppIcon name="logout" :size="15" />退出登录</div>
        </div>
      </header>

      <main class="content">
        <RouterView v-slot="{ Component }">
          <component :is="Component" />
        </RouterView>
      </main>
    </div>

    <!-- 全局 toast -->
    <div class="toasts">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="t.kind" @click="dismissToast(t.id)">
        <AppIcon :name="t.kind === 'ok' ? 'check' : t.kind === 'err' ? 'warn' : t.kind === 'warn' ? 'warn' : 'info'" :size="16" />
        <div class="msg">{{ t.message }}</div>
      </div>
    </div>

    <!-- 全局确认框 -->
    <Teleport to="body">
      <div v-if="confirmState.open" class="mask center" @click.self="resolveConfirm(false)">
        <div class="modal" style="width:min(420px,92vw)">
          <div class="modal-head">
            <AppIcon :name="confirmState.danger ? 'warn' : 'info'" :size="17" />
            <h2>{{ confirmState.title }}</h2>
          </div>
          <div class="modal-body">{{ confirmState.message }}</div>
          <div class="modal-foot">
            <button class="btn" @click="resolveConfirm(false)">{{ confirmState.cancelText }}</button>
            <button class="btn" :class="confirmState.danger ? 'btn-danger' : 'btn-primary'" @click="resolveConfirm(true)">
              {{ confirmState.okText }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>

  <!-- 未登录：只有路由内容（登录页），不显示任何导航 -->
  <RouterView v-else />
</template>