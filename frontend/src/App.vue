<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from './api'
import { clearToken, getUser, isLoggedIn } from './auth'

const route = useRoute()
const router = useRouter()

// computed 是「派生状态」：依赖变化时自动重算，不用手动同步。
//
// ⚠️ 这一行踩过一个很容易忽略的坑，改它之前先读完这段注释：
//
// 它原本依赖两样东西 —— isLoggedIn()（读 localStorage）和 route.name。
// 但 **localStorage 不是响应式的**，而且 isLoggedIn() && route.name !== 'login'
// 里的 **&& 是短路的**：首次求值发生在 /login 且还没 token，左边为假，
// **右边根本没执行** —— 于是这个 computed 一个响应式依赖都没登记，
// 算过一次 false 之后就永远不会再算。
//
// 症状非常具有迷惑性：**登录成功、路由也跳到了 /query，但顶栏就是不出现，
// 手动刷新一下又好了**（刷新后首次求值就带着 token，走的是另一条路径）。
//
// 根治在 auth.js：把 token 放进 ref，让 isLoggedIn() 读到响应式的值。
// 放在这里修（比如把两个条件换个顺序）也能好，但那是"碰巧"——
// 真正错的是"拿非响应式数据当 computed 的依赖"。
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
    <RouterLink to="/export">导出任务</RouterLink>
    <span class="spacer"></span>
    <span class="user">{{ user?.nickname || user?.username }}</span>
    <button class="ghost" @click="handleLogout">退出</button>
  </nav>

  <!-- 当前路由匹配到的页面组件渲染在这里 -->
  <RouterView />
</template>
