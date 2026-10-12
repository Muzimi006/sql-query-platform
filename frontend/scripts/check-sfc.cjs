/* 不构建、不起服务，只做一次 SFC 静态校验：
 *
 *   node scripts/check-sfc.cjs                 # 检查 src 下所有 .vue
 *   node scripts/check-sfc.cjs src/views/X.vue  # 只检查指定文件（可给多个）
 *
 * 它能抓到三类问题 —— 都是并行改页面时最容易出的：
 *   1. <script setup> / <template> 的语法错误；
 *   2. 模板里写错名字的变量（会被编译成 _ctx.xxx；正常应该全部命中 setup 绑定）；
 *   3. 忘了 import 的组件（会被编译成 resolveComponent("Xxx")）。
 *
 * ⚠️ RouterLink / RouterView 由 vue-router 全局注册，出现在第 3 类里是正常的，白名单里放行。
 *
 * 为什么需要它：`npm run build` 也能发现这些问题，但它**要跑完整条依赖图** ——
 * 多个人同时改页面时，别人的半成品会让构建失败，于是谁都验不了自己的文件。
 * 这个脚本只解析你给的那几个文件，互不干扰。
 *
 * 退出码：0 干净；1 有问题（可用于 CI / 提交前钩子）。
 */
const fs = require('fs')
const path = require('path')

// 从 frontend/ 目录解析依赖，这样在仓库任何位置调用都能找到
const sfc = require(path.join(__dirname, '..', 'node_modules', '@vue', 'compiler-sfc'))

const GLOBALLY_REGISTERED = ['RouterLink', 'RouterView']

// 模板里的内置属性（$slots / $attrs / $emit …）：它们不是 setup 绑定，
// 但会被编译成 _ctx.$slots —— 不白名单的话每个用插槽的组件都会"报错"。
// ⚠️ 这类白名单要谨慎：加错一个真变量名，就等于把拼写检查关掉了。
const BUILTIN_TEMPLATE_PROPS = [
  '$slots', '$attrs', '$refs', '$emit', '$props', '$el', '$parent', '$root',
  '$data', '$options', '$forceUpdate', '$nextTick', '$watch'
]

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (e.name.endsWith('.vue')) out.push(p)
  }
  return out
}

function checkOne(file) {
  const src = fs.readFileSync(file, 'utf8')
  const { descriptor, errors } = sfc.parse(src, { filename: file })
  if (errors.length) {
    console.log(file)
    console.log('  PARSE ERRORS:', errors.map((e) => e.message))
    return 1
  }

  let bindings = {}
  if (descriptor.scriptSetup || descriptor.script) {
    try {
      bindings = sfc.compileScript(descriptor, { id: 'chk' }).bindings
    } catch (e) {
      console.log(file)
      console.log('  SCRIPT COMPILE FAILED:', e.message)
      return 1
    }
  }

  const tpl = sfc.compileTemplate({
    source: descriptor.template ? descriptor.template.content : '',
    filename: file,
    id: 'chk',
    compilerOptions: { bindingMetadata: bindings }
  })
  const tplErrors = tpl.errors.map((e) => e.message || String(e))
  // _ctx.xxx = 模板里用了、但 setup 没暴露的名字（拼错或忘了定义）
  const unknown = [...new Set([...tpl.code.matchAll(/_ctx\.([A-Za-z_$][\w$]*)/g)].map((m) => m[1]))]
    .filter((n) => !BUILTIN_TEMPLATE_PROPS.includes(n))
  const comps = [...new Set([...tpl.code.matchAll(/_resolveComponent\("([^"]+)"\)/g)].map((m) => m[1]))]
    .filter((c) => !GLOBALLY_REGISTERED.includes(c))

  const bad = tplErrors.length + unknown.length + comps.length
  console.log(file)
  console.log('  template errors :', tplErrors.length ? tplErrors : 'none')
  console.log('  unknown names   :', unknown.length ? unknown : 'none')
  console.log('  unresolved comps:', comps.length ? comps : 'none')
  return bad ? 1 : 0
}

const args = process.argv.slice(2)
const files = args.length ? args : walk(path.join(__dirname, '..', 'src'))
let failed = 0
for (const f of files) failed += checkOne(f)
console.log('')
console.log(failed ? '✗ ' + failed + ' 个文件有问题' : '✓ ' + files.length + ' 个文件全部通过')
process.exit(failed ? 1 : 0)