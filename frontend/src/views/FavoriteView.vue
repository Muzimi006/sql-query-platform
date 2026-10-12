<script setup>
/* 收藏的 SQL。
 *
 * 这个接口不分页（favoriteApi.list() 一次给全量），所以这里是"卡片墙 + 本地搜索"，
 * 而不是表格 + 分页：收藏通常几十条，一次拉完在前端过滤，比来回翻页快得多，
 * 也不用为了搜一个备注名往后端打一次请求。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { dataSourceApi, favoriteApi } from '../api'
import { fmtRelative, fmtTime, fmtTimeShort, typeTone } from '../format'
import { confirmDialog, copyText, toastError, toastOk, toastWarn } from '../ui'
import AppEmpty from '../components/AppEmpty.vue'
import AppIcon from '../components/AppIcon.vue'

const router = useRouter()

const list = ref([])
const loading = ref(false)
const loadError = ref('')
const removingId = ref(null)
const keyword = ref('')

// id → 名称。拿不到就退化成显示编号（见 sourceLabel）
const nameMap = ref({})

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.value
  return list.value.filter((f) =>
    String(f.remark || '').toLowerCase().includes(kw) ||
    String(f.sqlText || '').toLowerCase().includes(kw)
  )
})

/** 数据源名：查不到映射就退回编号。
 *  不能返回空串 —— 卡片上那块空着，用户会以为"这条收藏没有关联数据源"。 */
function sourceLabel(id) {
  if (id === null || id === undefined) return '未关联数据源'
  return nameMap.value[id] || ('数据源 #' + id)
}

/** 语句类型 = SQL 的第一个单词。 */
function stmtType(sql) {
  const m = String(sql || '').trim().match(/^[A-Za-z]+/)
  return m ? m[0].toUpperCase() : ''
}

/** 写操作给醒目色（typeTone 的配色字典在 format.js 里）。
 *  卡片上直接标出 UPDATE / DELETE，比点进去再发现"这条会改数据"要好。 */
function toneClass(sql) {
  const tone = typeTone(stmtType(sql))
  return tone ? 'tag-' + tone : ''
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    list.value = (await favoriteApi.list()) || []
  } catch (e) {
    loadError.value = (e && e.message) || '收藏加载失败'
    toastError(e, '收藏加载失败')
    // 出错清空：留着上一次的结果，用户会以为那就是最新的
    list.value = []
  } finally {
    loading.value = false
  }
}

async function loadSources() {
  try {
    const sources = (await dataSourceApi.list()) || []
    const m = {}
    sources.forEach((s) => { m[s.id] = s.name })
    nameMap.value = m
  } catch (e) {
    // 数据源拉不到不该让整页失败：卡片上显示编号，去执行/复制/删除都还能用
    toastWarn('数据源名称加载失败，卡片上显示的是编号')
  }
}

/* 跨页面带参：查询页挂载时读 route.query.sql 回填表单，再 router.replace 把参数清掉。
 * dataSourceId 为空时干脆不带这个参数 —— 带上会被序列化成空串传过去，
 * 查询页还得再判一次空，不如在源头就不产生这种参数。 */
function goQuery(item) {
  const query = { sql: item.sqlText }
  if (item.dataSourceId !== null && item.dataSourceId !== undefined) {
    query.dataSourceId = item.dataSourceId
  }
  router.push({ path: '/query', query })
}

async function handleCopy(item) {
  if (!item.sqlText) {
    // copyText 对空串也会提示"已复制"，那是在骗人，这里先挡掉
    toastWarn('这条收藏没有 SQL 内容')
    return
  }
  await copyText(item.sqlText)
}

async function handleRemove(item) {
  const ok = await confirmDialog({
    title: '删除收藏',
    message: '确定删除收藏「' + (item.remark || '未命名收藏') + '」吗？删除后无法恢复。',
    okText: '删除',
    danger: true
  })
  if (!ok) return

  removingId.value = item.id
  try {
    await favoriteApi.remove(item.id)
    // 后端已经删掉了，本地直接剔除：重新拉一次全量列表只是多一次往返，卡片还会整体闪一下
    const i = list.value.findIndex((x) => x.id === item.id)
    if (i >= 0) list.value.splice(i, 1)
    toastOk('已删除')
  } catch (e) {
    toastError(e, '删除失败')
  } finally {
    removingId.value = null
  }
}

// 两个请求互不依赖，并排发；各自失败各自提示，谁也别拖垮谁
onMounted(() => {
  load()
  loadSources()
})
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        <h2>收藏的 SQL</h2>
        <span class="hint">
          共 {{ list.length }} 条<template v-if="keyword.trim()"> · 匹配 {{ filtered.length }} 条</template>
        </span>
        <span v-if="loading && list.length" class="loading-line tiny" style="padding:0">
          <span class="spinner"></span>加载中…
        </span>
        <span class="spacer"></span>
        <button class="btn btn-sm" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="14" />刷新
        </button>
        <button class="btn btn-sm btn-primary" @click="router.push('/query')">
          <AppIcon name="query" :size="14" />去查询工作台
        </button>
      </div>

      <div class="card-body tight">
        <div class="row wrap">
          <div class="field mb-0" style="min-width: 280px">
            <label>搜索</label>
            <input
              class="input"
              v-model="keyword"
              placeholder="按备注名或 SQL 里的关键字过滤"
            />
          </div>
          <span class="tiny muted-2">本地过滤，不发请求</span>
          <span class="spacer"></span>
          <button v-if="keyword" class="btn btn-sm btn-ghost" @click="keyword = ''">
            <AppIcon name="close" :size="14" />清空搜索
          </button>
        </div>
      </div>
    </div>

    <div v-if="loadError" class="alert alert-danger">
      <AppIcon name="warn" :size="16" />
      <div class="break">{{ loadError }}</div>
      <span class="spacer"></span>
      <button class="btn btn-sm" :disabled="loading" @click="load">重试</button>
    </div>

    <div v-if="loading && !list.length" class="loading-line">
      <span class="spinner"></span>正在加载收藏…
    </div>

    <AppEmpty
      v-else-if="!list.length"
      icon="star"
      title="还没有收藏任何 SQL"
      desc="在查询工作台或查询历史里点「收藏」，常用的语句就不用每次重写"
    >
      <button class="btn btn-sm btn-primary" @click="router.push('/query')">
        <AppIcon name="star" :size="14" />去查询页收藏一条
      </button>
    </AppEmpty>

    <AppEmpty
      v-else-if="!filtered.length"
      icon="search"
      title="没有匹配的收藏"
      :desc="'没有备注名或 SQL 含「' + keyword.trim() + '」的收藏'"
    >
      <button class="btn btn-sm" @click="keyword = ''">
        <AppIcon name="close" :size="14" />清空搜索
      </button>
    </AppEmpty>

    <div v-else class="grid-2">
      <div v-for="item in filtered" :key="item.id" class="card mb-0">
        <div class="card-head">
          <AppIcon name="star" :size="16" />
          <h2 class="break" :title="item.remark || '未命名收藏'">{{ item.remark || '未命名收藏' }}</h2>
          <span class="spacer"></span>
          <span class="tiny muted-2 nowrap" :title="fmtTime(item.createdAt)">{{ fmtRelative(item.createdAt) }}</span>
        </div>

        <div class="card-body tight">
          <!-- code 自带 max-height:420px，卡片墙里太高会把别的卡片挤下去，这里收到 160px -->
          <div class="code" :style="{ maxHeight: '160px' }">{{ item.sqlText }}</div>
          <div class="row wrap mt-1 tiny muted-2">
            <AppIcon name="database" :size="12" />
            <span class="nowrap">{{ sourceLabel(item.dataSourceId) }}</span>
            <span v-if="stmtType(item.sqlText)" class="tag" :class="toneClass(item.sqlText)">
              {{ stmtType(item.sqlText) }}
            </span>
            <span class="spacer"></span>
            <span class="nowrap">创建于 {{ fmtTimeShort(item.createdAt) }}</span>
          </div>
        </div>

        <div class="card-foot">
          <button class="btn btn-sm btn-primary" title="把这条 SQL 带回查询页执行" @click="goQuery(item)">
            <AppIcon name="play" :size="14" />去执行
          </button>
          <button class="btn btn-sm" title="复制完整 SQL" @click="handleCopy(item)">
            <AppIcon name="copy" :size="14" />复制
          </button>
          <span class="spacer"></span>
          <button
            class="btn btn-sm btn-ghost"
            :disabled="removingId === item.id"
            @click="handleRemove(item)"
          >
            <AppIcon name="trash" :size="14" />{{ removingId === item.id ? '删除中…' : '删除' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
