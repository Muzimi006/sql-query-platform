// 登录态的本地存储。
// 单独成模块是为了让 api.js 和 router.js 都能引用它，而两者之间不互相依赖
// （api.js 引 router.js、router.js 又引 api.js 会形成循环依赖）。

const TOKEN_KEY = 'sqp_token'
const USER_KEY = 'sqp_user'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
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
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export function isLoggedIn() {
  return Boolean(getToken())
}
