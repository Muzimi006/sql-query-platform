<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { dataSourceApi, exportApi } from '../api'

const sources = ref([])
const tasks = ref([])
const dataSourceId = ref(null)
const sql = ref('select * from user')
const error = ref('')
const message = ref('')
const creating = ref(false)
const downloadingId = ref(null)

/**
 * 轮询定时器。
 * 必须存下来并在组件卸载时清掉 —— 不清的话，用户切到别的页面之后
 * 这个定时器还在后台一直发请求，页面来回切换几次就会累积出好几个。
 */
let pollTimer = null

/** 是否还有任务在处理中。有才需要轮询，没有就停 —— 空转的轮询只会白耗资源。 */
const hasRunningTask = computed(() =>
  tasks.value.some((t) => t.status === 'PENDING' || t.status === 'RUNNING')
)

async function loadSources() {
  try {
    sources.value = await dataSourceApi.list()
    const firstEnabled = sources.value.find((s) => s.status === 1)
    if (firstEnabled && !dataSourceId.value) dataSourceId.value = firstEnabled.id
  } catch (e) {
    error.value = e.message
  }
}

async function loadTasks() {
  try {
    tasks.value = await exportApi.list()
  } catch (e) {
    error.value = e.message
  }
}

function stopPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startPolling() {
  if (pollTimer !== null) return
  pollTimer = setInterval(async () => {
    await loadTasks()
    if (!hasRunningTask.value) stopPolling()
  }, 1000)
}

async function handleCreate() {
  error.value = ''
  message.value = ''
  if (!dataSourceId.value) {
    error.value = '请先选择数据源'
    return
  }

  creating.value = true
  try {
    const task = await exportApi.create({ dataSourceId: dataSourceId.value, sql: sql.value })
    message.value = `导出任务 #${task.id} 已创建，正在后台执行`
    await loadTasks()
    startPolling()
  } catch (e) {
    error.value = e.message
  } finally {
    creating.value = false
  }
}

async function handleDownload(task) {
  error.value = ''
  downloadingId.value = task.id
  try {
    const blob = await exportApi.download(task.id)

    // 用 createObjectURL + 一个临时 <a> 触发下载。
    // 不能直接 window.open(URL)：那样带不上 Authorization 头，服务端会返回 401。
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `export-${task.id}.csv`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)

    // 必须释放：createObjectURL 创建的 URL 会一直把 Blob 钉在内存里，
    // 直到页面卸载或显式 revoke
    URL.revokeObjectURL(url)
  } catch (e) {
    error.value = e.message
  } finally {
    downloadingId.value = null
  }
}

function statusText(status) {
  return {
    PENDING: '排队中',
    RUNNING: '执行中',
    SUCCESS: '已完成',
    FAILED: '失败'
  }[status] || status
}

onMounted(async () => {
  await Promise.all([loadSources(), loadTasks()])
  // 页面刚打开时可能已经有别的任务在跑（比如上一次没等到结果就刷新了）
  if (hasRunningTask.value) startPolling()
})

onUnmounted(stopPolling)
</script>

<template>
  <div class="page">
    <div class="card">
      <h2>创建导出任务</h2>
      <div class="row">
        <label>
          数据源
          <select v-model.number="dataSourceId">
            <option v-for="s in sources" :key="s.id" :value="s.id" :disabled="s.status !== 1">
              {{ s.name }}（{{ s.host }}:{{ s.port }}）{{ s.status === 1 ? '' : '（已停用）' }}
            </option>
          </select>
        </label>
      </div>
      <label>
        SQL（<b>只支持单条 SELECT</b>）
        <textarea v-model="sql" spellcheck="false"></textarea>
      </label>
      <p class="muted">
        导出与查询走同一套 SQL 校验，但比查询更严：只允许 SELECT。
        一条 UPDATE 虽然"执行成功"，却没有可以写进 CSV 的结果集。
      </p>
      <button :disabled="creating" @click="handleCreate">
        {{ creating ? '创建中…' : '创建导出任务' }}
      </button>
      <p v-if="error" class="error">{{ error }}</p>
      <p v-if="message" class="muted">{{ message }}</p>
    </div>

    <div class="card">
      <h2>
        我的导出任务
        <span v-if="hasRunningTask" class="muted">· 处理中，每秒刷新</span>
      </h2>
      <table>
        <thead>
          <tr>
            <th>ID</th><th>状态</th><th>行数</th><th>创建时间</th><th>完成时间</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in tasks" :key="task.id">
            <td>{{ task.id }}</td>
            <td>{{ statusText(task.status) }}</td>
            <td>{{ task.rowCount === null ? '-' : task.rowCount }}</td>
            <td>{{ task.createdAt }}</td>
            <td>{{ task.finishedAt || '-' }}</td>
            <td>
              <!-- 只按 downloadable 判断，不自己拼 status === 'SUCCESS'：
                   文件可能已经被保留期清理任务删掉，那种情况下 status 仍是 SUCCESS
                   但 downloadable 已经是 false，点下载只会报错 -->
              <button v-if="task.downloadable" class="ghost"
                      :disabled="downloadingId === task.id"
                      @click="handleDownload(task)">
                {{ downloadingId === task.id ? '下载中…' : '下载 CSV' }}
              </button>
              <span v-else-if="task.status === 'SUCCESS'" class="muted">文件已过期清理</span>
              <span v-else-if="task.status === 'FAILED'" class="error">{{ task.errorMessage }}</span>
              <span v-else class="muted">等待中</span>
            </td>
          </tr>
          <tr v-if="!tasks.length">
            <td colspan="6" class="muted">还没有导出任务</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
