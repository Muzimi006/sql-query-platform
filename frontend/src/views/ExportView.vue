<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { dataSourceApi, exportApi } from '../api'
import { copyText, toastError, toastOk, toastWarn } from '../ui'
import { fmtNumber, fmtRelative, fmtTime, isExportRunning, sqlSummary } from '../format'
import AppIcon from '../components/AppIcon.vue'
import AppEmpty from '../components/AppEmpty.vue'
import StatusTag from '../components/StatusTag.vue'

/* 导出任务。
 *
 * 这个页面是**异步任务页**，和纯 CRUD 页面的写法不一样，两条硬规则：
 *  1. 创建成功只代表"任务入队了"，不代表 CSV 已经生成 —— 必须轮询列表看状态。
 *  2. 只有 downloadable 为 true 才给下载按钮：文件过了保留期会被清理任务删掉，
 *     那时 status 仍是 SUCCESS，但 downloadable 已经是 false，点下载只会得到一句报错。
 */

const sources = ref([])
const tasks = ref([])
const loading = ref(false)
const loadFailed = ref(false) // 加载失败和"没有任务"都是空列表，但要说的话完全不一样
const creating = ref(false)
const downloadingId = ref(null)

const form = ref({ dataSourceId: null, sql: '' })

/** 还在排队/生成中的任务数：> 0 才需要轮询，全部跑完就把定时器停掉（空转只是白耗资源）。 */
const runningCount = computed(() => tasks.value.filter((t) => isExportRunning(t.status)).length)
const successCount = computed(() => tasks.value.filter((t) => t.status === 'SUCCESS').length)
const failedCount = computed(() => tasks.value.filter((t) => t.status === 'FAILED').length)

/** 只有启用的数据源能用：后端在创建导出时会校验启用状态，前端提前拦一下少一次失败往返。 */
const enabledSources = computed(() => sources.value.filter((s) => s.status === 1))

/* 轮询定时器必须存成模块级的变量并在卸载时清掉。
 * 不清的话：切走页面后它还在后台每 3 秒发一次请求，页面来回切几次就会叠出好几个定时器，
 * 表现为"越切越卡、请求越打越多"。 */
let pollTimer = null

/** 上一轮请求还没回来就再发一次，会在后端变慢时叠出一堆并发请求（而且响应顺序还不保证）。
 *  用这个标记做"同一时刻只跑一轮"。 */
let polling = false

const POLL_MS = 3000

function startPolling() {
  if (pollTimer !== null) return
  pollTimer = setInterval(async () => {
    if (polling) return
    polling = true
    try {
      await refreshTasks(true)
    } finally {
      polling = false
    }
  }, POLL_MS)
}

function stopPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/**
 * 拉列表。
 *
 * @param silent 轮询触发的刷新。轮询失败**不能弹 toast** —— 3 秒一次会把屏幕刷满，
 *               而且后端一挂用户什么别的都看不到了。静默失败 + 停掉轮询即可。
 */
async function refreshTasks(silent = false) {
  if (!silent) loading.value = true
  try {
    tasks.value = (await exportApi.list()) || []
    loadFailed.value = false
    // 状态驱动轮询：这一轮拉完发现全都跑完了，就把定时器停掉
    if (runningCount.value > 0) startPolling()
    else stopPolling()
  } catch (e) {
    loadFailed.value = true
    stopPolling()
    if (silent) toastWarn('刷新导出任务失败，已停止自动刷新')
    else toastError(e, '加载导出任务失败')
  } finally {
    loading.value = false
  }
}

async function loadSources() {
  try {
    sources.value = (await dataSourceApi.list()) || []
    const first = enabledSources.value[0]
    if (first && !form.value.dataSourceId) form.value.dataSourceId = first.id
  } catch (e) {
    toastError(e, '加载数据源失败')
  }
}

function sourceLabel(id) {
  const hit = sources.value.find((s) => s.id === id)
  if (!hit) return '数据源 #' + id + '（已删除）'
  return hit.name
}

async function handleCreate() {
  if (creating.value) return
  if (!form.value.dataSourceId) { toastWarn('请先选择数据源'); return }
  if (!form.value.sql.trim()) { toastWarn('请填写要导出的 SQL'); return }

  creating.value = true
  try {
    const task = await exportApi.create({
      dataSourceId: form.value.dataSourceId,
      sql: form.value.sql.trim()
    })
    toastOk('导出任务 #' + task.id + ' 已创建，正在后台生成')
    // 顺序不能反：先把新任务拉进列表再启动轮询。
    // 反过来的话第一轮 tick 可能拉到一个还不含新任务的响应（请求竞态），
    // 于是 runningCount 为 0、轮询自停，列表永远停在"排队中"
    await refreshTasks(true)
    startPolling()
  } catch (e) {
    // 后端可能返回：只允许 SELECT / 数据源已停用 / 提交太频繁 / SQL 不合法。
    // 限流命中时不要自动重试，把后端那句话原样给用户看
    toastError(e, '创建导出任务失败')
  } finally {
    creating.value = false
  }
}

async function handleDownload(task) {
  if (downloadingId.value !== null) return
  downloadingId.value = task.id

  // createObjectURL 生成的 URL 会把 Blob 钉在内存里，直到页面卸载或显式 revoke，
  // 所以放在 finally 里释放（await 之后抛错也不会漏）
  let url = ''
  try {
    const blob = await exportApi.download(task.id)

    // 不能直接 window.open(url)：浏览器自己发起的请求带不上 Authorization 头，
    // 服务端会按未登录处理。所以必须先用 XHR 把文件取成 Blob，再自己触发保存
    url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'export-task-' + task.id + '.csv'
    link.style.display = 'none' // 不隐藏的话页面上会闪一下这个链接
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    toastOk('任务 #' + task.id + ' 开始下载')
  } catch (e) {
    // api.js 的 downloadCsv 已经识别了"响应其实是 JSON 错误体"的情况，
    // 所以这里拿到的 message 是后端的人话（例如「导出文件已过期清理，请重新导出」）
    toastError(e, '下载失败')
  } finally {
    if (url) URL.revokeObjectURL(url)
    downloadingId.value = null
  }
}

/** 失败任务的重试：把原来的数据源和 SQL 填回表单，用户确认后再创建。
 *  为什么不自动重发：失败原因多半是 SQL 本身（语法 / 超行数上限），
 *  原样重发只会再失败一次，还白白多一条失败记录。 */
function retryTask(task) {
  // SQL 先填回去：无论数据源还能不能用，用户改过的 SQL 都是最有价值的信息
  form.value.sql = task.sqlText || ''
  const source = sources.value.find((s) => s.id === task.dataSourceId)
  if (!source) {
    // 数据源被删了就**不要**再写 dataSourceId：下拉框里没有这个 option，
    // 选择框会显示成空白，用户看到的是"表单坏了"而不是"要重选一个"
    toastWarn('原数据源已被删除，SQL 已填回表单，请重新选择数据源')
    return
  }
  if (source.status !== 1) {
    toastWarn('原数据源已停用，SQL 已填回表单，启用后才能重新导出')
    return
  }
  form.value.dataSourceId = source.id
  toastOk('已填回表单，调整后点「创建导出任务」')
}

function clearForm() {
  form.value.sql = ''
}

onMounted(async () => {
  // 两个请求互不依赖，并发拿；串行会让首屏白等一个 RTT
  await Promise.all([loadSources(), refreshTasks()])
  // 打开页面时可能已经有上次留下的任务在跑（创建完没等就刷新了页面）
  if (runningCount.value > 0) startPolling()
})

onUnmounted(stopPolling)
</script>

<template>
  <div>
    <div class="stats">
      <div class="stat">
        <div class="k"><AppIcon name="export" :size="13" />任务总数</div>
        <div class="v">{{ fmtNumber(tasks.length) }}</div>
        <div class="s">只显示你自己的任务</div>
      </div>
      <div class="stat">
        <div class="k"><AppIcon name="clock" :size="13" />进行中</div>
        <div class="v">{{ fmtNumber(runningCount) }}</div>
        <div class="s">{{ runningCount > 0 ? '每 3 秒自动刷新' : '当前没有排队中的任务' }}</div>
      </div>
      <div class="stat">
        <div class="k"><AppIcon name="check" :size="13" />已完成</div>
        <div class="v">{{ fmtNumber(successCount) }}</div>
        <div class="s">文件可能已过保留期，以「可下载」为准</div>
      </div>
      <div class="stat">
        <div class="k"><AppIcon name="warn" :size="13" />失败</div>
        <div class="v">{{ fmtNumber(failedCount) }}</div>
        <div class="s">失败原因见列表，可调整后重新创建</div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>创建导出任务</h2>
        <span class="spacer"></span>
        <span class="hint hide-sm">异步执行 · 单次上限 10 万行</span>
      </div>

      <div class="card-body">
        <div class="alert alert-info">
          <AppIcon name="info" :size="16" />
          <span>
            导出是<b>异步</b>的：创建成功只代表任务已入队，真正的 CSV 由后台消费者生成。
            有任务处于排队中 / 生成中时，下面的列表每 3 秒自动刷新一次。
          </span>
        </div>

        <div v-if="!enabledSources.length" class="alert alert-warn">
          <AppIcon name="warn" :size="16" />
          <span>
            没有可用的数据源（还没添加，或全部被停用了）。
            <RouterLink to="/datasource">去数据源管理</RouterLink> 添加或启用一个。
          </span>
        </div>

        <div class="field">
          <label for="exp-source">数据源<span class="req">*</span></label>
          <select id="exp-source" v-model.number="form.dataSourceId" class="select" :disabled="creating">
            <!-- 停用的数据源仍然列出来但不可选：直接隐藏会让用户以为数据源丢了，
                 标注出来才知道是被停用了（和查询页保持同一套处理） -->
            <option v-for="s in sources" :key="s.id" :value="s.id" :disabled="s.status !== 1">
              {{ s.name }}（{{ s.host }}:{{ s.port }}）{{ s.status === 1 ? '' : '（已停用）' }}
            </option>
          </select>
        </div>

        <div class="field">
          <label for="exp-sql">SQL<span class="req">*</span></label>
          <textarea
            id="exp-sql" v-model="form.sql" class="textarea code" spellcheck="false"
            placeholder="例如：select id, name, created_at from users where created_at >= '2026-01-01'"
          ></textarea>
          <div class="tip">只支持单条 SELECT；写语句请走「审核工单」，审批通过后才执行。</div>
        </div>

        <div class="tiny muted-2 mb-2">
          <div>· 单次最多 <b>10 万行</b>，超出会让整个任务失败 —— 宁可报错，也不给你一个"看起来完整"的半个文件。</div>
          <div>· <b>不受查询页 1000 行截断的限制</b>：导出就是为了把结果完整拿走。</div>
          <div>· CSV 带 UTF-8 BOM，Excel 直接双击打开不乱码；文件有保留期，过期后会被清理任务删除。</div>
        </div>

        <div class="row">
          <button class="btn btn-primary" type="button" :disabled="creating || !enabledSources.length" @click="handleCreate">
            <span v-if="creating" class="spinner"></span>
            <AppIcon v-else name="export" :size="15" />{{ creating ? '创建中…' : '创建导出任务' }}
          </button>
          <button class="btn btn-ghost" type="button" :disabled="creating || !form.sql" @click="clearForm">清空 SQL</button>
          <span class="spacer"></span>
          <span class="tiny muted-2 hide-sm">创建后可以离开本页，任务在后台继续跑</span>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>导出任务</h2>
        <span class="spacer"></span>
        <span v-if="runningCount > 0" class="hint">
          <span class="spinner"></span> 每 3 秒自动刷新
        </span>
        <span v-else class="hint hide-sm">没有进行中的任务，自动刷新已停止</span>
        <button class="btn btn-sm" type="button" :disabled="loading" @click="refreshTasks(false)">
          <AppIcon name="refresh" :size="14" />刷新
        </button>
      </div>

      <div v-if="loading" class="loading-line">
        <span class="spinner"></span>加载中…
      </div>

      <AppEmpty
        v-else-if="!tasks.length"
        icon="export"
        :title="loadFailed ? '导出任务加载失败' : '还没有导出任务'"
        :desc="loadFailed ? '可能是网络或服务端问题，可以先重试一次。' : '在上面的表单里选一个数据源、写一条 SELECT，就能把结果导成 CSV。'"
      >
        <button v-if="loadFailed" class="btn" type="button" @click="refreshTasks(false)">
          <AppIcon name="refresh" :size="15" />重新加载
        </button>
      </AppEmpty>

      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>数据源</th>
              <th>SQL 摘要</th>
              <th>状态</th>
              <th class="num">行数</th>
              <th>错误信息</th>
              <th>创建时间</th>
              <th>完成时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in tasks" :key="task.id">
              <td class="mono nowrap">#{{ task.id }}</td>
              <td class="nowrap">{{ sourceLabel(task.dataSourceId) }}</td>
              <td>
                <div class="row">
                  <span class="mono small break" :title="task.sqlText">{{ sqlSummary(task.sqlText, 56) }}</span>
                  <button class="icon-btn" type="button" title="复制完整 SQL" @click="copyText(task.sqlText)">
                    <AppIcon name="copy" :size="14" />
                  </button>
                </div>
              </td>
              <td><StatusTag :value="task.status" kind="export" /></td>
              <td class="num">{{ fmtNumber(task.rowCount) }}</td>
              <td class="break">
                <template v-if="task.errorMessage">
                  <div class="small text-danger">{{ task.errorMessage }}</div>
                  <div class="tiny muted-2">可点「重新创建」调整 SQL 后重试</div>
                </template>
                <span v-else class="muted-2">—</span>
              </td>
              <td class="nowrap" :title="fmtTime(task.createdAt)">
                <div class="small">{{ fmtRelative(task.createdAt) }}</div>
                <div class="tiny muted-2 hide-sm">{{ fmtTime(task.createdAt) }}</div>
              </td>
              <td class="nowrap">
                <span v-if="task.finishedAt" :title="fmtTime(task.finishedAt)">{{ fmtTime(task.finishedAt) }}</span>
                <span v-else class="muted-2">—</span>
              </td>
              <td>
                <div class="actions">
                  <!-- 只看 downloadable：SUCCESS 但文件已被清理的任务，downloadable 是 false，
                       给它一个下载按钮只会让用户点了之后吃一句报错 -->
                  <button
                    v-if="task.downloadable" class="btn btn-sm" type="button"
                    :disabled="downloadingId === task.id" @click="handleDownload(task)"
                  >
                    <span v-if="downloadingId === task.id" class="spinner"></span>
                    <AppIcon v-else name="download" :size="14" />{{ downloadingId === task.id ? '下载中…' : '下载 CSV' }}
                  </button>
                  <button v-else-if="task.status === 'FAILED'" class="btn btn-sm" type="button" @click="retryTask(task)">
                    <AppIcon name="refresh" :size="14" />重新创建
                  </button>
                  <span v-else-if="task.status === 'SUCCESS'" class="tiny muted-2">文件已过期清理</span>
                  <span v-else class="tiny muted-2">生成中，稍候…</span>
                </div>
              </td>
            </tr>
          </tbody>
          <caption>状态由后台消费者回写，页面每 3 秒刷新一次；行数在任务成功前都是「—」。</caption>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 失败原因可能是一整句后端文案（"导出结果超过 100000 行上限，请缩小查询范围…"），
   用错误色而不是普通正文色，扫一眼就能定位到出问题的行 */

/* .spinner 默认 16px：放进 btn-sm 或 card-head 的 12px 小字里会把行高撑起来，
   看起来像"整行在抖"。这两处统一压到 12px */
.btn-sm .spinner,
.card-head .spinner {
  width: 12px;
  height: 12px;
  border-width: 2px;
}

/* 主按钮上的 spinner 默认是浅灰环 + 品牌色头，在品牌色底上几乎看不见 */
.btn-primary .spinner {
  border-color: var(--surface);
  border-top-color: transparent;
}
</style>
