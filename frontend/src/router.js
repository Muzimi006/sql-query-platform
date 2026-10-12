import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn } from './auth'

// 路由懒加载：() => import(...) 让每个页面单独打包，首屏只加载当前页。
// meta.title 会被 App.vue 的顶栏读去当页面标题 —— 标题只维护这一处。
const routes = [
  { path: '/', redirect: { name: 'query' } },
  {
    path: '/login',
    name: 'login',
    component: () => import('./views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  { path: '/query', name: 'query', component: () => import('./views/QueryView.vue'), meta: { title: '查询工作台' } },
  { path: '/history', name: 'history', component: () => import('./views/HistoryView.vue'), meta: { title: '查询历史' } },
  { path: '/favorite', name: 'favorite', component: () => import('./views/FavoriteView.vue'), meta: { title: '收藏的 SQL' } },
  { path: '/review', name: 'review', component: () => import('./views/ReviewView.vue'), meta: { title: '审核工单' } },
  { path: '/review/:id', name: 'review-detail', component: () => import('./views/ReviewView.vue'), meta: { title: '工单详情' } },
  { path: '/nl2sql', name: 'nl2sql', component: () => import('./views/Nl2SqlView.vue'), meta: { title: '自然语言生成 SQL' } },
  { path: '/datasource', name: 'datasource', component: () => import('./views/DataSourceView.vue'), meta: { title: '数据源管理' } },
  { path: '/export', name: 'export', component: () => import('./views/ExportView.vue'), meta: { title: '导出任务' } },
  // 兜底：手输错地址不要停在白屏上
  { path: '/:pathMatch(.*)*', redirect: { name: 'query' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  // 切页面回到顶部，否则从长列表点进详情会停在半空中
  scrollBehavior() { return { top: 0 } }
})

// 全局前置守卫：返回 true 放行，返回一个路由对象表示重定向。
// ⚠️ 这里只做"体验层"的拦截 —— 真正的鉴权在后端（JwtInterceptor + 服务层按 userId 过滤）。
//    前端守卫绕过去也拿不到数据，所以它挡的是"误操作"，不是"攻击者"。
router.beforeEach((to) => {
  if (!to.meta.public && !isLoggedIn()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && isLoggedIn()) {
    return { name: 'query' }
  }
  return true
})

export default router