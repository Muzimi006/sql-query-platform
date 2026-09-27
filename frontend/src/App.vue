<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from './api'
import { clearToken, getUser, isLoggedIn } from './auth'

const route = useRoute()
const router = useRouter()

// computed 是「派生状态」：依赖变化时自动重算，不用手动同步
const loggedIn = computed(() => isLoggedIn() && route.name !== 'login')
const user = computed(() => getUser())

async function handleLogout() {
  try {
    // 登出要把 token 加入服务端 Redis 黑名单，否则这个 token 在过期前仍然有效
    await authApi.logout()
  } catch (e) {
    // 服务端登出失败也要清掉本地状态，否则用户会卡在「看起来已登录」的状态
  }
  clearToken()
  router.push({ name: 'login' })
}
</script>

<template>
  <!-- 顶栏只在登录后显示；v-if 为假时整块不渲染 -->
  <nav v-if="loggedIn" class="nav">
    <span class="brand">SQL 查询与审核平台</span>
    <RouterLink to="/query">查询工作台</RouterLink>
    <RouterLink to="/datasource">数据源管理</RouterLink>
    <span class="spacer"></span>
    <span class="user">{{ user?.nickname || user?.username }}</span>
    <button class="ghost" @click="handleLogout">退出</button>
  </nav>

  <!-- 当前路由匹配到的页面组件渲染在这里 -->
  <RouterView />
</template>
