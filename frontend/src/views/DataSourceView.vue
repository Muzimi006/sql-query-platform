<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { dataSourceApi } from '../api'
import { confirmDialog, toastError, toastOk, toastWarn } from '../ui'
import { fmtRelative, fmtTime } from '../format'
import AppIcon from '../components/AppIcon.vue'
import AppEmpty from '../components/AppEmpty.vue'
import AppPager from '../components/AppPager.vue'
import StatusTag from '../components/StatusTag.vue'

/* 数据源管理：列表 + 新增/编辑弹窗 + 测试连接 + 启用停用 + 删除。
 *
 * 两条必须记住的后端语义（写错不会有报错，只有"功能看起来不对"）：
 *  1. 出参永不下发密码 —— 所以编辑时表单里的密码永远是空的，
 *     也就意味着"改个名字"这件事在后端要求必须重新提交一次密码（见 validate() 的说明）。
 *  2. /test 与新增/编辑共用 DbSourceDTO，@Valid 会校验全部必填项，
 *     所以测试连接必须在一个填满字段的表单里做，不能拿列表行的数据直接测。
 */

const rows = ref([])
const total = ref(0)
const current = ref(1)
const size = ref(10)
const loading = ref(false)
const loadFailed = ref(false) // 加载失败要和"本来就没有数据"区分开：两者都是空列表，但提示语完全不同
const busyId = ref(null) // 行内操作（停用/删除）进行中，用来禁用该行按钮，避免连点

// 支持的数据库类型由后端下发，前端不写死清单：
// 两边各维护一份，后端加了方言而前端没加，用户根本选不到它 —— 而且不会有任何报错
const dialects = ref([])

const modalOpen = ref(false)
const editingId = ref(null)
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null) // { ok, message }：连接结果就地展示，比 toast 更容易看第二眼
const testHint = ref(false) // 从列表「测试」进来的：先提示要填密码

const emptyForm = () => ({
  name: '',
  type: '',
  host: 'localhost',
  port: null,
  databaseName: '',
  username: '',
  password: ''
})
const form = ref(emptyForm())

const isEditing = computed(() => editingId.value !== null)
const modalTitle = computed(() => (isEditing.value ? '编辑数据源' : '新增数据源'))

/** 端口跟随类型：MySQL 3306 / PostgreSQL 5432。
 *  不做这件事，最典型的错误就是"类型换了端口没换"，而它的表现是连接超时 —— 看不出原因。 */
function applyDefaultPort() {
  const picked = dialects.value.find((d) => d.value === form.value.type)
  if (picked) form.value.port = picked.defaultPort
}

async function loadDialects() {
  try {
    dialects.value = await dataSourceApi.dialects()
    if (!form.value.type && dialects.value.length) {
      form.value.type = dialects.value[0].value
      applyDefaultPort()
    }
  } catch (e) {
    toastError(e, '加载数据库类型失败')
  }
}

async function loadPage() {
  loading.value = true
  try {
    // ⚠️ 约定用法。注意 api.js 里 page() 发的是 ?current=&size=，
    //    而后端 DbSourceController#page 读的参数名是 page —— 除第 1 页外都会拿到第 1 页的数据。
    //    api.js 不属于本次改动范围，这里保持约定写法，问题记在交付说明里。
    const page = await dataSourceApi.page(current.value, size.value)
    rows.value = page?.records || []
    total.value = Number(page?.total || 0)
    loadFailed.value = false
  } catch (e) {
    rows.value = []
    total.value = 0
    loadFailed.value = true
    toastError(e, '加载数据源失败')
  } finally {
    loading.value = false
  }
}

// 翻页只改 current，由 watch 统一触发加载 —— 免得"点下一页"和"删除后回退一页"
// 两条路径各写一次加载逻辑，早晚会漏掉一处
watch(current, loadPage)

function changeSize() {
  // current 本来就是 1 时 watch 不会触发，必须自己加载；
  // 否则「切每页条数但停在第 1 页」会什么都不发生
  if (current.value === 1) loadPage()
  else current.value = 1 // 交给 watch
}

function openAdd() {
  editingId.value = null
  form.value = emptyForm()
  if (dialects.value.length) {
    form.value.type = dialects.value[0].value
    applyDefaultPort()
  }
  testResult.value = null
  testHint.value = false
  modalOpen.value = true
}

function openEdit(row) {
  editingId.value = row.id
  // 密码留空：后端从不下发密码，这里没有任何办法回填
  form.value = {
    name: row.name || '',
    type: row.type || '',
    host: row.host || '',
    port: row.port ?? null,
    databaseName: row.databaseName || '',
    username: row.username || '',
    password: ''
  }
  testResult.value = null
  testHint.value = false
  modalOpen.value = true
}

/** 列表行上的「测试」：因为 /test 要求完整 DTO（含密码），只能开弹窗让人补密码。 */
function openTest(row) {
  openEdit(row)
  testHint.value = true
}

function closeModal() {
  modalOpen.value = false
  editingId.value = null
  testResult.value = null
  testHint.value = false
}

function buildPayload() {
  return {
    name: form.value.name.trim(),
    type: form.value.type,
    host: form.value.host.trim(),
    port: Number(form.value.port),
    // 库名允许为空（后端也没有 @NotBlank）：MySQL 会连到"没有默认库"的状态
    databaseName: form.value.databaseName.trim() || null,
    username: form.value.username.trim(),
    password: form.value.password
  }
}

/**
 * 前端校验只做"能少跑一次请求"的那部分，真实规则以后端 @Valid 为准。
 *
 * ⚠️ 校验项要跟 /test 对齐：测试连接和新增/编辑**共用同一个 DbSourceDTO**，
 *    名称、端口这些都带 @NotBlank / @NotNull，少校验一项就会拿回一句
 *    「数据源名称不能为空」这种跟"连接"毫无关系的报错。
 *
 * @param purpose 'save' | 'test' —— 只影响密码那一句提示的措辞
 */
function validate(purpose = 'save') {
  const f = form.value
  if (!f.name.trim()) return '请填写数据源名称（测试连接与保存共用一套校验，名称也是必填）'
  if (!f.type) return '请选择数据库类型'
  if (!f.host.trim()) return '请填写主机地址'
  const port = Number(f.port)
  if (!Number.isInteger(port) || port < 1 || port > 65535) return '端口必须是 1 ~ 65535 之间的整数'
  if (!f.username.trim()) return '请填写数据库账号'
  if (!f.password) {
    if (purpose === 'test') return '测试连接需要数据库密码：服务端从不下发已保存的密码'
    return isEditing.value
      ? '请填写数据库密码：后端更新接口当前要求密码必填（留空会被拒绝为「数据库密码不能为空」）'
      : '请填写数据库密码'
  }
  return ''
}

async function handleTest() {
  if (testing.value || saving.value) return
  const bad = validate('test')
  if (bad) { toastWarn(bad); return }

  testResult.value = null
  testing.value = true
  try {
    const ok = await dataSourceApi.test(buildPayload())
    testResult.value = ok
      ? { ok: true, message: '连接成功，可以保存了' }
      : { ok: false, message: '连接失败' }
    if (ok) toastOk('连接成功')
    else toastWarn('连接失败')
  } catch (e) {
    // 后端把 SQLException 翻译成了「无法连接到数据库：…」，这就是"人话"，
    // 原样展示即可；再包一层"操作失败"反而把真正的原因盖掉了。
    // 另外这个接口有频率限制，命中时后端会返回「操作太频繁，请稍后再试」——不要自动重试。
    testResult.value = { ok: false, message: (e && e.message) || '连接失败' }
    toastError(e, '连接失败')
  } finally {
    testing.value = false
  }
}

async function handleSave() {
  if (saving.value || testing.value) return
  const bad = validate()
  if (bad) { toastWarn(bad); return }

  // ⚠️ 必须先存下来：closeModal() 会把 editingId 清成 null，
  //    之后再读 isEditing 就是 false 了，新增/编辑会走错分支
  const editing = isEditing.value
  const id = editingId.value

  saving.value = true
  try {
    if (editing) {
      await dataSourceApi.update(id, buildPayload())
      toastOk('数据源已更新')
    } else {
      await dataSourceApi.add(buildPayload())
      toastOk('数据源已添加')
    }
    closeModal()
    // 列表按 created_at 倒序，新增的排在第一页第一条；不回到第 1 页会"看不到刚加的东西"
    if (!editing && current.value !== 1) current.value = 1 // 触发 watch
    else await loadPage()
  } catch (e) {
    // 名称重复、连接信息不合法等都在这里，后端文案已经是给人看的
    toastError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleToggle(row) {
  const disabling = row.status === 1
  const ok = await confirmDialog({
    title: disabling ? '停用数据源' : '启用数据源',
    message: disabling
      ? `停用「${row.name}」后，所有使用它的查询和导出会立即失败（配置保留，随时可以再启用）。确定停用？`
      : `启用「${row.name}」后，就可以对它执行查询和导出了。确定启用？`,
    okText: disabling ? '停用' : '启用',
    danger: disabling
  })
  if (!ok) return

  busyId.value = row.id
  try {
    await dataSourceApi.changeStatus(row.id, disabling ? 0 : 1)
    toastOk(disabling ? '数据源已停用' : '数据源已启用')
    await loadPage()
  } catch (e) {
    toastError(e, '状态修改失败')
  } finally {
    busyId.value = null
  }
}

async function handleDelete(row) {
  const ok = await confirmDialog({
    title: '删除数据源',
    message: `删除「${row.name}」后，使用它的查询和导出会立即失败，且无法恢复。确定删除？`,
    okText: '删除',
    danger: true
  })
  if (!ok) return

  busyId.value = row.id
  try {
    await dataSourceApi.remove(row.id)
    toastOk('数据源已删除')
    // 删掉本页最后一条时往前退一页：不退的话会停在一个空列表上，
    // 用户第一反应是"数据全没了"而不是"这一页空了"
    if (rows.value.length === 1 && current.value > 1) current.value -= 1 // 触发 watch
    else await loadPage()
  } catch (e) {
    toastError(e, '删除失败')
  } finally {
    busyId.value = null
  }
}

onMounted(async () => {
  await Promise.all([loadDialects(), loadPage()])
})
</script>

<template>
  <div class="content-narrow">
    <div class="card">
      <div class="card-head">
        <h2>我的数据源</h2>
        <span class="spacer"></span>
        <span class="hint hide-sm">停用后，该数据源上的查询与导出会立即失败</span>
        <select v-if="total > 10" v-model.number="size" class="select page-size hide-sm" @change="changeSize">
          <option :value="10">10 条/页</option>
          <option :value="20">20 条/页</option>
          <option :value="50">50 条/页</option>
        </select>
        <button class="btn btn-primary btn-sm" type="button" @click="openAdd">
          <AppIcon name="plus" :size="15" />新增数据源
        </button>
      </div>

      <div v-if="loading" class="loading-line">
        <span class="spinner"></span>加载中…
      </div>

      <!-- 空态分两种：真的没有数据 vs 加载失败。
           都写"还没有数据源"的话，服务挂了用户会以为自己的数据被清空了 -->
      <AppEmpty
        v-else-if="!rows.length"
        icon="database"
        :title="loadFailed ? '数据源加载失败' : '还没有数据源'"
        :desc="loadFailed ? '可能是网络或服务端问题，可以先重试一次。' : '添加一个数据库连接后，才能对它执行查询、导出和写操作审批。'"
      >
        <button v-if="loadFailed" class="btn" type="button" @click="loadPage">
          <AppIcon name="refresh" :size="15" />重新加载
        </button>
        <button v-else class="btn btn-primary" type="button" @click="openAdd">
          <AppIcon name="plus" :size="15" />新增数据源
        </button>
      </AppEmpty>

      <template v-else>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>名称</th>
                <th>类型</th>
                <th>host:port</th>
                <th>库名</th>
                <th>账号</th>
                <th>状态</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td>
                  <div class="strong">{{ row.name }}</div>
                  <div class="tiny muted-2">#{{ row.id }}</div>
                </td>
                <td><span class="tag">{{ row.type }}</span></td>
                <td class="mono nowrap">{{ row.host }}:{{ row.port }}</td>
                <td>{{ row.databaseName || '—' }}</td>
                <td class="mono">{{ row.username }}</td>
                <td><StatusTag :value="row.status" kind="source" /></td>
                <td class="nowrap" :title="fmtTime(row.createdAt)">
                  <div class="small">{{ fmtRelative(row.createdAt) }}</div>
                  <div class="tiny muted-2 hide-sm">{{ fmtTime(row.createdAt) }}</div>
                </td>
                <td>
                  <div class="actions">
                    <button class="btn btn-sm" type="button" :disabled="busyId === row.id" @click="openTest(row)">
                      <AppIcon name="bolt" :size="14" />测试
                    </button>
                    <button class="btn btn-sm" type="button" :disabled="busyId === row.id" @click="openEdit(row)">
                      <AppIcon name="review" :size="14" />编辑
                    </button>
                    <button
                      class="btn btn-sm" :class="row.status === 1 ? 'btn-danger' : 'btn-ok'" type="button"
                      :disabled="busyId === row.id" @click="handleToggle(row)"
                    >
                      {{ row.status === 1 ? '停用' : '启用' }}
                    </button>
                    <button class="btn btn-sm btn-danger" type="button" :disabled="busyId === row.id" @click="handleDelete(row)">
                      <AppIcon name="trash" :size="14" />删除
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
            <caption>密码以加密形式入库，任何出参都不会把它下发到前端；因此"测试连接"和"保存"都需要在现场填写密码。</caption>
          </table>
        </div>
        <AppPager v-model:current="current" :total="total" :size="size" :disabled="loading" />
      </template>
    </div>

    <Teleport to="body">
      <div v-if="modalOpen" class="mask center" @click.self="closeModal">
        <div class="modal modal-wide">
          <div class="modal-head">
            <AppIcon :name="isEditing ? 'database' : 'plus'" :size="17" />
            <h2>{{ modalTitle }}</h2>
            <span class="spacer"></span>
            <button class="icon-btn" type="button" title="关闭" @click="closeModal">
              <AppIcon name="close" :size="16" />
            </button>
          </div>

          <div class="modal-body">
            <div v-if="testHint && !form.password" class="alert alert-info">
              <AppIcon name="info" :size="16" />
              <span>测试连接需要数据库密码：服务端从不下发已保存的密码（这是有意的），请先填上密码再点「测试连接」。</span>
            </div>

            <!-- 约定的语义是「编辑时密码留空 = 不修改」，但后端 DbSourceDTO.password 带 @NotBlank，
                 更新接口同样要求必填。不说清楚的话，用户会照着提示留空、然后收到一句看不懂的报错。
                 testHint 时上面那条 alert-info 已经把"要填密码"讲清楚了，不重复堆两条提示 -->
            <div v-if="isEditing && !form.password && !testHint" class="alert alert-warn">
              <AppIcon name="warn" :size="16" />
              <span>
                编辑时密码留空表示<strong>不修改密码</strong>；但当前后端更新接口
                （<code>DbSourceDTO.password</code> 带 <code>@NotBlank</code>）要求密码必填 ——
                密码留空时无法保存，请把密码一起填上。
              </span>
            </div>

            <div class="form-row">
              <div class="field">
                <label for="ds-name">名称<span class="req">*</span></label>
                <input id="ds-name" v-model="form.name" class="input" maxlength="50" placeholder="例如：测试库" />
                <div class="tip">同一个账号下名称不能重复。</div>
              </div>
              <div class="field">
                <label for="ds-type">类型<span class="req">*</span></label>
                <select id="ds-type" v-model="form.type" class="select" @change="applyDefaultPort">
                  <option v-for="d in dialects" :key="d.value" :value="d.value">
                    {{ d.label }}（默认端口 {{ d.defaultPort }}）
                  </option>
                </select>
                <div class="tip">列表由后端下发，切换类型会自动带出默认端口。</div>
              </div>
            </div>

            <div class="form-row">
              <div class="field">
                <label for="ds-host">主机<span class="req">*</span></label>
                <input id="ds-host" v-model="form.host" class="input" placeholder="localhost 或 10.0.0.12" />
              </div>
              <div class="field">
                <label for="ds-port">端口<span class="req">*</span></label>
                <input id="ds-port" v-model="form.port" class="input" type="number" min="1" max="65535" />
              </div>
              <div class="field">
                <label for="ds-db">库名</label>
                <input id="ds-db" v-model="form.databaseName" class="input" placeholder="可留空" />
              </div>
            </div>

            <div class="form-row">
              <div class="field">
                <label for="ds-user">账号<span class="req">*</span></label>
                <input id="ds-user" v-model="form.username" class="input" autocomplete="off" />
              </div>
              <div class="field">
                <label for="ds-pass">密码<span v-if="!isEditing" class="req">*</span></label>
                <input
                  id="ds-pass" v-model="form.password" class="input" type="password"
                  autocomplete="new-password" :placeholder="isEditing ? '留空表示不修改' : '数据库密码'"
                />
                <div class="tip">
                  {{ isEditing
                    ? '留空表示不修改密码（服务端不会下发原密码，这里没法回填）。'
                    : '保存前会加密入库；任何接口都不会把它下发到前端。' }}
                </div>
              </div>
            </div>

            <div v-if="testResult" class="alert" :class="testResult.ok ? 'alert-ok' : 'alert-danger'">
              <AppIcon :name="testResult.ok ? 'check' : 'warn'" :size="16" />
              <span class="break">{{ testResult.message }}</span>
            </div>
          </div>

          <div class="modal-foot">
            <span class="tiny muted-2 hide-sm">测试连接会真的去连一次数据库，有频率限制</span>
            <span class="spacer"></span>
            <button class="btn" type="button" :disabled="testing || saving" @click="handleTest">
              <span v-if="testing" class="spinner"></span>
              <AppIcon v-else name="bolt" :size="15" />{{ testing ? '测试中…' : '测试连接' }}
            </button>
            <button class="btn btn-primary" type="button" :disabled="saving || testing" @click="handleSave">
              <span v-if="saving" class="spinner"></span>{{ saving ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
/* 弹窗里字段多，默认 560px 会把 form-row 挤成一层一个，看着很碎 */
.modal-wide { width: min(700px, 94vw); }

/* .select 是 width:100%，放在 card-head 里会撑满一整行 */
.card-head select.page-size { width: 96px; flex: 0 0 96px; }

/* 主按钮上的 spinner 默认是浅灰环 + 品牌色头，在品牌色底上几乎看不见 */
.btn-primary .spinner {
  border-color: var(--surface);
  border-top-color: transparent;
}
</style>
