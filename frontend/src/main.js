import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './style.css'

// createApp 创建应用实例；use(router) 装上路由插件；mount 把根组件挂到 #app
createApp(App).use(router).mount('#app')
