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
  // 分页版列表。⚠️ 只有它是分页的，list() 会一次性把全部数据源拿回来 ——
  // 数据源通常没几条，所以两个都留着：下拉框用 list()（要全量），管理页用 page()。
  // ⚠️ 参数名是 `page` 而不是 `current`：DbSourceController#page 的形参就叫 page。
  //    传错的表现是「翻页没反应，永远返回第一页」—— 后端有默认值，不会报错。
  page: (page = 1, size = 10) => http.get('/datasource/page', { params: { page, size } }),
  add: (data) => http.post('/datasource', data),
  update: (id, data) => http.put(`/datasource/${id}`, data),
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

/* ---------------------------------------------------------------------------
 * 审核工单
 *
 * ⚠️ 这一组接口有两处"看起来一样、其实不一样"，前端必须按它的语义用：
 *
 * 1. `approve` / `reject` 返回 200 **不代表已经执行完了** ——
 *    批准之后是**异步执行**（审批 → 投 MQ → 消费者认领 → 执行 → 回写）。
 *    正确用法是拿到 200 之后**轮询 detail(id)**，看 status 变成 EXECUTED / FAILED。
 *
 * 2. `requestToken` 是幂等键：双击提交、超时重试都靠它只产生一条工单。
 *    但**同一次提交意图必须用同一个 token**；换了 SQL 却复用 token，后端会按
 *    "载荷不一致"直接报错（这是有意的，见 SqlReviewServiceImpl）。
 * ------------------------------------------------------------------------- */
export const sqlReviewApi = {
  submit: (data) => http.post('/sql-review/submit', data),
  mine: (current = 1, size = 10) => http.get('/sql-review/mine', { params: { current, size } }),
  // 仅 ADMIN：别人提交的待执行 SQL 属于敏感内容，后端按角色收口
  pending: (current = 1, size = 10) => http.get('/sql-review/pending', { params: { current, size } }),
  detail: (id) => http.get(`/sql-review/${id}`),
  approve: (id, comment) => http.post(`/sql-review/${id}/approve`, { comment: comment || null }),
  reject: (id, comment) => http.post(`/sql-review/${id}/reject`, { comment: comment || null })
}

/* ---------------------------------------------------------------------------
 * 自然语言生成 SQL（NL2SQL）
 *
 * ⚠️ 它不是"自然语言查询"：模型生成的是 INSERT / UPDATE / DELETE，
 *    并且**不会直接执行** —— 结果是一张待审工单（返回 SqlReviewRequestVO）。
 *    如果模型给的是 SELECT，后端会把语句原样返回并提示"去查询页执行"。
 *
 * `status()` 用来决定入口要不要点亮（默认关闭；打开它意味着表结构会发往外部模型服务）。
 * ------------------------------------------------------------------------- */
export const nl2sqlApi = {
  status: () => http.get('/nl2sql/status'),
  ticket: (data) => http.post('/nl2sql/ticket', data)
}

export const historyApi = {
  list: () => http.get('/history'),
  page: (params) => http.get('/history/page', { params }),
  remove: (id) => http.delete(`/history/${id}`),
  clear: () => http.delete('/history')
}

export const favoriteApi = {
  list: () => http.get('/favorite'),
  add: (data) => http.post('/favorite', data),
  remove: (id) => http.delete(`/favorite/${id}`)
}
