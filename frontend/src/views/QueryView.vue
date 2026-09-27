<script setup>
import { onMounted, ref } from 'vue'
import { dataSourceApi, queryApi } from '../api'

const sources = ref([])
const dataSourceId = ref(null)
const sql = ref('select 1')
const result = ref(null)
const error = ref('')
const loading = ref(false)

async function loadSources() {
  try {
    sources.value = await dataSourceApi.list()
    // 默认选中第一个启用的数据源
    const firstEnabled = sources.value.find((s) => s.status === 1)
    if (firstEnabled) dataSourceId.value = firstEnabled.id
  } catch (e) {
    error.value = e.message
  }
}

async function handleExecute() {
  error.value = ''
  result.value = null

  if (!dataSourceId.value) {
    error.value = '请先选择数据源'
    return
  }

  loading.value = true
  try {
    result.value = await queryApi.execute({
      dataSourceId: dataSourceId.value,
      sql: sql.value
    })
  } catch (e) {
    // 服务端可能返回：SQL 语法错误 / 没有权限执行该类型SQL / 操作太频繁 / SQL执行失败
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(loadSources)
</script>

<template>
  <div class="page">
    <div class="card">
      <h2>SQL 查询工作台</h2>
      <label>
        数据源
        <select v-model.number="dataSourceId">
          <option v-for="s in sources" :key="s.id" :value="s.id">
            {{ s.name }}（{{ s.host }}:{{ s.port }}）
          </option>
        </select>
      </label>

      <label>
        SQL（仅支持单条 SELECT，服务端会做白名单校验）
        <textarea v-model="sql" spellcheck="false"></textarea>
      </label>

      <button :disabled="loading" @click="handleExecute">
        {{ loading ? '执行中…' : '执行' }}
      </button>

      <p v-if="error" class="error">{{ error }}</p>
    </div>

    <div v-if="result" class="card">
      <h2>
        结果（{{ result.rows.length }} 行 · {{ result.costMs }} ms）
        <span v-if="result.truncated" class="error">· 已达返回上限，结果被截断</span>
      </h2>
      <table>
        <thead>
          <tr><th v-for="c in result.columns" :key="c">{{ c }}</th></tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in result.rows" :key="i">
            <td v-for="c in result.columns" :key="c">
              {{ row[c] === null ? 'NULL' : row[c] }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
