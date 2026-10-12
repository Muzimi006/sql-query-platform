<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { dataSourceApi, sqlReviewApi } from '../api'
import { getUser } from '../auth'
import {
  fmtNumber, fmtRelative, fmtTime, isReviewRunning, newRequestToken, sqlSummary, typeTone
} from '../format'
import { confirmDialog, copyText, toastErr, toastError, toastOk, toastWarn } from '../ui'
import AppDrawer from '../components/AppDrawer.vue'
import AppEmpty from '../components/AppEmpty.vue'
import AppIcon from '../components/AppIcon.vue'
import AppPager from '../components/AppPager.vue'
import StatusTag from '../components/StatusTag.vue'

/* ============================================================================
 * 审核工单中心 —— 这个项目的业务核心页。
 *
 * 它把"没有写权限的人想改数据"这件事走完：提交 → 审批 → 异步执行 → 留痕。
 * 所以这一页有三条不能省的东西，缺一条流程就是假的：
 *   ① 状态必须**轮询**：批准接口返回 200 不代表执行完了（审批 → 投 MQ →
 *      消费者认领 → 执行 → 回写），停在 APPROVED 不动的界面等于骗人。
 *   ② 影响面必须**分开写**：预估扫描行数 ≠ 最终改动行数（见 SqlImpactAnalyzer），
 *      混在一起会让审批人拿一个错数字做决定。
 *   ③ 回滚依据必须**露出来**：前镜像留没留下来、反向 SQL 长什么样，
 *      出事的时候才知道有没有退路 —— 这也是这一页存在的意义。
 * ========================================================================== */

const route = useRoute()
const router = useRouter()

const SIZE = 10

/**
 * 角色开关。
 *
 * 审批侧接口（pending / approve / reject）后端只放给 ADMIN，
 * 前端据此决定第二个页签和审批按钮的可用性 —— 但这只是"体验层"，
 * 真正的判定在服务层（SqlReviewServiceImpl.requireAdmin），绕过去也拿不到数据。
 *
 * ⚠️ getUser() 读的是 localStorage，不是响应式的；这里不会有问题：
 *    角色在一次会话里不会变，换账号必然伴随重新登录 + 页面刷新。
 */
const isAdmin = computed(() => (getUser()?.role || '') === 'ADMIN')

/* ---------------------------------------------------------------------------
 * 列表：两个页签各自维护页码和加载态
 *
 * 为什么是两个对象而不是"一个列表 + 切页签时重查"：
 * 两个页签的排序和权限都不一样（mine 按 id 倒序、pending 按 id 升序且仅 ADMIN），
 * 各留各的页码，来回切的时候不会把对方的分页位置冲掉。
 * ------------------------------------------------------------------------- */
const tab = ref('mine')
const mine = reactive({ records: [], total: 0, current: 1, loading: false })
const pending = reactive({ records: [], total: 0, current: 1, loading: false })
const listError = ref('')

const active = computed(() => (tab.value === 'mine' ? mine : pending))
const rows = computed(() => active.value.records)
const loadingList = computed(() => active.value.loading)
/** 快速批准只针对某一行，用它做按钮的提交中禁用 —— 不要用列表级 loading，那会把整张表变灰 */
const approvingId = ref(null)

/* 顶部统计。null → fmtNumber 显示「—」，正好表达"还没拿到"，比先显示 0 诚实 */
const statsMine = ref(null)
const statsPending = ref(null)

/* ---------------------------------------------------------------------------
 * 提交工单的弹窗
 * ------------------------------------------------------------------------- */
const createOpen = ref(false)
const sources = ref([])
const submitting = ref(false)
const form = reactive({
  dataSourceId: null,
  sql: '',
  // 申请理由复用 DTO 的 generationPrompt 字段：它是提交载荷里唯一的自由文本，
  // 也是审批人判断"意图对不对"的依据（AI 入口会把用户原话自动填进同一个字段，
  // 所以同一条工单在两种入口下的留痕是一致的）
  generationPrompt: '',
  requestToken: null
})
/**
 * 幂等键的"上次实际用的载荷"。
 *
 * 坑在这里：token 代表的是**同一次提交意图**。提交失败后原样重试必须复用同一个
 * token（否则会多出一条重复工单）；但用户改了 SQL 再提交就是**另一次意图**，
 * 复用旧 token 会被后端按"幂等键命中但内容不同"直接拒绝
 *（见 SqlReviewServiceImpl.doSubmit，那里是刻意这么写的）。
 * 所以约定：空串 = 这个 token 还没用过 → 直接用；非空且载荷变了 → 换一个新 token。
 */
let tokenPayload = ''

/* ---------------------------------------------------------------------------
 * 详情抽屉
 * ------------------------------------------------------------------------- */
const drawerOpen = ref(false)
const detail = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
const comment = ref('')
const approving = ref(false)
const rejecting = ref(false)

const busyDecision = computed(() => approving.value || rejecting.value)
/** 只有 PENDING 能被批准/驳回 —— 后端也一样（UPDATE 的 WHERE 里带着 status） */
const canDecide = computed(() => isAdmin.value && detail.value?.status === 'PENDING')

/* ---------------------------------------------------------------------------
 * 轮询
 *
 * ⚠️ 定时器必须存成组件级变量并在卸载时 clearInterval：
 *    不存的话组件卸载后它还在跑，页面来回切几次就会攒出好几个定时器
 *    （表现是接口调用量成倍增长，而且旧定时器会往已销毁的详情里写数据）。
 * ------------------------------------------------------------------------- */
const POLL_MS = 3000
/** 上限 40 次 ≈ 2 分钟。卡住的工单可能几十分钟不动，无限轮询等于让标签页永远在打接口 */
const POLL_MAX = 40
let pollTimer = null
let pollTries = 0
/** 上一次轮询请求还没回来时跳过这一拍，避免后端慢的时候请求叠在一起 */
let pollBusy = false

function stopDetailPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  pollBusy = false
}

function startDetailPolling() {
  if (pollTimer !== null) return
  pollTries = 0
  pollTimer = setInterval(async () => {
    if (!drawerOpen.value) { stopDetailPolling(); return }
    if (pollBusy) return
    if (pollTries >= POLL_MAX) {
      stopDetailPolling()
      toastWarn('工单还在执行中，已停止自动刷新；可稍后重新打开这条工单查看')
      return
    }
    pollTries += 1
    pollBusy = true
    try {
      await loadDetail(route.params.id || detail.value?.id, { silent: true })
    } finally {
      pollBusy = false
    }
  }, POLL_MS)
}

/* ---------------------------------------------------------------------------
 * 数据加载
 * ------------------------------------------------------------------------- */

/** 数据源 id → 名字。列表行里只有 dataSourceId，没有名字（VO 里就没有这个字段） */
const sourceNameById = computed(() => {
  const m = new Map()
  for (const s of sources.value) m.set(s.id, s.name)
  return m
})

/**
 * 名字查不到时退回 #id，而不是写"未知"：
 * 待审批队列里有别人提交的工单，它可能挂在一个**不归我**的数据源上
 *（/datasource 只返回自己的），显示 #id 至少能让审批人自己去核对是哪一个。
 */
function sourceName(id) {
  if (!id) return '—'
  return sourceNameById.value.get(id) || ('#' + id)
}

/** 语句类型 → 标签色（写操作给醒目色，审批人一眼看出"这条会改数据"） */
function typeTagClass(type) {
  const tone = typeTone(type)
  return tone ? 'tag-' + tone : ''
}

async function loadSources() {
  try {
    sources.value = await dataSourceApi.list()
    if (!form.dataSourceId) {
      // 默认选中第一个启用的：停用的数据源后端会拒绝提交
      //（getEnabledByIdAndUserId 会先拦住）
      const firstEnabled = sources.value.find((s) => s.status === 1)
      if (firstEnabled) form.dataSourceId = firstEnabled.id
    }
  } catch (e) {
    // 数据源拉不到不阻断整页（工单列表还能看），但必须说清楚 ——
    // 否则用户点开弹窗看到一个空下拉框，会以为是页面坏了
    toastError(e, '数据源加载失败')
  }
}

/**
 * 顶部统计。
 *
 * 用 mine(1,1) / pending(1,1) 而不是拿当前列表的 total：
 * size=1 只回一行，但 total 是全量的准确值；而且统计块不该跟着页码走 ——
 * 翻到第 3 页时「我的工单数」不应该变。
 */
async function loadStats() {
  try {
    const [minePage, pendingPage] = await Promise.all([
      sqlReviewApi.mine(1, 1),
      // 非 ADMIN 调 pending 会直接失败（后端按角色收口），所以干脆不发这个请求
      isAdmin.value ? sqlReviewApi.pending(1, 1) : Promise.resolve(null)
    ])
    statsMine.value = minePage?.total ?? 0
    statsPending.value = pendingPage ? (pendingPage.total ?? 0) : 0
  } catch (e) {
    // 统计是锦上添花的提示，拉不到就保持上一次的值、也不弹错：
    // 为了两个数字在页面顶部刷一条报错，打扰大于价值（侧边栏角标同理，见 App.vue）
  }
}

async function loadList(which, current) {
  const box = which === 'mine' ? mine : pending
  if (current !== undefined) box.current = current
  box.loading = true
  listError.value = ''
  try {
    const page = which === 'mine'
      ? await sqlReviewApi.mine(box.current, SIZE)
      : await sqlReviewApi.pending(box.current, SIZE)
    box.records = page?.records || []
    box.total = page?.total || 0
    // 删到最后一页空了就自动回退一页，否则用户会停在"看起来没有数据"的空页上
    if (!box.records.length && box.current > 1) {
      box.current -= 1
      await loadList(which)
    }
  } catch (e) {
    box.records = []
    box.total = 0
    listError.value = e.message || '加载失败'
    toastError(e, '加载工单列表失败')
  } finally {
    box.loading = false
  }
}

function loadActive(current) {
  return loadList(tab.value, current)
}

/** 提交 / 批准 / 驳回之后都要调它：列表和统计是两处独立的数据，只刷一个必然对不上 */
async function refreshAll() {
  await Promise.all([loadStats(), loadActive()])
}

/* ---------------------------------------------------------------------------
 * 页签与分页
 * ------------------------------------------------------------------------- */
function switchTab(next) {
  if (next === tab.value) return
  if (next === 'pending' && !isAdmin.value) return
  tab.value = next
  listError.value = ''
  // 审批队列是"活"的，每次切过去都重拉一遍；不做本地缓存，
  // 否则管理员会对着几分钟前的队列做审批
  loadList(next)
}

function onPageChange(p) {
  if (p === active.value.current) return
  loadList(tab.value, p)
}

/* ---------------------------------------------------------------------------
 * 提交工单
 * ------------------------------------------------------------------------- */
function openCreate() {
  // ⚠️ 幂等键在"弹窗打开"这一刻生成：关掉再开就是一次新的提交意图。
  //    放在提交按钮里生成的话，双击会生成两个 token —— 幂等就失效了。
  form.requestToken = newRequestToken()
  tokenPayload = ''
  createOpen.value = true
  if (!sources.value.length) loadSources()
}

function closeCreate() {
  // 提交中不允许关：请求已经发出去了，关掉弹窗只会让用户以为没提交成功
  if (submitting.value) return
  createOpen.value = false
  // 刻意**不**清空已经写好的 SQL：误点一下遮罩就把刚贴的语句弄丢，比什么都让人恼火
}

function resetForm() {
  form.sql = ''
  form.generationPrompt = ''
  const firstEnabled = sources.value.find((s) => s.status === 1)
  form.dataSourceId = firstEnabled ? firstEnabled.id : null
}

async function handleSubmit() {
  // 前端先校验一遍：后端也会校验，但如果等一个来回再报错，用户已经以为提交成功了
  if (!form.dataSourceId) { toastErr('请先选择数据源'); return }
  const sql = form.sql.trim()
  if (!sql) { toastErr('SQL 不能为空'); return }
  // 与后端 @Size 对齐：列类型是 TEXT，超长时 MySQL 抛的异常会掉进全局兜底变成
  // "系统异常"，用户完全不知道是自己贴太长了（见 SqlReviewSubmitDTO 的注释）
  if (sql.length > 20000) { toastErr('SQL 不能超过 20000 个字符，当前 ' + sql.length + ' 个'); return }
  const reason = form.generationPrompt.trim()
  if (reason.length > 1000) { toastErr('申请理由不能超过 1000 个字符，当前 ' + reason.length + ' 个'); return }

  // 载荷变了就换 token（只有同一意图的重试才沿用），理由见 tokenPayload 的注释
  const payload = form.dataSourceId + '\n' + sql
  if (!form.requestToken) form.requestToken = newRequestToken()
  else if (tokenPayload && payload !== tokenPayload) form.requestToken = newRequestToken()
  tokenPayload = payload

  submitting.value = true
  try {
    const created = await sqlReviewApi.submit({
      dataSourceId: form.dataSourceId,
      sql,
      generationPrompt: reason || null,
      requestToken: form.requestToken
    })
    toastOk('工单 #' + (created?.id ?? '') + ' 已提交，等待管理员审批')
    // 提交成功 → 这个 token 的使命结束（下次打开弹窗会换新的）
    form.requestToken = null
    tokenPayload = ''
    createOpen.value = false
    resetForm()
    tab.value = 'mine'
    await refreshAll()
    // 直接打开新工单的详情：提交完最想知道的就是"它现在什么状态"
    if (created?.id) openDetail(created.id)
  } catch (e) {
    // 失败时**保留** token —— 用户点重试还是同一次提交意图，不该产生第二条工单。
    // 限流命中（"提交太频繁"）也走这里：只提示，不做自动重试（见接口约定）
    toastError(e, '提交失败')
  } finally {
    submitting.value = false
  }
}

/* ---------------------------------------------------------------------------
 * 详情 + 审批
 * ------------------------------------------------------------------------- */
function openDetail(id) {
  if (String(route.params.id || '') === String(id)) {
    // 已经在这条工单的路由上（比如从 /review/5 直接进来），只确保数据是新的
    drawerOpen.value = true
    loadDetail(id)
    return
  }
  // 走路由而不是直接开抽屉：这样详情可分享、可收藏，浏览器返回键也能关掉它
  router.push({ name: 'review-detail', params: { id: String(id) } })
}

/**
 * 拉一次详情。
 *
 * @param silent 轮询调用时为 true：不显示 loading、失败也不弹 toast。
 *   轮询失败一次是常态（网络抖动、后端恰好在重启），每次都弹会把屏幕刷满，
 *   而下一拍成功了就自动恢复了；真正的终态错误（FAILED）靠 status 表达，不靠请求失败。
 */
async function loadDetail(id, { silent = false } = {}) {
  if (!id) return
  if (!silent) {
    detailLoading.value = true
    detailError.value = ''
  }
  // 记下刷新前的状态：用来判断这次是不是"从执行中落到了终态"
  const prevStatus = String(detail.value?.id) === String(id) ? detail.value.status : null
  try {
    const vo = await sqlReviewApi.detail(id)
    detail.value = vo
    detailError.value = ''
    syncPolling(vo?.status, prevStatus)
  } catch (e) {
    if (silent) return
    // 看别人的工单时后端回答的是"工单不存在"而不是"无权查看"（防 id 枚举），
    // 原样展示即可，不要自作主张改成"无权访问"
    detailError.value = e.message || '加载工单详情失败'
    toastError(e, '加载工单详情失败')
  } finally {
    if (!silent) detailLoading.value = false
  }
}

/** 按最新状态决定要不要继续轮询；从"执行中"落到终态时顺手把列表也刷掉 */
function syncPolling(status, prevStatus) {
  if (isReviewRunning(status)) {
    startDetailPolling()
    return
  }
  const wasRunning = isReviewRunning(prevStatus)
  stopDetailPolling()
  if (!wasRunning) return
  if (status === 'EXECUTED') toastOk('工单已执行完成')
  else if (status === 'FAILED') toastErr('工单执行失败，详情里有数据库返回的错误信息')
  // 列表里那一行的状态也变了；不刷的话用户回到列表看到的还是"执行中"，会以为卡住了
  refreshAll()
}

function closeDetail() {
  drawerOpen.value = false
}

async function handleApprove() {
  const t = detail.value
  if (!t) return
  if (!(await confirmDialog({
    title: '批准并执行',
    message: '批准后这条 SQL 会在后台异步执行，会真的改动数据：\n' + sqlSummary(t.sqlText, 120),
    okText: '批准',
    danger: true
  }))) return

  approving.value = true
  try {
    // 批准允许不带意见（后端 @RequestBody(required = false)），传空串最省事：
    // api.js 里已经做了 comment || null 的归一化，两种写法都安全
    await sqlReviewApi.approve(t.id, comment.value.trim() || '')
    toastOk('工单 #' + t.id + ' 已批准，正在后台执行')
    comment.value = ''
    await refreshAll()
    // 批准只让它进入 APPROVED，执行还在后台 —— 立刻重拉一次并开始轮询，
    // 否则界面会停在一个"点了没反应"的状态上。
    // silent：别让抽屉闪一下"加载中"，那会把用户刚看过的详情内容整个抹掉
    await loadDetail(t.id, { silent: true })
    if (isReviewRunning(detail.value?.status)) startDetailPolling()
  } catch (e) {
    // 常见失败：不是 ADMIN、工单已被别人处理过、不能审批自己提交的工单（四眼原则）
    toastError(e, '批准失败')
  } finally {
    approving.value = false
  }
}

async function handleReject() {
  const t = detail.value
  if (!t) return
  // 驳回意见必填：后端服务层也会拦，但前端先拦一道，
  // 免得用户填了一堆字、点了确认才发现原因没写
  const reason = comment.value.trim()
  if (!reason) { toastErr('驳回必须写明原因：申请人要靠它知道该改什么'); return }
  if (!(await confirmDialog({
    title: '驳回工单',
    message: '驳回后这条 SQL 不会被执行（不发消息、不动任何数据）。\n原因：' + reason,
    okText: '确认驳回',
    danger: true
  }))) return

  rejecting.value = true
  try {
    await sqlReviewApi.reject(t.id, reason)
    toastOk('工单 #' + t.id + ' 已驳回')
    comment.value = ''
    // 同上：状态就地更新，不闪加载态
    await loadDetail(t.id, { silent: true })
    await refreshAll()
  } catch (e) {
    toastError(e, '驳回失败')
  } finally {
    rejecting.value = false
  }
}

/** 列表里的快速批准：不带意见，要写意见就进抽屉 */
async function quickApprove(row) {
  if (!(await confirmDialog({
    title: '批准并执行',
    message: '批准后工单 #' + row.id + ' 会在后台异步执行，会真的改动数据：\n' + sqlSummary(row.sqlText, 120),
    okText: '批准',
    danger: true
  }))) return

  approvingId.value = row.id
  try {
    await sqlReviewApi.approve(row.id, '')
    toastOk('工单 #' + row.id + ' 已批准，正在后台执行')
    await refreshAll()
  } catch (e) {
    // 四眼原则那条错误（"不能审批自己提交的工单"）会在这里冒出来：
    // 待审批队列里确实可能出现管理员自己提交的工单
    toastError(e, '批准失败')
  } finally {
    approvingId.value = null
  }
}

/* ---------------------------------------------------------------------------
 * 路由同步
 *
 * 详情是可直连的（/review/:id）：地址里带 id 就直接打开对应抽屉。
 * 用 watch 而不是在 openDetail 里直接 loadDetail —— 因为 /review 和 /review/:id
 * 指向同一个组件，两个路由之间切换**不会重新挂载**，onMounted 只跑一次。
 * ------------------------------------------------------------------------- */
watch(() => route.params.id, (id) => {
  if (id) {
    drawerOpen.value = true
    comment.value = ''
    loadDetail(id)
  } else {
    // 从详情退回列表（浏览器返回键）：收起抽屉，顺手停掉轮询并丢掉旧详情
    drawerOpen.value = false
    stopDetailPolling()
    detail.value = null
  }
})

watch(drawerOpen, (open) => {
  if (open) return
  stopDetailPolling()
  // 点遮罩 / 按 Esc 关闭时地址还停在 /review/:id 上，刷新会把抽屉又弹出来 —— 收回去。
  // ⚠️ 必须判 route.name：用户点侧边栏去别的页面时这里也会变成 false，
  //    不判的话会把那次跳转劫持回 /review
  if (route.name === 'review-detail') router.replace({ name: 'review' })
})

onMounted(async () => {
  loadSources()
  // 统计和列表并行拉：两者互不依赖，串行只会让首屏多等一个来回
  await Promise.all([loadStats(), loadActive()])
  // 直连 /review/:id：挂载时就把那条工单的抽屉打开
  const id = route.params.id
  if (id) {
    drawerOpen.value = true
    loadDetail(id)
  }
})

// ⚠️ 卸载时必须停轮询。少了这一行，用户切走之后定时器还在打接口，
//    来回切几次就会同时存在好几个轮询（每个都在往已销毁的组件状态里写）
onUnmounted(stopDetailPolling)
</script>

<template>
  <div>
    <!-- 顶部统计：我的工单数 / 待我审批数（后者仅 ADMIN，非管理员连请求都不发） -->
    <div class="stats">
      <div class="stat">
        <div class="k"><AppIcon name="review" :size="14" />我的工单</div>
        <div class="v">{{ fmtNumber(statsMine) }}</div>
        <div class="s">累计提交，含已执行 / 已驳回</div>
      </div>
      <div class="stat" v-if="isAdmin">
        <div class="k"><AppIcon name="shield" :size="14" />待我审批</div>
        <div class="v">{{ fmtNumber(statsPending) }}</div>
        <div class="s">队列按提交先后处理（先提交先审）</div>
      </div>
      <div class="stat" v-else>
        <div class="k"><AppIcon name="lock" :size="14" />审批队列</div>
        <div class="v muted-2">仅管理员</div>
        <div class="s">你提交的工单由管理员审批</div>
      </div>
    </div>

    <!-- 待审批列表是别人提交的待执行 SQL，属于敏感内容：非 ADMIN 禁用页签并说明原因 -->
    <div class="tabs">
      <button class="tab" :class="{ active: tab === 'mine' }" @click="switchTab('mine')">
        我的工单
      </button>
      <button
        class="tab" :class="{ active: tab === 'pending' }"
        :disabled="!isAdmin" :title="isAdmin ? '' : '仅管理员可见'"
        @click="switchTab('pending')"
      >
        <AppIcon v-if="!isAdmin" name="lock" :size="13" />
        待我审批
        <span v-if="!isAdmin" class="tiny">仅管理员可见</span>
      </button>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>{{ tab === 'mine' ? '我提交的工单' : '待审批队列' }}</h2>
        <span class="hint hide-sm">
          {{ tab === 'mine' ? '写语句先落工单，批准后才执行' : '按提交先后排序，同一秒的工单翻页也不会乱' }}
        </span>
        <span class="spacer"></span>
        <button class="btn btn-sm" :disabled="loadingList" @click="loadActive()">
          <AppIcon name="refresh" :size="14" />刷新
        </button>
        <button class="btn btn-primary btn-sm" @click="openCreate">
          <AppIcon name="plus" :size="14" />提交工单
        </button>
      </div>

      <!-- 列表错误：toast 会自己消失，这里再留一条带「重试」的，让用户能自己救回来 -->
      <div v-if="listError" class="card-body tight">
        <div class="alert alert-danger mb-0">
          <AppIcon name="warn" :size="16" class="ico" />
          <div>
            {{ listError }}
            <button class="btn btn-sm" @click="loadActive()">重试</button>
          </div>
        </div>
      </div>

      <div v-if="loadingList" class="loading-line">
        <span class="spinner"></span>正在加载工单…
      </div>

      <AppEmpty
        v-else-if="!rows.length"
        :icon="tab === 'mine' ? 'inbox' : 'check'"
        :title="tab === 'mine' ? '还没有提交过工单' : '队列已清空'"
        :desc="tab === 'mine'
          ? '写语句（INSERT / UPDATE / DELETE）不需要直接权限：提交工单，管理员批准后由平台执行。'
          : '当前没有等待审批的工单。批准后的工单会在后台异步执行，随时回来看看。'"
      >
        <button v-if="tab === 'mine'" class="btn btn-primary btn-sm" @click="openCreate">
          <AppIcon name="plus" :size="14" />提交第一张工单
        </button>
      </AppEmpty>

      <template v-else>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>ID</th>
                <th>数据源</th>
                <th>语句类型</th>
                <th>SQL 摘要</th>
                <th>涉及表</th>
                <th class="num">预估行数</th>
                <th>状态</th>
                <th>提交时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in rows" :key="r.id">
                <td class="strong nowrap">#{{ r.id }}</td>
                <td class="nowrap" :title="'数据源 #' + r.dataSourceId">{{ sourceName(r.dataSourceId) }}</td>
                <td>
                  <span class="tag" :class="typeTagClass(r.sqlType)">{{ r.sqlType || '—' }}</span>
                </td>
                <!-- 摘要只放一行，全文进详情；title 挂全文，不点进去也能核对 -->
                <td class="break">
                  <span :title="r.sqlText">{{ sqlSummary(r.sqlText, 60) }}</span>
                </td>
                <td class="break">
                  {{ r.involvedTables && r.involvedTables.length ? r.involvedTables.join('、') : '—' }}
                </td>
                <!-- ⚠️ 这是 EXPLAIN 的预估**扫描**行数，不是最终改动行数 -->
                <td class="num" title="EXPLAIN 预估扫描行数，不是最终会改动的行数">{{ fmtNumber(r.estimatedRows) }}</td>
                <td><StatusTag :value="r.status" kind="review" /></td>
                <td class="nowrap" :title="fmtTime(r.createdAt)">{{ fmtRelative(r.createdAt) }}</td>
                <td class="actions">
                  <button class="btn btn-sm" @click="openDetail(r.id)">
                    <AppIcon name="eye" :size="14" />详情
                  </button>
                  <button
                    v-if="isAdmin && r.status === 'PENDING'"
                    class="btn btn-sm btn-ok"
                    :disabled="approvingId === r.id"
                    @click="quickApprove(r)"
                  >
                    <AppIcon name="check" :size="14" />{{ approvingId === r.id ? '批准中…' : '批准' }}
                  </button>
                </td>
              </tr>
            </tbody>
            <caption>
              批准是异步的：接口返回 200 只代表工单进入「已批准」，是否真的执行完以详情里的状态为准。
            </caption>
          </table>
        </div>

        <AppPager
          :total="active.total"
          :current="active.current"
          :size="SIZE"
          :disabled="loadingList"
          @update:current="onPageChange"
        />
      </template>
    </div>

    <!-- 提交工单：mask center + modal 自己拼（设计系统里没有 Modal 组件，AppDrawer 是侧边抽屉） -->
    <Teleport to="body">
      <div v-if="createOpen" class="mask center" @click.self="closeCreate">
        <div class="modal">
          <div class="modal-head">
            <AppIcon name="review" :size="17" />
            <h2>提交审核工单</h2>
            <span class="spacer"></span>
            <button class="icon-btn" title="关闭" :disabled="submitting" @click="closeCreate">
              <AppIcon name="close" :size="16" />
            </button>
          </div>

          <div class="modal-body">
            <div class="alert alert-info">
              <AppIcon name="info" :size="16" class="ico" />
              <div>
                写语句不会立刻执行：先落一张工单，管理员批准后才在后台执行。
                受理范围是 <b>INSERT / UPDATE / DELETE</b>；SELECT 请去查询工作台，DDL 走 DBA 流程。
              </div>
            </div>

            <!-- 一个数据源都没有时说明白去处，不要让用户对着空下拉框猜 -->
            <div v-if="!sources.length" class="alert alert-warn">
              <AppIcon name="warn" :size="16" class="ico" />
              <div>
                你名下还没有可用的数据源，而工单必须指定一个执行目标。
                <div class="mt-1"><RouterLink class="btn btn-sm" to="/datasource">去数据源管理</RouterLink></div>
              </div>
            </div>

            <div class="field">
              <label>数据源<i class="req">*</i></label>
              <select class="select" v-model.number="form.dataSourceId" :disabled="submitting">
                <option :value="null" disabled>请选择数据源</option>
                <!-- 已停用的仍然列出来但禁选：直接隐藏会让用户以为数据源丢了 -->
                <option v-for="s in sources" :key="s.id" :value="s.id" :disabled="s.status !== 1">
                  {{ s.name }}（{{ s.host }}:{{ s.port }}）{{ s.status === 1 ? '' : '（已停用）' }}
                </option>
              </select>
              <div class="tip">停用的数据源不能提交 —— 否则会出现「数据源已停用，工单却排队等执行」。</div>
            </div>

            <div class="field">
              <label>SQL<i class="req">*</i></label>
              <textarea
                class="textarea code" v-model="form.sql" rows="8" spellcheck="false"
                :disabled="submitting" placeholder="UPDATE t_order SET status = 1 WHERE id = 1001"
              ></textarea>
              <div class="tip">
                最多 20000 个字符（当前 {{ form.sql.length }}）。白名单放开的是语句类型，
                危险函数 / 系统库 / 可执行注释照旧拦 —— 但 WHERE 写得准不准，只有你自己知道。
              </div>
            </div>

            <div class="field mb-0">
              <label>申请理由</label>
              <textarea
                class="textarea" v-model="form.generationPrompt" rows="3" spellcheck="false"
                :disabled="submitting" placeholder="为什么改、依据是什么"
              ></textarea>
              <div class="tip">
                最多 1000 字。审批人靠它判断意图对不对；它也会进审计 ——
                这条 SQL 是照着什么需求写出来的（对应工单字段 generationPrompt）。
              </div>
            </div>
          </div>

          <div class="modal-foot">
            <button class="btn" :disabled="submitting" @click="closeCreate">取消</button>
            <button class="btn btn-primary" :disabled="submitting" @click="handleSubmit">
              <span v-if="submitting" class="spinner"></span>{{ submitting ? '提交中…' : '提交工单' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 工单详情：抽屉不打断列表的上下文；AppDrawer 自带遮罩 / Esc / 关闭按钮 -->
    <AppDrawer
      v-model:open="drawerOpen"
      :title="detail ? '工单 #' + detail.id : '工单详情'"
      width="min(760px, 96vw)"
    >
      <div v-if="detailLoading" class="loading-line">
        <span class="spinner"></span>正在加载工单详情…
      </div>

      <!-- 详情拉不到（不存在 / 无权看）：仍然用 AppEmpty 给一个完整的样子，不留白屏 -->
      <template v-else-if="!detail">
        <div v-if="detailError" class="alert alert-danger">
          <AppIcon name="warn" :size="16" class="ico" />
          <div>{{ detailError }}</div>
        </div>
        <AppEmpty
          icon="warn"
          title="工单不存在或无权查看"
          desc="别人提交的工单只有管理员能看；报「不存在」是刻意的 —— 回答「无权查看」等于确认了这个 id 上确实有工单。"
        >
          <button class="btn btn-sm" @click="closeDetail">关闭</button>
        </AppEmpty>
      </template>

      <template v-else>
        <div class="row wrap mb-2">
          <StatusTag :value="detail.status" kind="review" />
          <span class="tag" :class="typeTagClass(detail.sqlType)">{{ detail.sqlType || '—' }}</span>
          <span class="tag" v-if="detail.generatedBy === 'AI'">AI 生成</span>
          <span class="spacer"></span>
          <span v-if="isReviewRunning(detail.status)" class="row tiny muted">
            <span class="spinner"></span>执行中，每 3 秒自动刷新
          </span>
        </div>

        <div v-if="detail.status === 'FAILED'" class="alert alert-danger">
          <AppIcon name="warn" :size="16" class="ico" />
          <div><b>执行失败</b>：{{ detail.errorMessage || '数据库没有返回错误信息，请看后端日志' }}</div>
        </div>
        <div v-else-if="detail.status === 'REJECTED'" class="alert alert-warn">
          <AppIcon name="info" :size="16" class="ico" />
          <div>这张工单被驳回了，SQL 不会执行。按审批意见改完可以再提一张新的。</div>
        </div>

        <div class="card">
          <div class="card-head"><h2>基本信息</h2></div>
          <div class="card-body">
            <dl class="kv">
              <dt>工单 ID</dt>
              <dd>#{{ detail.id }}</dd>
              <dt>数据源</dt>
              <dd>
                {{ sourceName(detail.dataSourceId) }}
                <span class="muted-2 tiny">（#{{ detail.dataSourceId }}）</span>
              </dd>
              <dt>提交时间</dt>
              <dd>
                {{ fmtTime(detail.createdAt) }}
                <span class="muted-2 tiny">· {{ fmtRelative(detail.createdAt) }}</span>
              </dd>
              <dt>审批时间</dt>
              <dd>{{ detail.reviewedAt ? fmtTime(detail.reviewedAt) : '—' }}</dd>
              <dt>执行时间</dt>
              <dd>{{ detail.executedAt ? fmtTime(detail.executedAt) : '—' }}</dd>
              <!-- 影响行数只有执行完才有值；null 显示「—」（"没有这个数" ≠ "是 0"） -->
              <dt>影响行数</dt>
              <dd>{{ fmtNumber(detail.affectedRows) }}</dd>
              <template v-if="detail.errorMessage">
                <dt>错误信息</dt>
                <dd class="break">{{ detail.errorMessage }}</dd>
              </template>
            </dl>
          </div>
        </div>

        <div class="card">
          <div class="card-head">
            <h2>SQL 全文</h2>
            <span class="spacer"></span>
            <button class="btn btn-sm" @click="copyText(detail.sqlText)">
              <AppIcon name="copy" :size="14" />复制
            </button>
          </div>
          <div class="card-body">
            <div class="code">{{ detail.sqlText }}</div>
          </div>
        </div>

        <div class="card">
          <div class="card-head"><h2>影响面</h2></div>
          <div class="card-body">
            <dl class="kv">
              <dt>涉及表</dt>
              <dd>
                <span v-if="detail.involvedTables && detail.involvedTables.length" class="row wrap">
                  <span class="tag" v-for="t in detail.involvedTables" :key="t">{{ t }}</span>
                </span>
                <span v-else class="muted-2">—（没解析出表名）</span>
              </dd>
              <dt>预估扫描行数</dt>
              <dd>
                <span class="strong">{{ fmtNumber(detail.estimatedRows) }}</span>
                <!-- ⚠️ 这句是这一页最不能省的一句：把扫描行数当成"会改多少行"，
                     等于给审批人一个错的决策依据 -->
                <div class="tip">
                  ⚠️ 这是 <b>EXPLAIN 的预估扫描行数，不是最终会改动的行数</b>：
                  一条 UPDATE 可能扫描 10 万行、只改到 3 行。真正的改动行数看上面的「影响行数」，
                  而且要等执行完才有值。
                </div>
                <div v-if="detail.estimateNote" class="tip">{{ detail.estimateNote }}</div>
              </dd>
              <dt>申请理由</dt>
              <dd>{{ detail.generationPrompt || '—' }}</dd>
              <dt>审批意见</dt>
              <dd>{{ detail.reviewComment || '—' }}</dd>
            </dl>
          </div>
        </div>

        <!-- 留痕卡：它回答的是「出事的时候有没有退路」，所以单独一块、不塞进基本信息 -->
        <div class="card">
          <div class="card-head">
            <h2>执行留痕 · 回滚依据</h2>
            <span class="spacer"></span>
            <StatusTag v-if="detail.undoStatus" :value="detail.undoStatus" kind="undo" />
          </div>
          <div class="card-body">
            <div v-if="!detail.undoStatus" class="muted small">
              工单还没执行，所以还没有前镜像留存信息。执行完成后这里会出现「留存了什么」和反向 SQL。
            </div>
            <template v-else>
              <div
                class="alert"
                :class="detail.undoStatus === 'FAILED' ? 'alert-danger'
                  : detail.undoStatus === 'SKIPPED' ? 'alert-warn' : 'alert-ok'"
              >
                <AppIcon
                  :name="detail.undoStatus === 'FAILED' ? 'warn' : 'check'"
                  :size="16" class="ico"
                />
                <div>
                  <template v-if="detail.undoStatus === 'SKIPPED'">
                    这次<b>没有回滚依据</b>（语句形态不支持留存：多表更新、目标表没主键、改动行数超上限等）。
                    这不是故障，是能力边界 —— 要回退只能靠备份 / binlog。
                  </template>
                  <template v-else-if="detail.undoStatus === 'FAILED'">
                    取前镜像时出了<b>意外错误</b>，请连同工单号查后端日志。
                    它和「未留存」不是一回事：后者是能力边界，前者是真的异常。
                  </template>
                  <template v-else>
                    改动前的行已经留存，下面这段反向 SQL 可以人工核对。
                  </template>
                </div>
              </div>
              <dl class="kv">
                <dt>留存行数</dt>
                <dd>{{ fmtNumber(detail.undoRowCount) }}</dd>
                <dt>说明</dt>
                <dd>{{ detail.undoReason || '—' }}</dd>
              </dl>
              <template v-if="detail.inverseSql">
                <div class="row mt-2 mb-1">
                  <span class="strong small">反向 SQL（仅供人工核对）</span>
                  <span class="spacer"></span>
                  <button class="btn btn-sm" @click="copyText(detail.inverseSql)">
                    <AppIcon name="copy" :size="14" />复制
                  </button>
                </div>
                <div class="code">{{ detail.inverseSql }}</div>
                <!-- 平台上没有任何接口会执行这段 SQL，必须写在界面上：
                     否则有人会以为复制出去就能一键回滚 -->
                <div class="alert alert-info mt-1">
                  <AppIcon name="info" :size="16" class="ico" />
                  <div>
                    平台<b>不提供一键回滚</b>：要回滚请把这段 SQL 复制出来，<b>作为一张新工单提交</b>，
                    走完全相同的审批路径 —— 谁在什么时候回滚的，同样要留痕。
                  </div>
                </div>
              </template>
            </template>
          </div>
        </div>

        <!-- 审批区：只有 ADMIN + PENDING 才有意义（后端也是按这两个条件卡的） -->
        <div v-if="canDecide" class="card">
          <div class="card-head">
            <h2>审批</h2>
            <span class="spacer"></span>
            <span class="hint">批准后异步执行，可以关掉页面</span>
          </div>
          <div class="card-body">
            <div class="field mb-0">
              <label>审批意见</label>
              <textarea
                class="textarea" v-model="comment" rows="3" spellcheck="false"
                :disabled="busyDecision" placeholder="批准可以留空；驳回必须写明原因"
              ></textarea>
              <div class="tip">驳回必须写原因：申请人要靠它知道该改什么，否则只能原样再交一遍。</div>
            </div>
          </div>
        </div>
      </template>

      <template #footer>
        <span v-if="canDecide" class="tiny muted-2">
          <AppIcon name="shield" :size="12" />四眼原则：不能批准自己提交的工单
        </span>
        <span class="spacer"></span>
        <button class="btn" :disabled="busyDecision" @click="closeDetail">关闭</button>
        <button v-if="canDecide" class="btn btn-danger" :disabled="busyDecision" @click="handleReject">
          <span v-if="rejecting" class="spinner"></span>{{ rejecting ? '驳回中…' : '驳回' }}
        </button>
        <button v-if="canDecide" class="btn btn-ok" :disabled="busyDecision" @click="handleApprove">
          <span v-if="approving" class="spinner"></span>{{ approving ? '批准中…' : '批准并执行' }}
        </button>
      </template>
    </AppDrawer>
  </div>
</template>
