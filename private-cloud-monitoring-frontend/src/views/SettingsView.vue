<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { GLOBAL_REFRESH_CONFIG_EVENT } from '../composables/useGlobalRefresh'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

interface TopologyNode {
  host: string
  role: string
}

const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'
const settingsKey = 'pc-monitor-settings-v1'
const loginPath = '/login'
const loading = ref(false)

const form = reactive({
  refreshIntervalSec: 30,
  emailNotify: true,
  webhookUrl: '',
})

const monitorTargets = ref<{ host: string; role: string; enabled: boolean }[]>([])

async function getApi<T>(path: string, params?: Record<string, string | undefined>): Promise<T> {
  const query = new URLSearchParams()
  Object.entries(params ?? {}).forEach(([k, v]) => {
    if (v && v.trim()) query.set(k, v)
  })
  const url = `${baseUrl}${path}${query.size ? `?${query.toString()}` : ''}`
  const response = await fetch(url, { headers: authHeaders() })
  if (handleAuthError(response)) {
    throw new Error('未登录或登录已过期')
  }
  const body = (await response.json()) as ApiResponse<T>
  if (!response.ok || body.code !== 200) throw new Error(body?.message || `请求失败: ${response.status}`)
  return body.data
}

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('pc_token')
  if (!token) {
    return {}
  }
  return { Authorization: `Bearer ${token}` }
}

function handleAuthError(response: Response): boolean {
  if (response.status !== 401) {
    return false
  }
  localStorage.removeItem('pc_token')
  localStorage.removeItem('pc_username')
  window.location.href = loginPath
  return true
}

function loadLocalSettings() {
  const raw = localStorage.getItem(settingsKey)
  if (!raw) return
  try {
    const data = JSON.parse(raw) as {
      refreshIntervalSec?: number
      emailNotify?: boolean
      webhookUrl?: string
      targets?: { host: string; enabled: boolean }[]
    }
    form.refreshIntervalSec = data.refreshIntervalSec || form.refreshIntervalSec
    form.emailNotify = data.emailNotify ?? form.emailNotify
    form.webhookUrl = data.webhookUrl || form.webhookUrl

    if (data.targets?.length && monitorTargets.value.length) {
      const map = new Map(data.targets.map((item) => [item.host, item.enabled]))
      monitorTargets.value = monitorTargets.value.map((item) => ({ ...item, enabled: map.get(item.host) ?? item.enabled }))
    }
  } catch {
    // ignore
  }
}

function saveSettings() {
  localStorage.setItem(
    settingsKey,
    JSON.stringify({
      ...form,
      targets: monitorTargets.value.map((item) => ({ host: item.host, enabled: item.enabled })),
    }),
  )
  window.dispatchEvent(new CustomEvent(GLOBAL_REFRESH_CONFIG_EVENT))
  ElMessage.success('系统设置已保存')
}

async function loadTargets() {
  loading.value = true
  try {
    const nodes = await getApi<TopologyNode[]>('/api/hosts/topology', { start: '-7d', end: 'now()' })
    monitorTargets.value = nodes.map((item) => ({
      host: item.host,
      role: item.role,
      enabled: true,
    }))
    loadLocalSettings()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载监控对象失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadTargets)
</script>

<template>
  <div class="page" v-loading="loading">
    <el-card>
      <el-tabs model-value="basic">
        <el-tab-pane label="基础配置" name="basic">
          <el-form label-width="160px" class="form">
            <el-form-item label="刷新间隔（秒）">
              <el-input-number v-model="form.refreshIntervalSec" :min="5" :max="300" />
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="监控对象" name="targets">
          <el-table :data="monitorTargets" border>
            <el-table-column prop="host" label="对象名称" min-width="220" />
            <el-table-column prop="role" label="对象类型" width="120" />
            <el-table-column label="启用监控" width="120">
              <template #default="scope">
                <el-switch v-model="scope.row.enabled" />
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="通知设置" name="notify">
          <el-form label-width="160px" class="form">
            <el-form-item label="邮件通知">
              <el-switch v-model="form.emailNotify" />
            </el-form-item>
            <el-form-item label="Webhook 地址">
              <el-input v-model="form.webhookUrl" placeholder="企业微信/钉钉 webhook" />
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <div class="actions">
        <el-button type="primary" @click="saveSettings">保存设置</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form {
  max-width: 680px;
  padding-top: 12px;
}

.actions {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
