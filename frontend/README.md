# 前端（Vue 3 + Vite）

SQL 查询与审核平台的 Web 端。它覆盖了后端 30 个接口里的绝大部分，核心是两条线：

- **查询线**：查询工作台 / 查询历史 / 收藏的 SQL
- **变更线**：提交工单 → 审批 → 异步执行 → 留痕（对应后端的审核工单），外加自然语言生成 SQL 入口

## 运行

先启动后端（默认 `http://localhost:8080`），然后：

```bash
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`。

开发期 `vite.config.js` 把 `/api` 代理到 `localhost:8080`，浏览器视角下前后端同源，因此不需要额外的 CORS 配置。

## 构建

```bash
npm run build     # 产物在 dist/
npm run preview   # 本地预览构建产物
```

生产环境把 `dist/` 交给 Nginx 托管，并把 `/api` 反代到后端即可。

## ⭐ 改代码之前先读

**[`前端开发约定.md`](前端开发约定.md)** —— 设计系统（可用类名）、可复用组件、工具函数、后端语义与硬要求。
它存在的理由很实际：前端**刻意零 UI 依赖**（没有 Element Plus、没有图标库），所有样式都集中在 `src/style.css`，
所以「有哪些类名可用、状态标签怎么显示」必须有一个唯一出处，否则同一个按钮迟早出现三种写法。

## 目录

```text
src/
├── main.js        应用入口：创建实例、装路由、挂载
├── App.vue        外壳：侧边栏 + 顶栏 + 主题切换 + 全局 toast / 确认框
├── router.js      路由表 + 登录守卫（meta.title 即顶栏标题）
├── auth.js        登录态的本地存储（token / user，token 放在 ref 里）
├── api.js         axios 实例 + 拦截器 + 按业务分组的接口
├── ui.js          toast / confirmDialog / copyText
├── format.js      时间、数字、状态字典、SQL 摘要
├── style.css      设计系统（CSS 变量 + 全部组件类）
├── components/    AppIcon · AppEmpty · AppPager · AppDrawer · StatusTag
└── views/
    ├── LoginView.vue          登录 / 注册
    ├── QueryView.vue          SQL 工作台（执行、截断提示、收藏、最近查询）
    ├── HistoryView.vue        查询历史（筛选、再执行、收藏、删除）
    ├── FavoriteView.vue       收藏的 SQL
    ├── ReviewView.vue         ⭐ 审核工单：提交 / 我的工单 / 待审批 / 详情与审批
    ├── Nl2SqlView.vue         自然语言生成 SQL（默认关闭，生成的是待审工单）
    ├── DataSourceView.vue     数据源增删改查 + 连接测试
    └── ExportView.vue         异步导出任务（轮询状态 + 下载 CSV）
```

## 说明

- 后端统一返回 `{ code, message, data }`，**业务失败时 HTTP 状态码仍是 200**，所以拆包和判错放在响应拦截器里统一处理。
- 拦截器返回 401 时不会经过全局异常处理器，需要在响应拦截器的 `error` 分支里清理本地登录态。
- 数据源密码在传输阶段是明文（HTTP 环境），服务端用 AES/GCM 加密后落库；生产环境应强制 HTTPS。
- **异步不是"返回 200 就完成了"**：工单批准后要轮询详情看 `EXECUTED / FAILED`，导出任务要轮询列表看 `SUCCESS / FAILED`。
- 前端路由守卫只是**体验层**：真正的鉴权在后端（JWT 拦截器 + 服务层按 userId 过滤）。绕过去也拿不到数据。