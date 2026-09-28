# 前端（Vue 3 + Vite）

SQL 查询与审核平台的前端，提供登录、数据源管理、SQL 查询工作台三个页面。

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

## 目录

```text
src/
├── main.js        应用入口：创建实例、装路由、挂载
├── App.vue        根组件：顶栏 + <RouterView>
├── router.js      路由表 + 登录守卫
├── auth.js        登录态的本地存储（token / user）
├── api.js         axios 实例 + 请求/响应拦截器 + 接口分组
├── style.css      全局样式
└── views/
    ├── LoginView.vue         登录
    ├── DataSourceView.vue    数据源增删改查 + 连接测试
    └── QueryView.vue         SQL 工作台
```

## 说明

- 后端统一返回 `{ code, message, data }`，**业务失败时 HTTP 状态码仍是 200**，所以拆包和判错放在响应拦截器里统一处理。
- 拦截器返回 401 时不会经过全局异常处理器，需要在响应拦截器的 `error` 分支里清理本地登录态。
- 数据源密码在传输阶段是明文（HTTP 环境），服务端用 AES/GCM 加密后落库；生产环境应强制 HTTPS。