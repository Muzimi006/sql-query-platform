import { ref } from 'vue'

// 登录态的本地存储。
// 单独成模块是为了让 api.js 和 router.js 都能引用它，而两者之间不互相依赖
// （api.js 引 router.js、router.js 又引 api.js 会形成循环依赖）。
//
// ⚠️ 为什么用一个 ref 包一层，而不是每次都直接读 localStorage：
//
// **localStorage 不是响应式的。** 直接读它的话，任何依赖登录态的 computed
// 都不会因为"token 变了"而重算。
//
// 这不是理论问题 —— 真实踩过。App.vue 里原本写的是：
//     const loggedIn = computed(() => isLoggedIn() && route.name !== 'login')
// 首次求值时还没登录 → isLoggedIn() 返回 false → **&& 短路，route.name 根本没被读到**
// → 这个 computed 一个响应式依赖都没有 → 它算出来一次 false 之后就**永远不会再算**。
// 表现是：**登录成功、路由也跳到了 /query，但顶栏一直不出现，必须手动刷新一次才行。**
// （刷新之后才是"首次求值就带着 token"，于是看起来正常。）
//
// 修法就是这里：把 token 放进 ref，让 isLoggedIn() 读到一个**响应式**的值。
// 这样 computed 有了真正的依赖，登录 / 登出时都会自己更新。
//
// 顺带记一条通用规律：**短路求值会吃掉依赖收集。**
// a() && b() 里当 a() 为假时，b() 从不执行 —— 于是 b() 里读到的响应式数据
// 也不会被登记成依赖。
const TOKEN_KEY = 'sqp_token'
const USER_KEY = 'sqp_user'

// ⚠️ 这两行必须在上面两个常量之后：const 有暂时性死区（TDZ），
// 写在前面会在模块求值时直接报 "Cannot access 'TOKEN_KEY' before initialization"。
const tokenState = ref(localStorage.getItem(TOKEN_KEY))

export function getToken() {
  return tokenState.value
}

export function setToken(token) {
  tokenState.value = token
  localStorage.setItem(TOKEN_KEY, token)
}

export function getUser() {
  const raw = localStorage.getItem(USER_KEY)
  return raw ? JSON.parse(raw) : null
}

export function setUser(user) {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearToken() {
  tokenState.value = null
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export function isLoggedIn() {
  return Boolean(tokenState.value)
}
