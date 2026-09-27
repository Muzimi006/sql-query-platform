<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { authApi } from '../api'
import { setToken, setUser } from '../auth'

const router = useRouter()

// ref 包裹的值是响应式的：模板里读 .value 由编译器自动补，JS 里必须写 .value
const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function handleSubmit() {
  error.value = ''
  loading.value = true
  try {
    // 已登录时后端会因为 token 缺失返回 401，但登录接口本身是放行的
    const data = await authApi.login({ username: username.value, password: password.value })
    setToken(data.token)
    setUser(data.user)
    router.push({ name: 'query' })
  } catch (e) {
    // 后端业务失败返回 { code: 500, message }，被响应拦截器转成了 Error
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="card login">
    <h1>登录</h1>
    <!-- @submit.prevent 阻止表单默认提交（会刷新页面），改由 JS 处理 -->
    <form @submit.prevent="handleSubmit">
      <label>
        用户名
        <input v-model="username" placeholder="用户名" autocomplete="username" />
      </label>
      <label>
        密码
        <input v-model="password" type="password" placeholder="密码" autocomplete="current-password" />
      </label>

      <p v-if="error" class="error">{{ error }}</p>

      <button type="submit" :disabled="loading">{{ loading ? '登录中…' : '登录' }}</button>
    </form>
  </div>
</template>
