<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { dataSourceApi, favoriteApi, historyApi, queryApi } from '../api'
import { copyText, confirmDialog, toastError, toastOk, toastWarn } from '../ui'
import { fmtCost, fmtNumber, fmtRelative, sqlSummary, typeTone } from '../format'
import AppEmpty from '../components/AppEmpty.vue'
import AppIcon from '../components/AppIcon.vue'
import StatusTag from '../components/StatusTag.vue'

const route = useRoute()
const router = useRouter()

/* ============================================================================
 * 表单
 * ========================================================================== */
const form = ref({ dataSourceId: null, sql: '' })
const sources = ref([])
const sourcesLoading = ref(false)
const sourcesError = ref('')

/** 编辑器元素。回填历史 SQL 之后要把焦点落回去，不然用户还得自己点一下。 */
const sqlBox = ref(null)

/* ============================================================================
 * 执行
 * ========================================================================== */
const running = ref(false)
/** 成功时的 QueryVO：{ columns, rows, costMs, truncated, affectedRows }。null = 这一次还没有结果。 */
const result = ref(null)
/** 失败时的后端人话。清空/重新执行前必须重置，否则上一次的错误会挂在新结果旁边。 */
const execError = ref('')

/**
 * 「这一次执行」的快照。
 *
 * <p>⚠️ 结果卡上的数据源名必须读它，不能读 form.dataSourceId ——
 * 执行完用户随手把下拉框切成别的库，统计块就会显示成"这条结果来自另一个库"，
 * 而那个库他根本没查过。同理，收藏 / 复制用的也是这里记下的 SQL，
 * 而不是编辑框里可能已经被改过的内容。
 */
const lastRun = ref({ sql: '', sourceId: null, sourceName: '', sourceAddr: '', type: '' })

/* ============================================================================
 * 收藏弹窗
 * ========================================================================== */
const favOpen = ref(false)
const favRemark = ref('')
const favSaving = ref(false)
/** 本次执行是否已经收藏过。后端 addFavorite 是直接 insert、不做去重，前端只能在会话里挡一下重复点击。 */
const favDone = ref(false)

/* ============================================================================
 * 最近 5 次查询
 * ========================================================================== */
const recent = ref([])
const recentLoading = ref(false)
const recentError = ref('')

/* ============================================================================
 * 计算属性
 * ========================================================================== */
const hasEnabledSource = computed(() => sources.value.some((s) => s.status === 1))

/**
 * 是不是"不返回结果集"的语句。
 *
 * <p>判据是 affectedRows 非 null：后端对 INSERT / UPDATE / DELETE 返回空 columns/rows
 * 加上影响行数（见 QueryServiceImpl#buildUpdateResult）。只按"rows 为空"判断的话，
 * 会把"查到 0 行"和"这条语句本来就不返回数据"混成一句话，前者是数据问题、后者是正常结果。
 */
const isWrite = computed(() => {
  const r = result.value
  return !!r && r.affectedRows !== null && r.affectedRows !== undefined
})

const columns = computed(() => (result.value && Array.isArray(result.value.columns) ? result.value.columns : []))
const hasRows = computed(() => !!result.value && Array.isArray(result.value.rows) && result.value.rows.length > 0)
const runTone = computed(() => typeTone(lastRun.value.type))

/**
 * 限流 / 并发 / 熔断这三类失败，等几秒重试就会好，和"SQL 写错了"是两回事。
 * 后端文案里带这些词，用来额外补一句提示；匹配不上也不影响主提示。
 */
const retryable = computed(() => /频繁|并发|繁忙|熔断|稍后/.test(execError.value || ''))

// 只影响界面上那行快捷键提示的文案。navigator.platform 已废弃，所以兜底看 userAgent
const isMac = typeof navigator !== 'undefined'
  && /Mac|iPhone|iPad|iPod/.test(navigator.platform || navigator.userAgent || '')

/* ============================================================================
 * 数据加载
 * ========================================================================== */

/**
 * 把「选中的数据源」校准到一个可用的值上。
 *
 * <p>三种情况必须分开处理，最后一种最容易漏：
 *  · 当前选的就是启用中的 → 不动；
 *  · 当前选的数据源被停用了 → 换第一个启用的，并明确告诉用户（静默切换比报错更让人困惑）；
 *  · 当前 id 在列表里根本不存在（数据源被删了，或者链接是别人给的）→ 也要换，
 *    否则下拉框会显示空白，点执行才报"数据源不能为空"。
 */
function reconcileSelection() {
  const list = sources.value
  const current = list.find((s) => s.id === form.value.dataSourceId)
  if (current && current.status === 1) return

  const firstEnabled = list.find((s) => s.status === 1)
  const next = firstEnabled ? firstEnabled.id : null

  if (current && current.status !== 1) {
    toastWarn(`数据源「${current.name}」已停用，已切换到${firstEnabled ? '「' + firstEnabled.name + '」' : '空（没有其他可用数据源）'}`)
  } else if (form.value.dataSourceId != null) {
    toastWarn('原来的数据源已不存在，已为你选择第一个可用数据源')
  }
  form.value.dataSourceId = next
}

async function loadSources() {
  sourcesLoading.value = true
  sourcesError.value = ''
  try {
    sources.value = (await dataSourceApi.list()) || []
    reconcileSelection()
  } catch (e) {
    // 下拉框拉不到 = 这一页基本没法用，所以既给页内 alert（可重试）也给 toast
    sourcesError.value = e.message || '数据源列表加载失败'
    toastError(e, '数据源列表加载失败')
  } finally {
    sourcesLoading.value = false
  }
}

async function loadRecent() {
  recentLoading.value = true
  recentError.value = ''
  try {
    // ⚠️ 参数名是 page / size（HistoryController 的 @RequestParam 就是这么写的），
    //    不是数据源分页接口用的 current —— 抄错不会报错，只会静默地永远停在第一页
    const page = await historyApi.page({ page: 1, size: 5 })
    recent.value = page && Array.isArray(page.records) ? page.records : []
  } catch (e) {
    recentError.value = e.message || '最近查询加载失败'
    toastError(e, '最近查询加载失败')
  } finally {
    recentLoading.value = false
  }
}

/* ============================================================================
 * 跨页带参：历史 / 收藏 → 查询页
 * ========================================================================== */

/**
 * 读 route.query 回填表单，然后把参数从地址栏清掉。
 *
 * <p>为什么要清：不清的话用户按一次 F5 就又把同一条旧 SQL 灌回编辑器
 * （他可能早就改成别的了），而且地址栏上挂着整条 SQL，又长又容易被误分享出去。
 *
 * <p>守卫「两个参数都没有就直接 return」是必须的：这个函数同时挂在 watch 上，
 * 而它自己会触发一次 replace → query 变化 → watch 再跑一遍，没有守卫就是死循环。
 */
function consumeRouteQuery() {
  const q = route.query || {}
  const rawSql = typeof q.sql === 'string' ? q.sql : ''
  const rawId = typeof q.dataSourceId === 'string' ? q.dataSourceId : ''
  if (!rawSql && !rawId) return

  if (rawSql) form.value.sql = rawSql
  if (rawId) {
    const id = Number(rawId)
    // 地址栏里的东西不可信：不是数字就忽略，别把 NaN 塞进 v-model
    if (Number.isFinite(id)) form.value.dataSourceId = id
    else toastWarn('地址栏里的数据源参数不合法，已忽略')
  }
  // 数据源还没拉回来时不能校准：此时列表为空是"还没拿到"，不是"一个都没有"，
  // 校准会把刚带进来的 id 一起抹掉
  if (sources.value.length) reconcileSelection()

  const rest = { ...q }
  delete rest.sql
  delete rest.dataSourceId
  router.replace({ path: route.path, query: rest })
}

/* ============================================================================
 * 执行
 * ========================================================================== */

/**
 * 取 SQL 的第一个关键字当"语句类型"，<b>只用于结果卡上那个标签</b>。
 * 真正的类型判定与拦截在后端（SqlValidateUtil + SqlPermissionService），
 * 前端这点字符串处理不做任何安全判断，也不该被当成判断依据。
 */
function sqlType(sql) {
  const m = String(sql || '').match(/^\s*(?:--[^\n]*\n|\/\*[\s\S]*?\*\/|\s)*([a-zA-Z]+)/)
  return m ? m[1].toUpperCase() : ''
}

async function handleExecute() {
  // 双保险：按钮已经 disabled，但 Ctrl+Enter 是不经过按钮的
  if (running.value) return

  const sqlText = form.value.sql.trim()
  if (!form.value.dataSourceId) {
    toastWarn('请先选择一个数据源')
    return
  }
  if (!sqlText) {
    toastWarn('请输入要执行的 SQL')
    sqlBox.value?.focus()
    return
  }
  const picked = sources.value.find((s) => s.id === form.value.dataSourceId)
  if (picked && picked.status !== 1) {
    toastWarn(`数据源「${picked.name}」已停用，不能执行查询`)
    return
  }

  // 先清掉上一次的结果再发请求：宁可让用户看到"执行中"，
  // 也不要让上一轮的结果留在屏幕上冒充这一轮的结果
  result.value = null
  execError.value = ''
  favDone.value = false
  lastRun.value = {
    sql: sqlText,
    sourceId: form.value.dataSourceId,
    sourceName: picked ? picked.name : '#' + form.value.dataSourceId,
    sourceAddr: picked ? [picked.type, picked.host + ':' + picked.port, picked.databaseName].filter(Boolean).join(' · ') : '',
    type: sqlType(sqlText)
  }
  running.value = true

  try {
    const vo = await queryApi.execute({ dataSourceId: form.value.dataSourceId, sql: sqlText })
    result.value = vo
    if (vo && vo.truncated) {
      // 提示条和 toast 都给一遍：用户可能正盯着下面的表格，不会去看上面那条
      toastWarn('结果超过 1000 行已被截断，请加 LIMIT 或更精确的 WHERE')
    }
    // 不 await：这次执行已经写进历史了（失败也写），但历史卡只是附属信息，
    // 不值得让它拖慢结果的呈现
    loadRecent()
  } catch (e) {
    // 后端把限流 / 并发拒绝 / 熔断 / SQL 报错统一包成业务失败（HTTP 仍然是 200），
    // 拦截器已经转成 Error，这里原样把后端那句人话显示出来
    execError.value = e.message || '查询失败'
    toastError(e, '查询失败')
    loadRecent()
  } finally {
    // 放 finally：任何一条抛出路径都不能让按钮永远停在"执行中"
    running.value = false
  }
}

/**
 * SQL 编辑器里的键盘操作。
 *
 * <p>Ctrl/Cmd + Enter 执行：写 SQL 时手一直在键盘上，让人去点按钮是最烦的打断。
 * <p>Tab 插入两个空格：textarea 里 Tab 默认是"跳到下一个控件"，
 * 想手动对齐一段 SQL 就会直接跳出输入框，体验很差。
 */
function onSqlKeydown(e) {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    e.preventDefault()
    handleExecute()
    return
  }
  if (e.key === 'Tab') {
    e.preventDefault()
    const el = e.target
    const start = el.selectionStart
    const end = el.selectionEnd
    const text = form.value.sql
    form.value.sql = text.slice(0, start) + '  ' + text.slice(end)
    // ⚠️ 光标位置必须在 DOM 更新之后再设：v-model 会重写 textarea 的值，
    //    同步设过去会被这一次重写冲掉，表现为"光标跳回开头"
    nextTick(() => {
      el.selectionStart = el.selectionEnd = start + 2
    })
  }
}

async function handleClear() {
  // 编辑器里已经有内容时才算破坏性操作（清掉就没法撤销了）；
  // 空的时候还要弹一次确认，是纯粹的打扰
  if (form.value.sql.trim()
    && !(await confirmDialog({ title: '清空', message: '当前 SQL 和结果都会被清掉，确定吗？', okText: '清空', danger: true }))) {
    return
  }
  form.value.sql = ''
  result.value = null
  execError.value = ''
  lastRun.value = { sql: '', sourceId: null, sourceName: '', sourceAddr: '', type: '' }
  favDone.value = false
  sqlBox.value?.focus()
}

/* ============================================================================
 * 结果区
 * ========================================================================== */

/**
 * 单元格文本。
 *
 * <p>null / undefined 显示成 — 而不是空字符串：空白格看起来像界面坏了，
 * "—"才明确表示"这格没有值"（SQL 里的 NULL 和空串是两件事，这里不做合并）。
 *
 * <p>对象要走 JSON.stringify：数组 / JSON 列直接渲染会得到 [object Object]。
 *
 * <p>数字<b>不做千分位</b>（虽然 format.js 有 fmtNumber）：结果表里 id、年份、金额原样才可信，
 * 给 id 加上千分位逗号会被当成另一个数。
 */
function cellText(v) {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'object') {
    try {
      return JSON.stringify(v)
    } catch (err) {
      return String(v)
    }
  }
  return String(v)
}

async function handleCopySql() {
  // copyText 自己会弹「已复制 / 浏览器不允许」的 toast，这里不重复弹
  await copyText(lastRun.value.sql || form.value.sql)
}

/* ============================================================================
 * 收藏
 * ========================================================================== */
function openFavorite() {
  favRemark.value = ''
  favOpen.value = true
}

function closeFavorite() {
  if (favSaving.value) return
  favOpen.value = false
}

async function submitFavorite() {
  if (favSaving.value) return
  const sqlText = lastRun.value.sql
  if (!sqlText) {
    toastWarn('没有可收藏的 SQL')
    return
  }
  favSaving.value = true
  try {
    await favoriteApi.add({
      dataSourceId: lastRun.value.sourceId,
      sqlText,
      // 空备注传 null 而不是空串：空串会在收藏页渲染出一个空的备注行
      remark: favRemark.value.trim() || null
    })
    favDone.value = true
    favOpen.value = false
    toastOk('已收藏，可在「收藏的 SQL」里找到')
  } catch (e) {
    toastError(e, '收藏失败')
  } finally {
    favSaving.value = false
  }
}

/** Esc 关弹窗。⚠️ 只在弹窗打开期间挂监听，关闭时立刻摘掉 —— 常驻监听会在别的页面也吃掉 Esc。 */
function onEsc(e) {
  if (e.key === 'Escape') closeFavorite()
}
watch(favOpen, (open) => {
  if (open) window.addEventListener('keydown', onEsc)
  else window.removeEventListener('keydown', onEsc)
})

/* ============================================================================
 * 最近查询：点击回填
 * ========================================================================== */
function fillFrom(item) {
  if (!item || !item.sqlText) {
    toastWarn('这条记录没有 SQL 内容')
    return
  }
  form.value.sql = item.sqlText

  // 顺带把数据源也切过去（同一条 SQL 在不同库上的结果可能完全不同），
  // 但只在那个数据源还在、且启用时才切：切到一个已停用的库，
  // 用户要点执行才会报错，错误发现得太晚
  const target = sources.value.find((s) => s.id === item.dataSourceId)
  if (target && target.status === 1) {
    form.value.dataSourceId = target.id
    toastOk('已回填 SQL')
  } else if (item.dataSourceId) {
    toastWarn('已回填 SQL（原数据源已删除或停用，保留了当前选择）')
  } else {
    toastOk('已回填 SQL')
  }

  nextTick(() => {
    const el = sqlBox.value
    if (!el) return
    el.focus()
    // 光标落在末尾：回填完多半是要接着改这条 SQL
    el.setSelectionRange(el.value.length, el.value.length)
  })
}

/* ============================================================================
 * 生命周期
 * ========================================================================== */
onMounted(async () => {
  // 先消费地址栏参数，再拉数据源：反过来的话 loadSources 里的默认选中会先执行，
  // 把跨页带进来的 dataSourceId 覆盖掉
  consumeRouteQuery()
  await Promise.all([loadSources(), loadRecent()])
})

// 同一条路由只换 query 是不会重新挂载组件的（Vue 复用实例），
// 只靠 onMounted 会漏掉"已经在查询页、又被推了一次带参地址"的情况
watch(() => route.query, consumeRouteQuery)

onUnmounted(() => {
  window.removeEventListener('keydown', onEsc)
})
</script>

<template>
  <div>
    <!-- 表单卡 + 最近查询卡并排。
         用 flex-wrap 而不是固定两栏：窄屏（比如侧边栏占满的笔记本分屏）会自动折成上下两段，
         不需要额外写媒体查询，也不会把 SQL 输入框挤成一条缝 -->
    <div class="row wrap" style="align-items:flex-start; gap:var(--gap)">
      <div style="flex:1 1 560px; min-width:0">
        <div class="card">
          <div class="card-head">
            <AppIcon name="query" :size="16" />
            <h2>SQL 查询</h2>
            <span class="spacer"></span>
            <span class="hint">仅支持单条语句，服务端会做白名单校验</span>
          </div>

          <div class="card-body">
            <!-- 数据源列表拉不到是"这一页没法用"级别的故障，所以要给出重试入口，
                 而不是只有一个空下拉框让用户干瞪眼 -->
            <div v-if="sourcesError" class="alert alert-danger">
              <AppIcon name="warn" :size="16" />
              <div>
                <div class="strong">{{ sourcesError }}</div>
                <div class="small mt-1">数据源列表是这一页的前置条件，加载失败时无法执行任何查询。</div>
                <div class="mt-2">
                  <button class="btn btn-sm" :disabled="sourcesLoading" @click="loadSources">
                    <AppIcon name="refresh" :size="14" />重新加载
                  </button>
                </div>
              </div>
            </div>

            <div v-else-if="!sourcesLoading && !sources.length" class="alert alert-info">
              <AppIcon name="info" :size="16" />
              <div>
                <div class="strong">你还没有可用的数据源</div>
                <div class="small mt-1">查询必须指定一个数据源，先去添加一个再回来。</div>
                <div class="mt-2">
                  <RouterLink class="btn btn-sm" to="/datasource">
                    <AppIcon name="plus" :size="14" />去数据源管理
                  </RouterLink>
                </div>
              </div>
            </div>

            <div class="field">
              <label>数据源<span class="req">*</span></label>
              <select class="select" v-model.number="form.dataSourceId" :disabled="running || sourcesLoading">
                <option :value="null" disabled>{{ sourcesLoading ? '加载中…' : '请选择数据源' }}</option>
                <!-- 已停用的数据源仍然列出来，只是不能选：
                     直接过滤掉会让用户以为"数据源丢了"，标注出来才知道是被停用了 -->
                <option v-for="s in sources" :key="s.id" :value="s.id" :disabled="s.status !== 1">
                  {{ s.name }}（{{ s.type }} · {{ s.host }}:{{ s.port }}）{{ s.status === 1 ? '' : '· 已停用，不可用' }}
                </option>
              </select>
              <div class="tip">
                <template v-if="sourcesLoading">正在加载数据源…</template>
                <template v-else-if="!hasEnabledSource">当前没有启用中的数据源，已停用的库不能执行 SQL。</template>
                <template v-else>只能选启用中的数据源；停用的库仍然列出来，方便你知道它还在。</template>
              </div>
            </div>

            <div class="field mb-0">
              <label>SQL<span class="req">*</span></label>
              <textarea
                ref="sqlBox"
                class="textarea code"
                v-model="form.sql"
                rows="10"
                spellcheck="false"
                placeholder="select * from your_table limit 100"
                @keydown="onSqlKeydown"
              ></textarea>
              <div class="tip">
                单次最多返回 1000 行，超过会被截断；单条语句有 30 秒超时。
                按 <kbd>Tab</kbd> 缩进两个空格，按 <kbd>{{ isMac ? '⌘' : 'Ctrl' }}</kbd> + <kbd>Enter</kbd> 执行。
              </div>
            </div>
          </div>

          <div class="card-foot">
            <button class="btn btn-primary" :disabled="running || sourcesLoading" @click="handleExecute">
              <span v-if="running" class="spinner" style="border-top-color:currentColor"></span>
              <AppIcon v-else name="play" :size="15" />
              {{ running ? '执行中…' : '执行' }}
            </button>
            <span class="row tiny muted-2" style="gap:4px">
              <kbd>{{ isMac ? '⌘' : 'Ctrl' }}</kbd><span>+</span><kbd>Enter</kbd>
            </span>
            <span class="spacer"></span>
            <button class="btn btn-ghost btn-sm" :disabled="running" @click="handleClear">
              <AppIcon name="trash" :size="14" />清空
            </button>
          </div>
        </div>
      </div>

      <div style="flex:0 1 320px; min-width:260px">
        <div class="card">
          <div class="card-head">
            <AppIcon name="history" :size="16" />
            <h2>最近 5 次查询</h2>
            <span class="spacer"></span>
            <button class="icon-btn" title="刷新" :disabled="recentLoading" @click="loadRecent">
              <AppIcon name="refresh" :size="15" />
            </button>
          </div>

          <div class="card-body tight">
            <div v-if="recentLoading" class="loading-line">
              <span class="spinner"></span>加载中…
            </div>

            <div v-else-if="recentError" class="col">
              <div class="small" style="color:var(--danger)">{{ recentError }}</div>
              <button class="btn btn-sm" @click="loadRecent"><AppIcon name="refresh" :size="14" />重试</button>
            </div>

            <AppEmpty
              v-else-if="!recent.length"
              icon="history"
              title="还没有查询记录"
              desc="执行一次查询后，最近 5 条会出现在这里"
            />

            <div v-else class="table-wrap">
              <table class="table">
                <tbody>
                  <!-- 整行可点：历史的价值就是"把上次那条捞回来接着改"，
                       再让用户去点一个小小的"回填"按钮是多余的 -->
                  <tr
                    v-for="h in recent"
                    :key="h.id"
                    style="cursor:pointer"
                    title="点击回填到上面的 SQL 输入框"
                    @click="fillFrom(h)"
                  >
                    <td>
                      <div class="row" style="gap:6px">
                        <StatusTag :value="h.status" kind="history" />
                        <span class="tiny muted-2 nowrap" :title="h.createdAt">{{ fmtRelative(h.createdAt) }}</span>
                      </div>
                      <div class="small break mt-1" :title="h.sqlText">{{ sqlSummary(h.sqlText, 60) }}</div>
                      <!-- 失败的记录把原因也带出来：不然用户点回填、再执行一次、
                           又失败，才知道上次也是这条 SQL 的问题 -->
                      <div v-if="h.errorMessage" class="tiny mt-1" style="color:var(--danger)">
                        {{ sqlSummary(h.errorMessage, 60) }}
                      </div>
                    </td>
                  </tr>
                </tbody>
                <caption>只显示最近 5 条，完整列表见「查询历史」</caption>
              </table>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 结果卡。整卡单独占一行：结果表列数不可控，需要完整的横向空间 -->
    <div class="card">
      <div class="card-head" style="flex-wrap:wrap">
        <AppIcon name="chart" :size="16" />
        <h2>执行结果</h2>
        <span v-if="lastRun.type" class="tag" :class="runTone ? 'tag-' + runTone : ''">{{ lastRun.type }}</span>
        <span v-if="result && !isWrite" class="hint">{{ columns.length }} 列</span>
        <span class="spacer"></span>
        <template v-if="result">
          <button class="btn btn-sm" :disabled="favDone || running" @click="openFavorite">
            <AppIcon name="star" :size="14" />{{ favDone ? '已收藏' : '收藏这条 SQL' }}
          </button>
          <button class="btn btn-sm" @click="handleCopySql">
            <AppIcon name="copy" :size="14" />复制 SQL
          </button>
        </template>
      </div>

      <div class="card-body">
        <!-- 1. 执行中 -->
        <div v-if="running" class="loading-line">
          <span class="spinner"></span>
          正在执行，请稍候…（单条 SQL 有 30 秒超时保护）
        </div>

        <!-- 2. 失败：后端 message 就是给用户看的人话，原样显示；
                另外必须说清楚"这是业务失败，不是平台崩了"，
                否则用户第一反应是"系统坏了，再点几次试试" -->
        <div v-else-if="execError" class="alert alert-danger" style="margin-bottom:0">
          <AppIcon name="warn" :size="16" />
          <div>
            <div class="strong">{{ execError }}</div>
            <div class="small mt-1">
              这是业务侧的失败，不是系统异常：服务端没有把这次执行当成成功，
              查询历史里会记为「失败」。改好 SQL 或换个数据源再试即可。
            </div>
            <div v-if="retryable" class="small mt-1">
              这条提示属于限流 / 排队类，不是你的 SQL 有问题，等几秒再点执行就行。
            </div>
          </div>
        </div>

        <!-- 3. 还没执行过。不留白屏：白屏会让人以为页面坏了 -->
        <AppEmpty
          v-else-if="!result"
          icon="query"
          title="还没有执行查询"
          desc="选好数据源、写上 SQL，点「执行」或按 Ctrl / ⌘ + Enter 开始"
        />

        <!-- 4. 有结果 -->
        <template v-else>
          <div class="stats">
            <div class="stat">
              <div class="k">
                <AppIcon :name="isWrite ? 'bolt' : 'inbox'" :size="13" />{{ isWrite ? '影响行数' : '返回行数' }}
              </div>
              <div class="v">{{ isWrite ? fmtNumber(result.affectedRows) : fmtNumber(result.rows ? result.rows.length : 0) }}</div>
              <div class="s">{{ isWrite ? '这条语句不返回结果集' : '单次上限 1000 行' }}</div>
            </div>
            <div class="stat">
              <div class="k"><AppIcon name="clock" :size="13" />耗时</div>
              <div class="v">{{ fmtCost(result.costMs) }}</div>
              <div class="s">从语句发给数据库开始计时</div>
            </div>
            <div class="stat">
              <div class="k"><AppIcon name="database" :size="13" />数据源</div>
              <div class="v" style="font-size:16px; word-break:break-all">{{ lastRun.sourceName }}</div>
              <div class="s">{{ lastRun.sourceAddr || '—' }}</div>
            </div>
          </div>

          <!-- 截断必须说清楚，且要说"你拿到的是前 1000 行、不是全量"：
               不说的话用户会拿这份数据去做汇总，结论直接是错的 -->
          <div v-if="result.truncated" class="alert alert-warn">
            <AppIcon name="warn" :size="16" />
            <div>
              <div class="strong">结果超过 1000 行已被截断，请加 LIMIT 或更精确的 WHERE</div>
              <div class="small mt-1">
                下面这 {{ fmtNumber(result.rows.length) }} 行是前 1000 行，<span class="strong">不是全部数据</span>。
                直接拿它去做汇总统计，得到的结论会是错的。
              </div>
            </div>
          </div>

          <!-- 写语句：后端明确区分了"查到 0 行"和"这条语句不返回结果集"，
               前端也要区分，不能把 UPDATE 成功显示成一个空结果 -->
          <div v-if="isWrite" class="alert alert-ok" style="margin-bottom:0">
            <AppIcon name="check" :size="16" />
            <div>
              <div class="strong">语句执行成功，影响 {{ fmtNumber(result.affectedRows) }} 行</div>
              <div class="small mt-1">
                写操作已经真的落库了（谁、什么时候、执行了什么都会记在查询历史里），
                不会出现在结果表里 —— 它本来就不返回结果集。
              </div>
            </div>
          </div>

          <!-- 空结果：执行是成功的，只是没数据。这跟"执行失败"是两件事 -->
          <AppEmpty
            v-else-if="!hasRows"
            icon="query"
            title="查询成功，但没有匹配的数据"
            desc="这条 SQL 正常执行完了，返回 0 行 —— 可以试着放宽 WHERE 条件，或确认表里确实有数据"
          />

          <div v-else class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <!-- 表头按后端返回的 columns 动态渲染，不在前端假设任何列名 -->
                  <th v-for="c in columns" :key="c">{{ c }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, i) in result.rows" :key="i">
                  <!-- 数据格一律 nowrap：换行的单元格会把行高撑到几十上百像素，
                       1000 行根本没法上下扫；横向溢出交给 table-wrap 滚动。
                       title 放完整值，滚到后面时也能确认这一格到底是什么 -->
                  <td
                    v-for="c in columns"
                    :key="c"
                    class="nowrap"
                    :title="cellText(row[c])"
                  >{{ cellText(row[c]) }}</td>
                </tr>
              </tbody>
              <caption>表格可横向滚动；单元格内容过长时，鼠标悬停可看完整值</caption>
            </table>
          </div>
        </template>
      </div>
    </div>

    <!-- 收藏弹窗 -->
    <Teleport to="body">
      <div v-if="favOpen" class="mask center" @click.self="closeFavorite">
        <div class="modal" style="width:min(560px,92vw)">
          <div class="modal-head">
            <AppIcon name="star" :size="17" />
            <h2>收藏这条 SQL</h2>
            <span class="spacer"></span>
            <button class="icon-btn" title="关闭" :disabled="favSaving" @click="closeFavorite">
              <AppIcon name="close" :size="16" />
            </button>
          </div>

          <div class="modal-body">
            <div class="field">
              <label>SQL</label>
              <!-- 只读展示，不在这里改：这个弹窗只负责"给它起个名字"，
                   真要改 SQL 应该回编辑框改完重新执行，否则收藏的和验证过的那条就不是同一条了 -->
              <div class="code">{{ lastRun.sql }}</div>
              <div class="tip">收藏时带的正是这次执行过的 SQL（数据源：{{ lastRun.sourceName }}）。</div>
            </div>

            <div class="field mb-0">
              <label>备注</label>
              <input
                class="input"
                v-model="favRemark"
                maxlength="200"
                placeholder="例如：每日活跃用户统计，运营每周一要看"
                @keydown.enter.prevent="submitFavorite"
              />
              <div class="tip">写清楚用途，下次在收藏页一眼就能找到它；可以不填。</div>
            </div>
          </div>

          <div class="modal-foot">
            <button class="btn" :disabled="favSaving" @click="closeFavorite">取消</button>
            <button class="btn btn-primary" :disabled="favSaving" @click="submitFavorite">
              <span v-if="favSaving" class="spinner" style="border-top-color:currentColor"></span>
              {{ favSaving ? '收藏中…' : '收藏' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
