import axios from 'axios'
import { clearToken, getToken } from './auth'

// 所有请求都走 /api 前缀，开发期由 vite.config.js 代理到 8080
const http = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 请求拦截器：统一注入 Authorization 头，避免每个调用点都手写
http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：统一拆包 + 统一处理登录失效
http.interceptors.response.use(
  (response) => {
    // 后端统一返回 { code, message, data }。
    // 注意：业务失败时 HTTP 状态码仍是 200，要靠 code 判断，不能只看 response.status
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code !== 200) {
        return Promise.reject(new Error(body.message || '请求失败'))
      }
      return body.data
    }
    return body
  },
  (error) => {
    // 401 由 JwtInterceptor 直接写出，不经过全局异常处理器，所以要在这一层处理
    if (error.response?.status === 401 && window.location.pathname !== '/login') {
      clearToken()
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default http

export const authApi = {
  login: (data) => http.post('/auth/login', data),
  register: (data) => http.post('/auth/register', data),
  logout: () => http.post('/auth/logout')
}

export const dataSourceApi = {
  list: () => http.get('/datasource'),
  add: (data) => http.post('/datasource', data),
  remove: (id) => http.delete(`/datasource/${id}`),
  changeStatus: (id, status) => http.put(`/datasource/${id}/status`, null, { params: { status } }),
  test: (data) => http.post('/datasource/test', data)
}

export const queryApi = {
  execute: (data) => http.post('/query/execute', data)
}
