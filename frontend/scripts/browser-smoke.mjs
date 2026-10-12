/**
 * 前端冒烟测试：真浏览器 + 真后端 + 真 dev server。
 *
 * 为什么需要它：「构建通过」和「页面能用」之间隔着很远。前端有一整类 bug
 * 接口层永远测不到 —— 典型的是**接口全对、路由也对，就是界面不更新**
 * （本项目真踩过：登录成功但侧边栏不出现，必须手动刷新。根因是 computed 依赖了
 * 非响应式的 localStorage，见 frontend/src/auth.js 顶部注释）。
 *
 * 为什么不用 Playwright/Puppeteer：它们要么自带几百 MB 浏览器，要么要装依赖。
 * 这里用**系统已装的 Edge + CDP（Chrome DevTools Protocol）**，零依赖 ——
 * Node 22+ 自带 fetch 与 WebSocket，够用了。
 *
 * 用法：
 *   node scripts/browser-smoke.mjs [baseUrl] [edgePath]
 *   （默认 http://localhost:5173；账号可用 SMOKE_USER / SMOKE_PASS 覆盖）
 *
 * ⚠️ 前置条件：后端已起（8080）、dev server 已起（5173）、库里有一个可用账号。
 * ⚠️ 它**不能**替代人工目视：headless 看不到配色、布局溢出这类视觉问题。
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const BASE = process.argv[2] || 'http://localhost:5173'
const EDGE = process.argv[3] || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const CDP_PORT = 9333
const USER = process.env.SMOKE_USER || 'coderead01'
const PASS = process.env.SMOKE_PASS || 'Probe12345!'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/* 逐页最小断言：至少证明"它渲染出来了"，而不是白屏或跳走 */
const PAGES = [
  { path: '/query', name: '查询工作台', must: ['textarea'] },
  { path: '/history', name: '查询历史', must: ['.card'] },
  { path: '/favorite', name: '收藏的 SQL', must: ['.card'] },
  { path: '/review', name: '审核工单', must: ['.tabs', '.tab'] },
  { path: '/nl2sql', name: '自然语言生成', must: ['.card'] },
  { path: '/datasource', name: '数据源管理', must: ['.card'] },
  { path: '/export', name: '导出任务', must: ['.card'] }
]

/* ---------- 最小 CDP 客户端 ---------- */
class Cdp {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    this.consoleErrors = []
    this.pageErrors = []
    this.failedRequests = []
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id && this.pending.has(msg.id)) {
        const p = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        msg.error ? p.reject(new Error(msg.error.message)) : p.resolve(msg.result)
        return
      }
      this.onEvent(msg)
    })
  }

  onEvent(msg) {
    if (msg.method === 'Runtime.exceptionThrown') {
      const d = msg.params.exceptionDetails || {}
      this.pageErrors.push((d.exception && d.exception.description) || d.text || 'unknown')
    }
    if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
      this.consoleErrors.push((msg.params.args || []).map((a) => a.value || a.description || '').join(' '))
    }
    if (msg.method === 'Network.loadingFailed') {
      this.failedRequests.push(msg.params.errorText || 'failed')
    }
  }

  send(method, params = {}) {
    const id = ++this.id
    this.ws.send(JSON.stringify({ id, method, params }))
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }))
  }

  /** 在页面里跑一段 JS。awaitPromise 让页面内的 fetch / Promise 能直接返回结果。 */
  async eval(expression) {
    const r = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
    if (r.exceptionDetails) {
      const d = r.exceptionDetails
      throw new Error('页面内脚本抛错：' + ((d.exception && d.exception.description) || d.text))
    }
    return r.result ? r.result.value : undefined
  }

  async goto(url) {
    await this.send('Page.navigate', { url })
    await sleep(600)
    // 等 Vue 渲染：轮询 #app 是否已经有子节点
    for (let i = 0; i < 40; i++) {
      const ready = await this.eval('document.querySelector("#app") && document.querySelector("#app").children.length > 0')
      if (ready) break
      await sleep(150)
    }
    await sleep(250)
  }
}

/* ---------- 启动 Edge 并连上它 ---------- */
async function launchEdge() {
  const profile = mkdtempSync(join(tmpdir(), 'sqp-smoke-'))
  const args = [
    '--headless=new',
    '--remote-debugging-port=' + CDP_PORT,
    '--user-data-dir=' + profile,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-gpu',
    'about:blank'
  ]
  const proc = spawn(EDGE, args, { stdio: 'ignore' })

  let target = null
  for (let i = 0; i < 80; i++) {
    try {
      const list = await (await fetch('http://127.0.0.1:' + CDP_PORT + '/json/list')).json()
      target = list.find((t) => t.type === 'page')
      if (target) break
    } catch (e) { /* 端口还没起来，继续等 */ }
    await sleep(250)
  }
  if (!target) throw new Error('Edge 的 CDP 端口没起来（检查 EDGE 路径）')

  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve)
    ws.addEventListener('error', reject)
  })
  return { proc, profile, cdp: new Cdp(ws) }
}

/* ---------- 断言小工具 ---------- */
const results = []
function check(name, ok, detail) {
  results.push({ name, ok, detail: detail || '' })
  console.log((ok ? '  [OK] ' : '  [!!] ') + name + (detail ? '  —— ' + detail : ''))
}

async function main() {
  const { proc, profile, cdp } = await launchEdge()
  try {
    await cdp.send('Runtime.enable')
    await cdp.send('Page.enable')
    await cdp.send('Network.enable')

    console.log('')
    console.log('[1] 未登录时的路由守卫')
    await cdp.goto(BASE + '/query')
    const path1 = await cdp.eval('location.pathname')
    check('未登录访问 /query 被重定向到 /login', path1 === '/login', '实际 ' + path1)
    const formOk = await cdp.eval('document.querySelectorAll("input").length >= 2 && !!document.querySelector("button")')
    check('登录页渲染出表单', formOk === true)

    console.log('')
    console.log('[2] 真登录（走界面，不是直接写 localStorage）')
    // ⚠️ 直接改 el.value 不会触发 Vue 的 v-model，必须补发一个 input 事件
    const fill = '(() => {' +
      'const set = (el, v) => {' +
        'const d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), "value");' +
        'd.set.call(el, v);' +
        'el.dispatchEvent(new Event("input", { bubbles: true }));' +
      '};' +
      'const inputs = document.querySelectorAll("input");' +
      'set(inputs[0], ' + JSON.stringify(USER) + ');' +
      'set(inputs[1], ' + JSON.stringify(PASS) + ');' +
      // ⚠️ 必须精确定位**表单里的 submit 按钮**：页面上还有"登录 / 注册"两个页签，
      //    它们也是 button，而且排在表单前面 —— 按文字找会点到页签上，表单根本不会提交。
      //    CSS 属性选择器的值可以不加引号（[type=submit]），这样就不用嵌套转义了。
      'const btn = document.querySelector("form button[type=submit]");' +
      'if (btn) btn.click();' +
      'return !!btn;' +
    '})()'
    const clicked = await cdp.eval(fill)
    check('找到并点击了登录按钮', clicked === true)
    await sleep(2000)
    const path2 = await cdp.eval('location.pathname')
    // 失败时把页面上的错误提示抓出来 —— 否则只知道"没进去"，不知道为什么
    const alertText = path2 === '/login'
      ? await cdp.eval('((document.querySelector(".alert-danger") || {}).textContent || "").trim()')
      : ''
    check('登录成功后进入工作台', path2 === '/query',
      path2 !== '/query'
        ? '实际 ' + path2 + (alertText ? '，页面提示：' + alertText : '（若账号不存在请设 SMOKE_USER / SMOKE_PASS）')
        : '')

    console.log('')
    console.log('[3] 登录后侧边栏必须立刻出现（V-11 那个 bug 的守卫）')
    const navCount = await cdp.eval('document.querySelectorAll(".nav-item").length')
    check('侧边栏导航项已渲染（无需刷新）', navCount >= 7, 'nav-item 数量 ' + navCount)
    const brand = await cdp.eval('(document.querySelector(".sidebar-brand .name") || {}).textContent || ""')
    check('侧边栏品牌区有文字', String(brand).length > 0, brand)

    console.log('')
    console.log('[4] 逐页渲染')
    for (const p of PAGES) {
      await cdp.goto(BASE + p.path)
      const here = await cdp.eval('location.pathname')
      if (here !== p.path) { check(p.name + ' 路由可达', false, '被重定向到 ' + here); continue }
      const missing = []
      for (const sel of p.must) {
        const n = await cdp.eval('document.querySelectorAll(' + JSON.stringify(sel) + ').length')
        if (!n) missing.push(sel)
      }
      check(p.name + ' 渲染出关键元素', missing.length === 0, missing.length ? '缺 ' + missing.join(',') : '')
    }

    console.log('')
    console.log('[5] 刷新后仍保持登录（token 在 localStorage 里）')
    await cdp.goto(BASE + '/datasource')
    const stillIn = await cdp.eval('location.pathname')
    check('直接打开 /datasource 不被踢回登录页', stillIn === '/datasource', '实际 ' + stillIn)

    console.log('')
    console.log('[6] 错误账（页面异常 / console.error / 失败请求）')
    const ignorable = (s) => /favicon/i.test(s)
    const pe = cdp.pageErrors.filter((s) => !ignorable(s))
    const ce = cdp.consoleErrors.filter((s) => !ignorable(s))
    const fr = cdp.failedRequests.filter((s) => !ignorable(s))
    check('没有未捕获的页面异常', pe.length === 0, pe.slice(0, 3).join(' | '))
    check('没有 console.error', ce.length === 0, ce.slice(0, 3).join(' | '))
    check('没有失败的请求', fr.length === 0, fr.slice(0, 3).join(' | '))

    const failed = results.filter((r) => !r.ok)
    console.log('')
    console.log('===== 结果：' + (results.length - failed.length) + '/' + results.length + ' 通过 =====')
    if (failed.length) {
      console.log('失败项：')
      failed.forEach((f) => console.log('  - ' + f.name + (f.detail ? '（' + f.detail + '）' : '')))
      process.exitCode = 1
    }
  } finally {
    proc.kill()
    await sleep(300)
    try { rmSync(profile, { recursive: true, force: true }) } catch (e) { /* 删不掉不影响结论 */ }
  }
}

main().catch((e) => {
  console.error('冒烟测试自身失败：' + e.message)
  process.exitCode = 2
})