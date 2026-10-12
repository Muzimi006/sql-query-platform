<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from '../api'
import { setToken, setUser } from '../auth'
import AppIcon from '../components/AppIcon.vue'

/* 登录 / 注册合成一页。
 *
 * 为什么不拆成两个路由：两者字段高度重合（用户名 + 密码），拆开之后用户
 * 注册完还要自己找登录入口 —— 而这正是"注册成功但不自动登录"最容易卡住人的地方。
 *
 * ⚠️ 这一页不能用 toast。App.vue 里的 toast 容器渲染在 v-if="loggedIn" 的分支里，
 *    未登录时它根本没被挂载 —— 调 toastErr() 什么都不会显示，用户只会觉得"点了没反应"。
 *    所以错误一律走页面内的 alert alert-danger。
 */

const route = useRoute()
const router = useRouter()

const mode = ref('login') // login | register
const username = ref('')
const password = ref('')
const nickname = ref('')
const adminCode = ref('')
const error = ref('')
const okMsg = ref('')
const submitting = ref(false)

const isRegister = computed(() => mode.value === 'register')

/**
 * 切换页签。
 *
 * 必须清掉上一次的提示和密码：留着上一轮的「注册成功，请登录」再切到登录页，
 * 用户会以为刚刚这次操作也成功了；密码留着则是"切换账号却带着上一个人的密码"。
 */
function switchMode(next) {
  if (submitting.value || mode.value === next) return
  mode.value = next
  error.value = ''
  okMsg.value = ''
  password.value = ''
  adminCode.value = ''
}

async function handleSubmit() {
  if (submitting.value) return
  error.value = ''
  okMsg.value = ''

  // 前端只挡"明显发不出去"的请求，字段的真实校验以后端 @NotBlank 为准
  if (!username.value.trim()) { error.value = '请输入用户名'; return }
  if (!password.value) { error.value = '请输入密码'; return }

  submitting.value = true
  try {
    if (isRegister.value) {
      // 注册接口只返回 UserVO，**没有 token** —— 所以注册成功不能自动登录：
      // 硬跳查询页会被路由守卫（isLoggedIn() 为假）原样踢回登录页，
      // 表现是"注册完页面闪了一下又回来了"，用户完全不知道发生了什么。
      await authApi.register({
        username: username.value.trim(),
        password: password.value,
        nickname: nickname.value.trim() || null,
        adminCode: adminCode.value.trim() || null
      })
      // 这里直接改 mode，不走 switchMode —— 那个函数会把下面的成功提示一起清掉
      mode.value = 'login'
      password.value = ''
      adminCode.value = ''
      okMsg.value = '注册成功，请登录'
    } else {
      const data = await authApi.login({ username: username.value.trim(), password: password.value })
      // 顺序不能反：先落 token 再跳转。反过来的话路由守卫看到的还是未登录状态
      setToken(data.token)
      setUser(data.user)

      // redirect 是路由守卫写进来的（未登录直接点 /review/3 会被带到登录页并记住原地址）
      const redirect = route.query.redirect
      const target = typeof redirect === 'string' && redirect && !redirect.startsWith('/login')
        ? redirect
        : '/query'
      router.replace(target)
    }
  } catch (e) {
    // 业务失败在 body.code 里（HTTP 仍是 200），axios 拦截器已经把它转成了带 message 的 Error。
    // 登录失败/限流/注册过于频繁的后端文案都是给人看的，原样展示，不再包一层"操作失败"
    error.value = (e && e.message) || (isRegister.value ? '注册失败' : '登录失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="card login-card">
      <!-- 品牌区：一句话说清这个系统和"随便连个库查数据"的区别 -->
      <div class="brand">
        <div class="brand-logo">SQ</div>
        <div>
          <div class="brand-name">SQL 查询与审核</div>
          <div class="brand-sub">查询有闸门、写操作要审批</div>
        </div>
      </div>

      <div class="tabs login-tabs">
        <button class="tab" :class="{ active: !isRegister }" type="button" @click="switchMode('login')">登录</button>
        <button class="tab" :class="{ active: isRegister }" type="button" @click="switchMode('register')">注册</button>
      </div>

      <div class="card-body">
        <div v-if="error" class="alert alert-danger">
          <AppIcon name="warn" :size="16" />
          <span class="break">{{ error }}</span>
        </div>
        <div v-if="okMsg" class="alert alert-ok">
          <AppIcon name="check" :size="16" />
          <span>{{ okMsg }}</span>
        </div>

        <!-- 用 form + @submit.prevent 而不是给按钮绑 click：
             form 天然支持回车提交，也才有浏览器的密码管理器行为 -->
        <form @submit.prevent="handleSubmit">
          <div class="field">
            <label for="login-username">用户名<span class="req">*</span></label>
            <input
              id="login-username" v-model="username" class="input" type="text"
              autocomplete="username" placeholder="请输入用户名"
            />
          </div>

          <div class="field">
            <label for="login-password">密码<span class="req">*</span></label>
            <input
              id="login-password" v-model="password" class="input" type="password"
              :autocomplete="isRegister ? 'new-password' : 'current-password'"
              placeholder="请输入密码"
            />
          </div>

          <template v-if="isRegister">
            <div class="field">
              <label for="login-nickname">昵称</label>
              <input id="login-nickname" v-model="nickname" class="input" type="text" placeholder="可留空，默认显示用户名" />
            </div>
            <div class="field">
              <label for="login-admin">管理员邀请码</label>
              <input id="login-admin" v-model="adminCode" class="input" type="text" placeholder="可留空" />
              <div class="tip">
                留空即为普通用户；填写且与后端 <code>platform.admin-register-code</code> 一致时才授予管理员。
                用邀请码而不是用户名，是因为后者可以被抢注。
              </div>
            </div>
          </template>

          <button class="btn btn-primary btn-block" type="submit" :disabled="submitting">
            <span v-if="submitting" class="spinner"></span>
            {{ submitting ? (isRegister ? '注册中…' : '登录中…') : (isRegister ? '注册' : '登录') }}
          </button>
        </form>

        <div class="mt-2 tiny muted-2">
          <AppIcon name="shield" :size="12" /> UPDATE / DELETE 不会直接执行，要先提交工单、审批通过才跑。
        </div>
        <div class="mt-1 tiny muted-2">
          按 <kbd>Enter</kbd> 可直接提交。
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 背景走变量 + 一层很淡的品牌色光晕：纯平背景看起来像"没做完"，
   但光晕颜色也必须用变量，写死 #xxx 在深色模式下会非常突兀 */
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background-color: var(--bg);
  background-image: radial-gradient(1100px 480px at 50% -10%, var(--brand-weak), transparent 70%);
}

.login-card {
  width: 380px;
  max-width: 100%;
  margin: 0; /* .card 默认有 margin-bottom，居中布局里会把它顶偏 */
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 22px 20px 16px;
}

.brand-logo {
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-size: 15px;
  font-weight: 700;
  /* 用 --surface 当"底色上的字色"：浅色模式是白、深色模式自动变深，
     比写死 #fff 更安全（深色模式下品牌色变浅，白字会糊） */
  color: var(--surface);
  background: linear-gradient(135deg, var(--brand), var(--brand-strong));
}

.brand-name { font-size: 15px; font-weight: 650; }
.brand-sub { font-size: 12px; color: var(--muted); }

.login-tabs {
  padding: 0 20px;
  margin-bottom: 0; /* 下面紧跟 card-body，自带的内边距够了 */
}

/* 主按钮上的 spinner 默认是"浅灰环 + 品牌色头"，在品牌色底上几乎看不见 */
.btn-primary .spinner {
  border-color: var(--surface);
  border-top-color: transparent;
}
</style>
