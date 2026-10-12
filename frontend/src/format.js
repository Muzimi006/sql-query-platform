/* ============================================================================
 * 展示层的小工具：时间、数字、状态字典、SQL 摘要。
 *
 * ⚠️ 状态字典集中在这里，理由和 StatusTag 组件一样：
 * 状态文案在 5 个页面上都要出现，散开写必然出现前后不一致。
 * ========================================================================== */

/** 后端返回的是 LocalDateTime（Jackson 配置成 ISO-8601 字符串，如 2026-10-12T15:04:05）。
 *  这里只做"把 T 换成空格"，不做时区换算 —— 后端的时间就是本机时区，前端再做一次换算反而错。 */
export function fmtTime(v) {
  if (!v) return '—'
  const s = String(v)
  return s.length >= 19 ? s.slice(0, 19).replace('T', ' ') : s.replace('T', ' ')
}

/** 只显示到分钟，列表里够用。 */
export function fmtTimeShort(v) {
  const s = fmtTime(v)
  return s === '—' ? s : s.slice(0, 16)
}

/** 相对时间：列表里一眼看出"刚刚 / 3 分钟前"。 */
export function fmtRelative(v) {
  if (!v) return '—'
  const t = new Date(String(v).replace(' ', 'T')).getTime()
  if (Number.isNaN(t)) return fmtTime(v)
  const diff = Date.now() - t
  if (diff < 0) return fmtTime(v)
  const sec = Math.floor(diff / 1000)
  if (sec < 60) return '刚刚'
  const min = Math.floor(sec / 60)
  if (min < 60) return min + ' 分钟前'
  const hour = Math.floor(min / 60)
  if (hour < 24) return hour + ' 小时前'
  const day = Math.floor(hour / 24)
  if (day < 30) return day + ' 天前'
  return fmtTime(v)
}

/** 千分位。注意 null / undefined 要显示成 —，不能显示 0（"没有这个数"和"是 0"是两件事）。 */
export function fmtNumber(v) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  return n.toLocaleString('zh-CN')
}

/** 毫秒换成人话：820 → 820 ms；12500 → 12.5 s。 */
export function fmtCost(ms) {
  if (ms === null || ms === undefined) return '—'
  const n = Number(ms)
  if (Number.isNaN(n)) return '—'
  return n < 1000 ? n + ' ms' : (n / 1000).toFixed(2) + ' s'
}

/** SQL 摘要：列表里只显示一行，完整内容进详情。 */
export function sqlSummary(sql, max = 70) {
  if (!sql) return '—'
  const one = String(sql).replace(/\s+/g, ' ').trim()
  return one.length > max ? one.slice(0, max) + '…' : one
}

/** 语句类型 → 标签色。写操作给醒目色：审批人一眼看出"这条会改数据"。 */
export function typeTone(type) {
  const t = String(type || '').toUpperCase()
  if (t === 'SELECT') return 'info'
  if (t === 'INSERT') return 'ok'
  if (t === 'UPDATE') return 'warn'
  if (t === 'DELETE') return 'danger'
  return ''
}

/**
 * 状态字典。⚠️ key 必须和后端枚举字面量完全一致 ——
 * 拼错的表现是"状态标签显示成英文原文"，不报错，所以很容易漏。
 */
export const STATUS = {
  review: {
    PENDING: { label: '待审批', tone: 'warn' },
    APPROVED: { label: '已批准 · 执行中', tone: 'info' },
    REJECTED: { label: '已驳回', tone: '' },
    EXECUTING: { label: '执行中', tone: 'info' },
    EXECUTED: { label: '已执行', tone: 'ok' },
    FAILED: { label: '执行失败', tone: 'danger' }
  },
  export: {
    PENDING: { label: '排队中', tone: 'warn' },
    RUNNING: { label: '生成中', tone: 'info' },
    SUCCESS: { label: '已完成', tone: 'ok' },
    FAILED: { label: '失败', tone: 'danger' }
  },
  history: {
    SUCCESS: { label: '成功', tone: 'ok' },
    FAILED: { label: '失败', tone: 'danger' }
  },
  source: {
    1: { label: '启用', tone: 'ok' },
    0: { label: '停用', tone: '' }
  },
  undo: {
    // ⚠️ key 必须是 CAPTURED（后端 UndoStatus.CAPTURED），不是 RECORDED ——
    //    写错的症状是标签把 CAPTURED 原样显示成英文，不报错，很难发现。
    CAPTURED: { label: '已留存', tone: 'ok' },
    SKIPPED: { label: '未留存', tone: 'warn' },
    FAILED: { label: '留存失败', tone: 'danger' },
    NONE: { label: '—', tone: '' }
  },
  bool: {
    true: { label: '是', tone: 'ok' },
    false: { label: '否', tone: '' }
  }
}

export function statusLabel(kind, value) {
  const m = (STATUS[kind] || {})[String(value)]
  return m ? m.label : String(value == null ? '—' : value)
}

/** 工单是否还在"跑"，决定详情页要不要继续轮询。 */
export function isReviewRunning(status) {
  return status === 'APPROVED' || status === 'EXECUTING'
}

/** 导出任务是否还在跑。 */
export function isExportRunning(status) {
  return status === 'PENDING' || status === 'RUNNING'
}

/** 生成一个幂等键。crypto.randomUUID 在 http（非 localhost）下可能不可用，所以有回退。 */
export function newRequestToken() {
  try {
    if (window.crypto && window.crypto.randomUUID) return window.crypto.randomUUID()
  } catch (e) { /* 回退 */ }
  return 'tok-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
}