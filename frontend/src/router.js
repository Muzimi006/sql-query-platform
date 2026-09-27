import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn } from './auth'

// 路由懒加载：() => import(...) 让每个页面单独打包，首屏只加载当前页
const routes = [
  { path: '/', redirect: { name: 'query' } },
  {
    path: '/login',
    name: 'login',
    component: () => import('./views/LoginView.vue'),
    meta: { public: true }
  },
  { path: '/query', name: 'query', component: () => import('./views/QueryView.vue') },
  { path: '/datasource', name: 'datasource', component: () => import('./views/DataSourceView.vue') }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局前置守卫：返回 true 放行，返回一个路由对象表示重定向
router.beforeEach((to) => {
  if (!to.meta.public && !isLoggedIn()) {
    return { name: 'login' }
  }
  if (to.name === 'login' && isLoggedIn()) {
    return { name: 'query' }
  }
  return true
})

export default router
