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
  // 支持的数据库类型：前端下拉框读它渲染，避免前后端各维护一份清单
  dialects: () => http.get('/datasource/dialects'),
  list: () => http.get('/datasource'),
  add: (data) => http.post('/datasource', data),
  remove: (id) => http.delete(`/datasource/${id}`),
  changeStatus: (id, status) => http.put(`/datasource/${id}/status`, null, { params: { status } }),
  test: (data) => http.post('/datasource/test', data)
}

export const queryApi = {
  execute: (data) => http.post('/query/execute', data)
}

/**
 * 下载接口专用的拆包。
 *
 * <p>为什么不能直接用上面那个响应拦截器：下载要带 Authorization 头，
 * 浏览器不能直接用 URL 打开，所以必须走 XHR/fetch —— 也就是说必须走 `blob`。
 * 而拦截器判断的是 `'code' in body`，拿到一个 Blob 时这个判断永远为 false，
 * 于是它会把响应原样返回。
 *
 * <p><b>问题在后端失败的时候</b>：本项目所有响应都是 HTTP 200 + body 里的 code
 * （包括错误）。失败时后端返回的是一段 JSON，但响应类型是 blob ——
 * 不处理的话，这段错误 JSON 会被当成 CSV 存到用户硬盘上，
 * 用户打开看到一堆乱码，而且完全不知道是为什么失败。
 *
 * <p>所以这里自己看一眼 content-type：是 JSON 就说明这是错误响应，解析出来抛出去。
 */
async function downloadCsv(id) {
  const blob = await http.get(`/export/${id}/download`, { responseType: 'blob' })

  const contentType = blob && blob.type ? blob.type : ''
  if (contentType.includes('json')) {
    const text = await blob.text()
    try {
      const body = JSON.parse(text)
      throw new Error(body.message || '下载失败')
    } catch (e) {
      // JSON.parse 自己失败时不要把它当成"后端返回了错误信息"，
      // 那说明 content-type 判断错了，把原始文本带出去更有助于排查
      if (e instanceof SyntaxError) {
        throw new Error('下载失败：响应不是合法的 CSV')
      }
      throw e
    }
  }

  return blob
}

export const exportApi = {
  list: () => http.get('/export'),
  create: (data) => http.post('/export', data),
  download: downloadCsv
}
