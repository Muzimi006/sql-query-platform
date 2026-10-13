import { reactive } from 'vue'

/* ============================================================================
 * 全局轻量交互：toast（右下角提示）与 confirm（确认框）。
 *
 * 为什么自己写而不是装一个 UI 库：见 style.css 顶部的说明（零 UI 依赖）。
 * 这里的实现只有几十行，而且**业务语义是对的**：
 *   · toast 有三种语义色：ok / err / warn，默认 3 秒自动消失，可手动关
 *   · confirm 返回 Promise<boolean>，所以调用点可以写 `if (!await confirmDialog(...)) return`
 * ========================================================================== */

export const toasts = reactive([])
let seq = 0

export function toast(message, kind = 'info', timeout = 3200) {
  const id = ++seq
  toasts.push({ id, message: String(message || ''), kind })
  if (timeout > 0) {
    setTimeout(() => dismissToast(id), timeout)
  }
  return id
}

export function dismissToast(id) {
  const i = toasts.findIndex((t) => t.id === id)
  if (i >= 0) toasts.splice(i, 1)
}

export const toastOk = (m) => toast(m, 'ok')
export const toastErr = (m) => toast(m, 'err', 5200)
export const toastWarn = (m) => toast(m, 'warn', 4200)

/** 统一的错误出口：axios 拦截器把业务失败包成了 Error，这里取 message 展示。 */
export function toastError(e, fallback = '操作失败') {
  const msg = e && e.message ? e.message : fallback
  return toastErr(msg)
}

export const confirmState = reactive({
  open: false,
  title: '请确认',
  message: '',
  okText: '确定',
  cancelText: '取消',
  danger: false,
  _resolve: null
})

/**
 * 确认框。用法：
 *   if (!(await confirmDialog({ message: '确定删除？', danger: true }))) return
 */
export function confirmDialog({
  title = '请确认',
  message = '',
  okText = '确定',
  cancelText = '取消',
  danger = false
} = {}) {
  // 上一次还没关就又弹了一个：把上一个按"取消"结掉，避免 Promise 永远悬着
  if (confirmState._resolve) confirmState._resolve(false)
  Object.assign(confirmState, { open: true, title, message, okText, cancelText, danger })
  return new Promise((resolve) => { confirmState._resolve = resolve })
}

export function resolveConfirm(ok) {
  confirmState.open = false
  const r = confirmState._resolve
  confirmState._resolve = null
  if (r) r(ok)
}

/** 复制到剪贴板：优先用 Clipboard API，非 https / 旧浏览器回退到 textarea + execCommand。 */
export async function copyText(text) {
  const value = String(text == null ? '' : text)
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(value)
      toastOk('已复制')
      return true
    }
  } catch (e) { /* 落到下面的回退实现 */ }
  try {
    const ta = document.createElement('textarea')
    ta.value = value
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    // execCommand 已被标记废弃，但它是内网 http 部署（非安全上下文，navigator.clipboard 不可用）
    // 下唯一还能工作的复制方式，因此保留；上面的 Clipboard API 是首选路径。
    document.execCommand('copy')
    document.body.removeChild(ta)
    toastOk('已复制')
    return true
  } catch (e) {
    toastWarn('当前浏览器不允许自动复制，请手动选中')
    return false
  }
}