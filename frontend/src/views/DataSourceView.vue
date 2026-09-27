<script setup>
import { onMounted, ref } from 'vue'
import { dataSourceApi } from '../api'

const list = ref([])
const error = ref('')
const message = ref('')
const loading = ref(false)

// 新增数据源的表单。字段名必须和后端 DbSourceDTO 对齐
const form = ref({
  name: '',
  type: 'MySQL',
  host: 'localhost',
  port: 3306,
  databaseName: '',
  username: '',
  password: ''
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    list.value = await dataSourceApi.list()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function handleAdd() {
  error.value = ''
  message.value = ''
  try {
    await dataSourceApi.add({ ...form.value, port: Number(form.value.port) })
    message.value = `数据源「${form.value.name}」已添加`
    form.value.name = ''
    await load()
  } catch (e) {
    error.value = e.message
  }
}

async function handleTest() {
  error.value = ''
  message.value = ''
  try {
    const ok = await dataSourceApi.test({ ...form.value, port: Number(form.value.port) })
    message.value = ok ? '连接成功' : '连接失败'
  } catch (e) {
    error.value = e.message
  }
}

async function handleToggle(row) {
  error.value = ''
  try {
    await dataSourceApi.changeStatus(row.id, row.status === 1 ? 0 : 1)
    await load()
  } catch (e) {
    error.value = e.message
  }
}

async function handleDelete(row) {
  if (!window.confirm(`确定删除数据源「${row.name}」吗？`)) return
  error.value = ''
  try {
    await dataSourceApi.remove(row.id)
    await load()
  } catch (e) {
    error.value = e.message
  }
}

// onMounted：组件挂载完成后执行一次，等价于「页面加载时拉数据」
onMounted(load)
</script>

<template>
  <div class="page">
    <div class="card">
      <h2>新增数据源</h2>
      <div class="row">
        <label>名称<input v-model="form.name" placeholder="例如：测试库" /></label>
        <label>类型
          <select v-model="form.type">
            <option value="MySQL">MySQL</option>
            <option value="openGauss">openGauss（B 库 / MySQL 协议口）</option>
          </select>
        </label>
        <label>主机<input v-model="form.host" /></label>
        <label>端口<input v-model.number="form.port" type="number" /></label>
      </div>
      <div class="row">
        <label>库名<input v-model="form.databaseName" placeholder="可留空" /></label>
        <label>用户名<input v-model="form.username" /></label>
        <label>密码<input v-model="form.password" type="password" /></label>
      </div>
      <div class="row">
        <button type="button" class="ghost" @click="handleTest">测试连接</button>
        <button type="button" @click="handleAdd">保存</button>
      </div>
      <p v-if="error" class="error">{{ error }}</p>
      <p v-if="message" class="muted">{{ message }}</p>
    </div>

    <div class="card">
      <h2>我的数据源</h2>
      <p v-if="loading" class="muted">加载中…</p>
      <table v-else>
        <thead>
          <tr>
            <th>ID</th><th>名称</th><th>类型</th><th>地址</th><th>状态</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <!-- v-for 必须带 key，Vue 用它复用 DOM、判断哪些节点需要重建 -->
          <tr v-for="row in list" :key="row.id">
            <td>{{ row.id }}</td>
            <td>{{ row.name }}</td>
            <td>{{ row.type }}</td>
            <td>{{ row.host }}:{{ row.port }}/{{ row.databaseName || '-' }}</td>
            <td>{{ row.status === 1 ? '启用' : '停用' }}</td>
            <td>
              <button class="ghost" @click="handleToggle(row)">
                {{ row.status === 1 ? '停用' : '启用' }}
              </button>
              <button class="danger" @click="handleDelete(row)">删除</button>
            </td>
          </tr>
          <tr v-if="!list.length">
            <td colspan="6" class="muted">还没有数据源，先在上面添加一个</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
