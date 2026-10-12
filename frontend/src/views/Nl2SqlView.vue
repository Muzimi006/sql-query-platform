<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { dataSourceApi, nl2sqlApi } from '../api'
import { copyText, toastError, toastOk, toastWarn } from '../ui'
import { fmtNumber, fmtRelative, fmtTime, newRequestToken, typeTone } from '../format'
import AppEmpty from '../components/AppEmpty.vue'
import AppIcon from '../components/AppIcon.vue'
import StatusTag from '../components/StatusTag.vue'

const router = useRouter()

/* ============================================================================
 * 自然语言生成 SQL
 *
 * 这个页面和项目里其它页面的最大区别：它不"查数"，它把一句中文变成一条
 * 待审的**数据变更**工单。所以页面的重点不是把表单画出来，而是三件事：
 *   1. 功能默认关闭，且关闭的原因是"表结构会出境"—— 必须讲清楚，不能装看不见；
 *   2. 生成结果不执行，落成工单 —— 用户最容易误解的就是这一点；
 *   3. 失败要分类给建议 —— 后端把原因都放在 message 里，几类失败的下一步完全不同。
 * ========================================================================== */

/* ---------- 一、功能开关 ---------- */

// 为什么进页面第一件事是问后端"你能不能跑"：
// 这个功能默认关闭（见 application.yml 的 platform.llm），如果直接渲染表单，
// 用户辛辛苦苦写了一句中文、点了生成，才收到"功能未启用"——
// 他会以为是自己写错了。先问状态，再把输入区禁用 + 说清楚怎么打开。
const checking = ref(true)
const available = ref(false)

// 状态接口本身失败（网络抖动 / 500）和"服务端明确说没启用"是两件事：
// 前者重试一下可能就好了，后者重试一万次也一样。混在一句提示里会误导人。
const statusError = ref('')

/* ---------- 二、数据源 ---------- */

const sources = ref([])
const dataSourceId = ref(null)

// "拉不到数据源"和"一个数据源都没有"要分开：前者重试可能就好了，
// 后者要去数据源管理页添加。都显示成空态会把用户引到错的方向。
const sourcesError = ref('')

// 只有启用中的数据源能选：停用的库连不上，拿不到表结构，
// 但列表里仍然列出来并标注"已停用"—— 直接隐藏会让用户以为数据源丢了。
const enabledSources = computed(() => sources.value.filter((s) => s.status === 1))

/* ---------- 三、表单 ---------- */

const prompt = ref('')
const generating = ref(false)

// 输入区在"功能未启用"和"正在生成"两种情况下都要锁住。
// 生成中锁住不只是防重复提交：这一次请求的幂等键绑定了当前的
// 数据源 + 原文，中途改字会让"重试"变成"另一次意图"。
const canInput = computed(() => available.value && !generating.value)

// 给用户看的例子，不是给模型看的。写得具体（表、日期、状态、改成什么），
// 是因为模型信息不足时会回问而不是猜 —— 一条没有 WHERE 的 UPDATE 比报错可怕得多。
const EXAMPLES = [
  '把票务表里 2026-10-01 之后状态为已取消的订单金额改成 0',
  '删除 2026-01-01 之前状态为已完成的登录日志'
]

/* ---------- 四、结果 / 失败 ---------- */

const ticket = ref(null)
const failure = ref(null)

/* ---------- 五、幂等键 ---------- */
//
// 约定（见 api.js）：同一次提交意图必须复用同一个 requestToken，
// 而换了载荷却复用 token，后端会按"载荷不一致"直接拒掉。
// 所以这里用「数据源 + 需求原文」当指纹：
//   · 失败后原样重试 → 指纹没变 → 复用 token，不会产生第二条工单；
//   · 改了需求再点   → 指纹变了   → 换新 token，那本来就是另一次意图。
// 成功后清掉指纹：下一句需求要是撞上上一句的 token，会被当成重复提交丢掉。
const token = ref('')
const tokenKey = ref('')

function takeToken(key) {
  if (!token.value || tokenKey.value !== key) {
    token.value = newRequestToken()
    tokenKey.value = key
  }
  return token.value
}

/* ---------- 六、失败分类 ---------- */
//
// 后端所有失败都是 HTTP 200 + message（业务失败），所以只能按文案分流。
// ⚠️ 匹配串必须和 NaturalLanguageSqlService 里的 BusinessException 文案一致：
//    后端改了文案，这里会退化成"通用失败"分支 —— 不会崩，但会少给一条可操作建议。
const FAIL_META = {
  query: {
    icon: 'search',
    tone: 'tag-info',
    title: '这是一句查询需求，不用走审批',
    tag: '不建工单',
    message: '模型判断你描述的是一句查询需求。后端没有把它变成工单，而是把生成的 SELECT 原样返回给你。',
    advice: '查询不需要审批，所以这里不会受理。点「去查询页执行」会把这条 SQL 带到查询工作台 —— 只是回填表单，不会自动执行，你确认后再点执行。'
  },
  unsafe: {
    icon: 'shield',
    tone: 'tag-danger',
    title: '模型生成的 SQL 没有通过安全校验',
    tag: '安全防线拦下',
    message: '',
    advice: '这是预期行为，不是故障：模型生成的 SQL 和你手敲的 SQL 走的是同一套四道防线，被拦说明防线起作用了。'
      + '可以换一种说法再试（把范围写具体、避开危险函数），或者直接到「审核工单」页手写 SQL 提交。'
  },
  ddl: {
    icon: 'warn',
    tone: 'tag-warn',
    title: '语句类型不在工单受理范围内',
    tag: '不受理 DDL',
    message: '',
    advice: '工单只受理数据变更（INSERT / UPDATE / DELETE）。建表 / 改表 / 删表（CREATE / ALTER / DROP / TRUNCATE）属于结构变更，要由 DBA 走变更流程 —— 换个说法再生成也是一样的结果。'
  },
  needMore: {
    icon: 'info',
    tone: 'tag-info',
    title: '模型认为信息不够，主动回问了',
    tag: '需要补充信息',
    message: '',
    advice: '把缺的说清楚再试：具体日期、景区名称、票种、数据范围，例如「把灵隐寺 2026-03-08 的成人票余票改成 500」。模型宁可回问也不猜 —— 猜错的结果很可能是一条没有 WHERE 的 UPDATE。'
  },
  rate: {
    icon: 'bolt',
    tone: 'tag-warn',
    title: '生成太频繁，被频率限制挡下了',
    tag: '频率限流',
    message: '',
    advice: '每个用户每分钟最多 5 次（platform.llm.rate-limit.max-requests / window-seconds）。这道闸门挡的是脚本刷：每一次生成都是一次付费的外部调用。等一分钟再试，连点只会一直被挡。'
  },
  busy: {
    icon: 'bolt',
    tone: 'tag-warn',
    title: '当前生成任务较多，请稍后再试',
    tag: '并发限流 · 不是故障',
    message: '',
    advice: '这是并发限流，不是系统坏了：同一时刻最多 4 个生成任务在跑（platform.llm.max-concurrent，默认 4），拿不到额度时后端会先等最多 2 秒（platform.llm.acquire-timeout-ms，默认 2000）再拒绝。'
      + '说明此刻有人正在生成，等几秒再点即可 —— 刷新页面帮不上忙，也不会有数据丢失。'
  },
  disabled: {
    icon: 'lock',
    tone: 'tag-warn',
    title: '功能在服务端被关掉了',
    tag: '未启用',
    message: '',
    advice: '需要由部署方设置 platform.llm.enabled=true 并配置 LLM_API_KEY（建议用环境变量注入）后重启服务。改好之后点「重新检查」。'
  },
  unknown: {
    icon: 'warn',
    tone: 'tag-danger',
    title: '生成失败',
    tag: '失败',
    message: '',
    advice: '可以先按原样再点一次「生成 SQL」—— 重试复用同一个幂等键，不会重复建单。仍然失败就把需求写得更具体，或到「审核工单」页手写 SQL 提交。'
  }
}

/**
 * 后端的"查询需求"分支把生成的 SELECT 直接拼在错误消息末尾
 * （"…请复制到查询页执行：" + sql）。这里按最后一个全角冒号切出来；
 * 切不出来就退回"从第一个 SQL 关键字开始截"，宁可多显示一段文字，
 * 也不要让用户面对一个空的代码块。
 */
function extractSql(msg) {
  const marker = '执行：'
  const i = msg.lastIndexOf(marker)
  if (i >= 0) {
    const s = msg.slice(i + marker.length).trim()
    if (s) return s
  }
  const m = msg.match(/\b(select|insert|update|delete)\b[\s\S]*/i)
  return m ? m[0].trim() : ''
}

function classifyFailure(e) {
  const msg = e && e.message ? String(e.message) : '生成失败'
  let kind = 'unknown'
  // 顺序有讲究：'未启用' 放最后，避免被别的文案里的"功能"之类误命中
  if (msg.includes('这是一句查询需求')) kind = 'query'
  else if (msg.includes('不在工单受理范围内')) kind = 'ddl'
  else if (msg.includes('没有通过安全校验')) kind = 'unsafe'
  else if (msg.includes('信息不够')) kind = 'needMore'
  else if (msg.includes('生成太频繁')) kind = 'rate'
  else if (msg.includes('当前生成任务较多')) kind = 'busy'
  else if (msg.includes('未启用')) kind = 'disabled'

  const meta = FAIL_META[kind]
  return {
    kind,
    icon: meta.icon,
    tone: meta.tone,
    title: meta.title,
    tag: meta.tag,
    advice: meta.advice,
    // query 分支的原文里带着 SQL，重复显示反而乱；其余分支保留后端原话，
    // 因为它通常已经带了具体原因（哪个函数、哪种类型）
    message: meta.message || msg,
    sql: kind === 'query' ? extractSql(msg) : ''
  }
}

/* ---------- 七、数据加载 ---------- */

async function loadStatus() {
  checking.value = true
  statusError.value = ''
  try {
    const s = await nl2sqlApi.status()
    available.value = Boolean(s && s.available)
    if (available.value) await loadSources()
  } catch (e) {
    // 拿不到状态时按"不可用"处理（输入区禁用），但要明说是"没问到"而不是"被关闭"
    available.value = false
    statusError.value = e && e.message ? e.message : '请求失败'
    toastError(e, '没能取到功能状态')
  } finally {
    checking.value = false
  }
}

async function loadSources() {
  sourcesError.value = ''
  try {
    sources.value = (await dataSourceApi.list()) || []
    const first = enabledSources.value[0]
    if (first) dataSourceId.value = first.id
  } catch (e) {
    // 数据源拉不到不该把整页搞崩：表单还在，用户至少能看到提示并重试
    sourcesError.value = e && e.message ? e.message : '加载失败'
    toastError(e, '数据源加载失败')
  }
}

onMounted(loadStatus)

/* ---------- 八、动作 ---------- */

function useExample(text) {
  // 点例子只回填、不自动提交：等于替用户做了一次"把表结构发出去"的决定，
  // 让他先看一眼、改两个词，再自己点生成
  prompt.value = text
}

function startOver() {
  // 保留数据源和原文：多数人是"改一个词再生成一条"，清空反而添乱
  ticket.value = null
  failure.value = null
  tokenKey.value = ''
  toastOk('已清空上次结果，可以改一改需求再生成')
}

async function handleGenerate() {
  if (!available.value) return
  if (!dataSourceId.value) {
    toastWarn('请先选择数据源')
    return
  }
  const text = prompt.value.trim()
  if (!text) {
    toastWarn('请先描述你想要的数据变更')
    return
  }
  // 先在本地拦长度：后端限长 500（超了会是 400 参数校验错误），
  // 前端拦一次能省掉一次付费的外部模型调用
  if (text.length > 500) {
    toastWarn('描述不能超过 500 字')
    return
  }

  generating.value = true
  failure.value = null
  ticket.value = null
  try {
    const data = await nl2sqlApi.ticket({
      dataSourceId: dataSourceId.value,
      prompt: text,
      requestToken: takeToken(dataSourceId.value + '\n' + text)
    })
    ticket.value = data
    tokenKey.value = '' // 这一笔已经落库，下一次生成是新的意图
    toastOk('已生成 SQL 并提交为待审工单')
  } catch (e) {
    failure.value = classifyFailure(e)
    // query 分支不是"出错"，是"走错入口了"，用 warn 而不是红 toast
    if (failure.value.kind === 'query') {
      toastWarn('这是一句查询需求，不需要审批：去查询页执行')
    } else {
      toastError(e, '生成失败')
    }
  } finally {
    generating.value = false
  }
}

function goQueryPage() {
  // 跨页面带参的约定写法（见开发约定 6.2）：查询页挂载时读 route.query.sql 回填，
  // 然后 router.replace 把参数清掉 —— 所以这里不需要再做别的清理
  router.push({
    path: '/query',
    query: { sql: failure.value?.sql || '', dataSourceId: dataSourceId.value ?? '' }
  })
}

/* ---------- 九、结果区的展示派生 ---------- */

const ticketTone = computed(() => (ticket.value ? typeTone(ticket.value.sqlType) : ''))
const tables = computed(() => (ticket.value && ticket.value.involvedTables) || [])
const tableCountText = computed(() => (tables.value.length ? tables.value.length + ' 张' : '—'))
const tableNamesText = computed(() =>
  tables.value.length ? tables.value.join('、') : '没有识别到表（可能是预估失败，审批时留意）'
)
</script>

<template>
  <div class="content-narrow">

    <!-- ① 加载中：整页一个加载态，避免"表单先出来、又变成不可用"的跳动 -->
    <div v-if="checking" class="card">
      <div class="card-body">
        <div class="loading-line"><span class="spinner"></span>正在检查自然语言生成功能是否可用…</div>
      </div>
    </div>

    <template v-else>
      <!-- ② 状态没问到：说清楚这是"没问到"，不是"被关闭" -->
      <div v-if="statusError" class="alert alert-danger">
        <AppIcon name="warn" :size="16" />
        <div class="break">
          <div class="strong">没能取到功能状态：{{ statusError }}</div>
          <div class="small mt-1">这不代表功能被关闭 —— 也可能只是这一次请求失败。功能状态未知时输入区会保持禁用。</div>
        </div>
        <span class="spacer"></span>
        <button class="btn btn-sm" @click="loadStatus"><AppIcon name="refresh" :size="14" />重新检查</button>
      </div>

      <!-- ③ 关闭态：这是"数据出境"的开关，代价和打开方式都要写在明面上 -->
      <div v-if="!available" class="alert alert-warn">
        <AppIcon name="lock" :size="16" />
        <div class="break">
          <div class="strong">该功能默认关闭：它要把表结构发往外部模型服务，是一次真实的数据出境。</div>
          <div class="small mt-1">
            要启用需由部署方设置 <code>platform.llm.enabled=true</code> 并配置 <code>LLM_API_KEY</code>（建议用环境变量注入），然后重启服务。
            表结构一旦发出去就收不回来，所以这个决定必须由部署方签字，不能让应用自己打开 —— 没配密钥时平台照常启动，只是这个入口不可用。
          </div>
        </div>
      </div>

      <!-- ④ 主卡片 -->
      <div class="card">
        <div class="card-head">
          <AppIcon name="magic" :size="16" />
          <h2>把一句中文需求换成一条待审 SQL</h2>
          <span class="spacer"></span>
          <span class="tag" :class="available ? 'tag-ok' : 'tag-warn'">
            <i class="dot"></i>{{ available ? '已启用' : '未启用' }}
          </span>
        </div>

        <div class="card-body">
          <!-- 关键概念：用户对这个功能最大的误解就是"它能帮我查数"。
               放在表单之前，因为误解一旦带进输入框，写出来的需求就是错的。 -->
          <div class="alert alert-info">
            <AppIcon name="info" :size="16" />
            <div class="break">
              <div class="strong mb-1">先对齐一个概念：这不是「自然语言查询」</div>
              <div class="small">· 它生成的是 <span class="strong">INSERT / UPDATE / DELETE</span>，是数据变更，不是 SELECT。</div>
              <div class="small">· 生成的 SQL <span class="strong">不会直接执行</span>：它会先落成一张<span class="strong">待审工单</span>，管理员批准后才由后台异步执行。</div>
              <div class="small">· 工单里同时躺着三样东西：<span class="strong">你的原话</span>、<span class="strong">模型生成的 SQL</span>、<span class="strong">影响面预估</span>（动哪几张表、扫多少行）—— 审批人审的是"意图"，而不是一段不知从哪来的代码。</div>
              <div class="small">· 如果你的需求本质是<span class="strong">查询</span>，后端会把模型给的 <span class="strong">SELECT 原样返回</span>并提示你去查询页执行：查询不需要审批，这里不会建工单。</div>
              <div class="small">· 模型生成的 SQL 和你手敲的 SQL 走<span class="strong">完全相同</span>的校验与审批路径，AI 没有任何特权。</div>
            </div>
          </div>

          <!-- 数据源 -->
          <div class="field">
            <label>数据源<span class="req">*</span></label>
            <select class="select" v-model.number="dataSourceId" :disabled="!canInput">
              <!-- 占位项：功能未启用 / 数据源还没加载出来时，下拉框不至于是一片空白 -->
              <option :value="null" disabled>
                {{ available ? '请选择数据源' : '功能未启用，暂不可选' }}
              </option>
              <option v-for="s in sources" :key="s.id" :value="s.id" :disabled="s.status !== 1">
                {{ s.name }}（{{ s.type }} · {{ s.host }}:{{ s.port }}）{{ s.status === 1 ? '' : '（已停用）' }}
              </option>
            </select>
            <div class="tip">这张库的表结构（表名 / 列名 / 类型）会随本次请求一起发给外部模型服务。</div>
          </div>

          <!-- 需求 -->
          <div class="field">
            <label>你要做什么<span class="req">*</span></label>
            <textarea
              class="textarea"
              v-model="prompt"
              maxlength="500"
              :disabled="!canInput"
              placeholder="例如：把票务表里 2026-10-01 之后状态为已取消的订单金额改成 0"
            ></textarea>
            <div class="tip row between">
              <span>写清楚「改哪张表、哪些行、改成什么」。条件不全时模型会回问，而不是猜。</span>
              <span class="nowrap">{{ prompt.trim().length }} / 500</span>
            </div>
          </div>

          <!-- 示例：只回填、不提交 -->
          <div class="row wrap mb-2">
            <span class="tiny muted-2">换个说法试试：</span>
            <button
              v-for="ex in EXAMPLES" :key="ex"
              class="btn btn-sm btn-ghost" :disabled="!canInput"
              @click="useExample(ex)"
            >{{ ex }}</button>
          </div>

          <div class="row wrap">
            <button class="btn btn-primary" :disabled="generating || !available" @click="handleGenerate">
              <span v-if="generating" class="spinner"></span>
              <AppIcon v-else name="magic" :size="15" />
              {{ generating ? '生成中…（最长 30 秒）' : '生成 SQL' }}
            </button>
            <button v-if="prompt" class="btn btn-ghost btn-sm" :disabled="generating" @click="prompt = ''">清空需求</button>
            <span class="spacer"></span>
            <span v-if="!available" class="tiny muted-2"><AppIcon name="lock" :size="12" /> 输入区已禁用：功能未启用</span>
            <span v-else-if="generating" class="tiny muted-2">正在调用外部模型服务，请不要刷新页面</span>
          </div>
        </div>
      </div>

      <!-- ⑤ 空态：有功能、但一个能用的数据源都没有时，表单填了也提交不了 -->
      <div v-if="available && (sourcesError || enabledSources.length === 0)" class="card">
        <div class="card-body">
          <!-- 加载失败和"确实没有"分开说，否则用户会跑去数据源管理页找一个其实存在的库 -->
          <AppEmpty
            v-if="sourcesError"
            icon="warn"
            title="数据源没能加载出来"
            :desc="sourcesError"
          >
            <button class="btn btn-sm" @click="loadStatus">
              <AppIcon name="refresh" :size="14" />重新加载
            </button>
          </AppEmpty>
          <AppEmpty
            v-else
            icon="database"
            title="还没有可用的数据源"
            desc="生成 SQL 要先读出库的表结构再发给模型，所以至少要有一个启用中的数据源。"
          >
            <button class="btn btn-primary btn-sm" @click="router.push('/datasource')">
              <AppIcon name="plus" :size="14" />去数据源管理添加
            </button>
          </AppEmpty>
        </div>
      </div>

      <!-- ⑥ 失败：每类失败给不同的"下一步"，只说"失败"等于把问题丢回给用户 -->
      <div v-if="failure" class="card">
        <div class="card-head">
          <AppIcon :name="failure.icon" :size="16" />
          <h2>{{ failure.title }}</h2>
          <span class="spacer"></span>
          <span class="tag" :class="failure.tone">{{ failure.tag }}</span>
        </div>
        <div class="card-body">
          <div class="alert" :class="failure.kind === 'query' ? 'alert-info' : 'alert-danger'">
            <AppIcon :name="failure.kind === 'query' ? 'info' : 'warn'" :size="16" />
            <div class="break">{{ failure.message }}</div>
          </div>

          <!-- 查询需求：把模型给的 SELECT 摆出来，并给一个能直接走的出口 -->
          <template v-if="failure.sql">
            <div class="row mb-1">
              <span class="strong small">模型生成的查询语句</span>
              <span class="spacer"></span>
              <button class="btn btn-sm" @click="copyText(failure.sql)"><AppIcon name="copy" :size="14" />复制</button>
            </div>
            <div class="code">{{ failure.sql }}</div>
          </template>

          <div class="small muted-2 mt-2 break">{{ failure.advice }}</div>
        </div>
        <div class="card-foot">
          <template v-if="failure.kind === 'query'">
            <button class="btn btn-primary" @click="goQueryPage">
              <AppIcon name="query" :size="15" />去查询页执行
            </button>
            <button class="btn" @click="copyText(failure.sql)"><AppIcon name="copy" :size="15" />复制 SQL</button>
          </template>
          <button v-else-if="failure.kind === 'unsafe'" class="btn btn-primary" @click="router.push('/review')">
            <AppIcon name="review" :size="15" />去手写 SQL 提交工单
          </button>
          <button v-else-if="failure.kind === 'disabled'" class="btn btn-primary" @click="loadStatus">
            <AppIcon name="refresh" :size="15" />重新检查
          </button>
          <button v-else class="btn" @click="handleGenerate">
            <AppIcon name="refresh" :size="15" />按原样重试
          </button>
          <span class="spacer"></span>
          <!-- 幂等提示只对"重试"这条路径有意义：查询需求和未启用这两类根本没有提交过 -->
          <span v-if="failure.kind !== 'query' && failure.kind !== 'disabled'" class="tiny muted-2">
            重试会复用同一个幂等键，不会重复建单
          </span>
        </div>
      </div>

      <!-- ⑦ 成功：先给 SQL，再给影响面，最后给去工单的入口 -->
      <div v-if="ticket" class="card">
        <div class="card-head">
          <AppIcon name="review" :size="16" />
          <h2>已提交为工单 #{{ ticket.id }}</h2>
          <span class="spacer"></span>
          <StatusTag :value="ticket.status" kind="review" />
          <span class="tag" :class="ticketTone ? 'tag-' + ticketTone : ''">{{ ticket.sqlType }}</span>
        </div>
        <div class="card-body">
          <div class="alert alert-ok">
            <AppIcon name="check" :size="16" />
            <div class="break">
              这条 SQL <span class="strong">没有执行</span>，它已经变成一张待审工单。
              管理员批准后由后台异步执行 —— 所以「批准了」也不等于「执行完了」，结果要回工单详情看。
            </div>
          </div>

          <div class="row mb-1">
            <span class="strong small">模型生成的 SQL</span>
            <span class="spacer"></span>
            <button class="btn btn-sm" @click="copyText(ticket.sqlText)"><AppIcon name="copy" :size="14" />复制</button>
          </div>
          <div class="code">{{ ticket.sqlText }}</div>

          <div class="stats mt-3">
            <div class="stat">
              <div class="k"><AppIcon name="bolt" :size="13" />语句类型</div>
              <div class="v">{{ ticket.sqlType || '—' }}</div>
              <div class="s">数据变更语句，审批通过后才执行</div>
            </div>
            <div class="stat">
              <div class="k"><AppIcon name="database" :size="13" />涉及表</div>
              <div class="v">{{ tableCountText }}</div>
              <div class="s break">{{ tableNamesText }}</div>
            </div>
            <div class="stat">
              <div class="k"><AppIcon name="chart" :size="13" />预估扫描行数</div>
              <div class="v">{{ fmtNumber(ticket.estimatedRows) }}</div>
              <div class="s break">{{ ticket.estimateNote || '来自 EXPLAIN 的估算，仅供审批参考' }}</div>
            </div>
          </div>

          <dl class="kv">
            <dt>需求原话</dt>
            <dd class="break">{{ ticket.generationPrompt || '—' }}</dd>
            <dt>来源</dt>
            <dd>AI（模型生成）· 与手写 SQL 走同一套校验和审批</dd>
            <dt>提交时间</dt>
            <dd :title="fmtTime(ticket.createdAt)">{{ fmtRelative(ticket.createdAt) }}</dd>
          </dl>
        </div>
        <div class="card-foot">
          <button class="btn btn-primary" @click="router.push({ name: 'review-detail', params: { id: ticket.id } })">
            <AppIcon name="arrowRight" :size="15" />查看工单详情
          </button>
          <button class="btn" @click="startOver">
            <AppIcon name="refresh" :size="15" />再生成一条
          </button>
          <span class="spacer"></span>
          <span class="tiny muted-2">工单号 #{{ ticket.id }} · 审批通过后由后台执行</span>
        </div>
      </div>

      <!-- ⑧ 空态：还没生成过任何东西时，别把下半页留白 -->
      <div v-if="available && !ticket && !failure" class="card">
        <div class="card-body">
          <AppEmpty
            icon="magic"
            title="还没有生成任何 SQL"
            desc="在上面写一句数据变更需求再点「生成 SQL」。结果会先落成一张待审工单，不会直接执行。"
          >
            <button class="btn btn-sm" @click="router.push('/review')">
              <AppIcon name="review" :size="14" />看看我的工单
            </button>
          </AppEmpty>
        </div>
      </div>

      <!-- ⑨ 限流说明：把"卡了一下"和"坏了"区分开，否则用户会反复点重试 -->
      <div class="card">
        <div class="card-head">
          <AppIcon name="bolt" :size="16" />
          <h2>为什么会有频率和并发限制</h2>
          <span class="spacer"></span>
          <span class="hint">每一次生成都是一次付费的外部调用</span>
        </div>
        <div class="card-body">
          <dl class="kv">
            <dt>频率限制</dt>
            <dd class="break">
              每个用户每分钟最多 <span class="strong">5 次</span>（<code>platform.llm.rate-limit.max-requests</code>、
              <code>window-seconds</code>）。超了会提示「生成太频繁，请稍后再试」——
              这是挡脚本刷的，连点没有意义。
            </dd>
            <dt>并发限制</dt>
            <dd class="break">
              同一时刻最多 <span class="strong">4 个</span>生成任务在跑（<code>platform.llm.max-concurrent</code>，默认 4）；
              拿不到额度时会先等最多 <span class="strong">2 秒</span>（<code>platform.llm.acquire-timeout-ms</code>，默认 2000），等不到才拒绝。
              一次生成最长会占 30 秒的服务端线程，所以这道闸门必须有。
            </dd>
            <dt>「当前生成任务较多」</dt>
            <dd class="break">
              这是<span class="strong">并发限流，不是故障</span>：说明此刻有人正在生成。等几秒再点即可，
              不需要刷新页面，也不会有任何数据因此丢失。
            </dd>
          </dl>
        </div>
      </div>
    </template>
  </div>
</template>
