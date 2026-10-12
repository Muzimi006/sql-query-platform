<script setup>
/* 查询历史页。
 *
 * 这一页的价值不在"看"，而在"找回"：跑失败的那条、上周试过的写法，
 * 都要能一眼定位并一键带回查询页重跑 —— 所以每一行都给三个出口：再次执行 / 收藏 / 删除。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { dataSourceApi, favoriteApi, historyApi } from '../api'
import { STATUS, fmtCost, fmtRelative, fmtTime, sqlSummary } from '../format'
import { confirmDialog, toastError, toastOk, toastWarn } from '../ui'
import AppEmpty from '../components/AppEmpty.vue'
import AppIcon from '../components/AppIcon.vue'
import AppPager from '../components/AppPager.vue'
import StatusTag from '../components/StatusTag.vue'

const router = useRouter()

const rows = ref([])
const total = ref(0)
const current = ref(1)
const size = ref(10)
const loading = ref(false)
const loadError = ref('')
const removingId = ref(null)
const clearing = ref(false)

const filters = reactive({ status: '', dataSourceId: '' })

/* 状态下拉的选项直接来自 format.js 的字典。
 * 页面上再手抄一份 SUCCESS / FAILED 的中文，早晚会出现"下拉写成功、标签写执行成功"这种不一致。 */
const STATUS_OPTIONS = Object.entries(STATUS.history).map(([value, meta]) => ({ value, label: meta.label }))

const sources = ref([])
const sourceNames = computed(() => {
  const m = {}
  sources.value.forEach((s) => { m[s.id] = s.name })
  return m
})

const hasFilter = computed(() => filters.status !== '' || filters.dataSourceId !== '')

/** 数据源名：查不到映射就退回编号。
 *  这里不能返回空串 —— 表格里那格空着，用户会以为"这条历史没有关联数据源"。 */
function sourceLabel(id) {
  if (id === null || id === undefined) return '—'
  return sourceNames.value[id] || ('数据源 #' + id)
}

/** 错误信息可能是一整段堆栈，表格里只留一眼能看懂的开头，完整内容挂 title 上。 */
function clip(text, max = 46) {
  const one = String(text || '').replace(/\s+/g, ' ').trim()
  return one.length > max ? one.slice(0, max) + '…' : one
}

async function loadSources() {
  try {
    sources.value = await dataSourceApi.list()
  } catch (e) {
    // 数据源拉不到不该让整页失败：筛选框退化成只有"全部"，表格里显示编号，功能还能用
    toastWarn('数据源列表加载失败，表格里显示的是编号')
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const page = await historyApi.page({
      page: current.value,
      size: size.value,
      // 没选的条件不传：后端按「参数缺省 = 不过滤」处理；
      // 反过来传空串，dataSourceId 还会因为空串转不成数字而直接报参数非法
      status: filters.status || undefined,
      dataSourceId: filters.dataSourceId === '' ? undefined : filters.dataSourceId
    })
    // IPage 结构：{ records, total, size, current, pages }
    rows.value = page?.records || []
    total.value = Number(page?.total || 0)
  } catch (e) {
    loadError.value = (e && e.message) || '查询历史加载失败'
    toastError(e, '查询历史加载失败')
    // 出错必须清空旧数据：留着上一次的结果，用户会以为"这就是最新的"
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/* 筛选条件 / 每页条数一变就回到第 1 页。
 * 这里刻意不直接调 load()：只要 current 不是 1，改它就会触发下面那个 watcher 去加载，
 * 两边都调就会连发两次请求 —— 后端有限流，双击式的重复请求会把用户挡下来。 */
watch([() => filters.status, () => filters.dataSourceId, size], () => {
  if (current.value !== 1) current.value = 1
  else load()
})
watch(current, load)

function resetFilters() {
  filters.status = ''
  filters.dataSourceId = ''
}

/** 跨页面带参：查询页挂载时读 route.query.sql 回填表单，然后 router.replace 清掉参数。
 *  必须用 query 而不是共享状态 —— 刷新页面后 query 还在，"上次没跑完的语句"不会丢。
 *  dataSourceId 为空时不带这个参数：带上会被序列化成空串传过去，查询页还得再判一次空。 */
function handleRerun(row) {
  const query = { sql: row.sqlText }
  if (row.dataSourceId !== null && row.dataSourceId !== undefined) {
    query.dataSourceId = row.dataSourceId
  }
  router.push({ path: '/query', query })
}

/* ---------- 收藏 ---------- */
const favOpen = ref(false)
const favRow = ref(null)
const favRemark = ref('')
const favSaving = ref(false)

function openFavorite(row) {
  favRow.value = row
  favRemark.value = '' // 每次重开都清空：留着上一条的备注，下一条会被莫名其妙地覆盖
  favOpen.value = true
}

function closeFavorite() {
  if (favSaving.value) return // 提交中不许关：关了以后请求还在飞，用户不知道到底存上没有
  favOpen.value = false
  favRow.value = null
}

async function submitFavorite() {
  if (favSaving.value) return
  const remark = favRemark.value.trim()
  if (!remark) {
    toastWarn('先给这条 SQL 起个备注名，不然收藏列表里认不出它')
    return
  }
  favSaving.value = true
  try {
    await favoriteApi.add({
      dataSourceId: favRow.value.dataSourceId,
      sqlText: favRow.value.sqlText,
      remark
    })
    toastOk('已收藏：' + remark)
    favOpen.value = false
    favRow.value = null
  } catch (e) {
    toastError(e, '收藏失败')
  } finally {
    favSaving.value = false
  }
}

/* ---------- 删除 / 清空 ---------- */
async function handleRemove(row) {
  const ok = await confirmDialog({
    title: '删除这条历史',
    message: '确定删除这条查询历史吗？' + sqlSummary(row.sqlText, 60),
    okText: '删除',
    danger: true
  })
  if (!ok) return

  removingId.value = row.id
  try {
    await historyApi.remove(row.id)
    toastOk('已删除')
    // 删掉的是本页最后一条时，如果还停在原页码就会看到一张空表，
    // 用户会以为"历史被删光了" —— 退一页才是他预期的结果
    if (rows.value.length === 1 && current.value > 1) {
      current.value -= 1 // 交给 current 的 watcher 去加载
    } else {
      await load()
    }
  } catch (e) {
    toastError(e, '删除失败')
  } finally {
    removingId.value = null
  }
}

async function handleClear() {
  const ok = await confirmDialog({
    title: '清空全部历史',
    message: '将删除你的全部查询历史，删除后无法恢复。确定继续吗？',
    okText: '全部清空',
    danger: true
  })
  if (!ok) return

  clearing.value = true
  try {
    await historyApi.clear()
    toastOk('历史已清空')
    if (current.value !== 1) current.value = 1 // 交给 current 的 watcher 去加载
    else await load()
  } catch (e) {
    toastError(e, '清空失败')
  } finally {
    clearing.value = false
  }
}

// 两个请求互不依赖，并排发；任一失败都在各自的 catch 里出 toast
onMounted(() => {
  loadSources()
  load()
})
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        <h2>查询历史</h2>
        <span class="hint">每次执行都留痕：成败、耗时、失败原因</span>
        <span v-if="loading && rows.length" class="loading-line tiny pad-0">
          <span class="spinner"></span>加载中…
        </span>
        <span class="spacer"></span>
        <button class="btn btn-sm" :disabled="loading" @click="load" title="重新拉取当前条件的第一页">
          <AppIcon name="refresh" :size="14" />刷新
        </button>
        <button
          class="btn btn-sm btn-danger"
          :disabled="clearing || loading || (total === 0 && !hasFilter)"
          :title="total === 0 && !hasFilter ? '没有历史可清空' : '删除你的全部查询历史（不受筛选影响）'"
          @click="handleClear"
        >
          <AppIcon name="trash" :size="14" />{{ clearing ? '清空中…' : '清空全部历史' }}
        </button>
      </div>

      <div class="card-body tight">
        <div class="filter-bar">
          <div class="field">
            <label>状态</label>
            <select class="select" v-model="filters.status" :disabled="loading">
              <option value="">全部</option>
              <option v-for="o in STATUS_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
            </select>
          </div>
          <div class="field wide">
            <label>数据源</label>
            <select class="select" v-model="filters.dataSourceId" :disabled="loading || !sources.length">
              <option value="">全部</option>
              <option v-for="s in sources" :key="s.id" :value="s.id">{{ s.name }}</option>
            </select>
          </div>
          <div class="spacer"></div>
          <div class="field narrow">
            <label>每页条数</label>
            <select class="select" v-model.number="size" :disabled="loading">
              <option :value="10">10 条</option>
              <option :value="20">20 条</option>
              <option :value="50">50 条</option>
            </select>
          </div>
        </div>
      </div>

      <div v-if="loadError" class="card-body tight">
        <div class="alert alert-danger mb-0">
          <AppIcon name="warn" :size="16" />
          <div class="break">{{ loadError }}</div>
          <span class="spacer"></span>
          <button class="btn btn-sm" :disabled="loading" @click="load">重试</button>
        </div>
      </div>

      <div v-if="loading && !rows.length" class="loading-line">
        <span class="spinner"></span>正在加载查询历史…
      </div>

      <AppEmpty
        v-else-if="!rows.length"
        icon="history"
        :title="hasFilter ? '没有符合筛选条件的历史' : '还没有查询历史'"
        :desc="hasFilter ? '换个条件再试试，或者清空筛选看全部' : '去查询工作台执行一条 SQL，这里会自动留痕'"
      >
        <button v-if="hasFilter" class="btn btn-sm" @click="resetFilters">
          <AppIcon name="refresh" :size="14" />清空筛选
        </button>
        <button v-else class="btn btn-sm btn-primary" @click="router.push('/query')">
          <AppIcon name="query" :size="14" />去查询工作台
        </button>
      </AppEmpty>

      <template v-else>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>时间</th>
                <th>数据源</th>
                <th>SQL 摘要</th>
                <th>状态</th>
                <th class="num">耗时</th>
                <th>错误信息</th>
                <th class="num">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <!-- 相对时间给"多久以前"的直觉，绝对时间放 title：只显示其一都有人不够用 -->
                <td class="nowrap"><span :title="fmtTime(row.createdAt)">{{ fmtRelative(row.createdAt) }}</span></td>
                <td class="nowrap">{{ sourceLabel(row.dataSourceId) }}</td>
                <td class="break">
                  <span :title="row.sqlText">{{ sqlSummary(row.sqlText, 64) }}</span>
                </td>
                <td><StatusTag :value="row.status" kind="history" /></td>
                <td class="num nowrap">{{ fmtCost(row.costMs) }}</td>
                <td class="break">
                  <span
                    v-if="row.errorMessage"
                    class="tiny text-danger"
                    :title="row.errorMessage"
                  >{{ clip(row.errorMessage) }}</span>
                  <span v-else class="muted-2">—</span>
                </td>
                <td class="actions">
                  <button class="btn btn-sm" title="把这条 SQL 带回查询页重新执行" @click="handleRerun(row)">
                    <AppIcon name="play" :size="14" />再次执行
                  </button>
                  <button class="btn btn-sm" title="存成常用 SQL" @click="openFavorite(row)">
                    <AppIcon name="star" :size="14" />收藏
                  </button>
                  <button
                    class="btn btn-sm btn-ghost"
                    :disabled="removingId === row.id"
                    @click="handleRemove(row)"
                  >
                    <AppIcon name="trash" :size="14" />{{ removingId === row.id ? '删除中…' : '删除' }}
                  </button>
                </td>
              </tr>
            </tbody>
            <caption>只展示你自己的查询记录；表格里是摘要，完整 SQL 在服务端原文保存</caption>
          </table>
        </div>

        <AppPager v-model:current="current" :total="total" :size="size" :disabled="loading" />
      </template>
    </div>

    <!-- 收藏弹窗：用全局那套 mask + modal 类，不自己造弹窗样式（App.vue 只管 toast 和确认框） -->
    <Teleport to="body">
      <div v-if="favOpen" class="mask center" @click.self="closeFavorite">
        <div class="modal">
          <div class="modal-head">
            <AppIcon name="star" :size="17" />
            <h2>收藏这条 SQL</h2>
            <span class="spacer"></span>
            <button class="icon-btn" :disabled="favSaving" title="关闭" @click="closeFavorite">
              <AppIcon name="close" :size="16" />
            </button>
          </div>

          <div class="modal-body">
            <div class="field">
              <label>备注名<span class="req">*</span></label>
              <input
                class="input"
                v-model="favRemark"
                maxlength="50"
                placeholder="例如：每日活跃用户数"
                @keyup.enter="submitFavorite"
              />
              <div class="tip">
                备注是收藏列表里唯一的辨识依据。后端不去重，同一条 SQL 想存两份就写两个不同的备注。
              </div>
            </div>

            <div class="field mb-0">
              <label>数据源</label>
              <div class="small">{{ sourceLabel(favRow && favRow.dataSourceId) }}</div>
            </div>

            <div class="field mb-0 mt-2">
              <label>SQL（按原文保存，这里不能改）</label>
              <div class="code code-sm">{{ favRow && favRow.sqlText }}</div>
            </div>
          </div>

          <div class="modal-foot">
            <button class="btn" :disabled="favSaving" @click="closeFavorite">取消</button>
            <button
              class="btn btn-primary"
              :disabled="favSaving || !favRemark.trim()"
              @click="submitFavorite"
            >
              {{ favSaving ? '收藏中…' : '确认收藏' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
