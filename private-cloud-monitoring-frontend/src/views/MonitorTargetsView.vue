<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useGlobalRefresh } from '../composables/useGlobalRefresh'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

interface AuthMe {
  username: string
  roles: string[]
  servers: string[]
  admin: boolean
}

interface MonitorTargetView {
  host: string
  role: string
  enabled: boolean
  updatedAt?: string | null
}

interface MonitorTargetChangeView {
  host: string
  role: string
  enabled: boolean
  changedBy: string
  changedAt: string
}

const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'
const loginPath = '/login'

const loading = ref(false)
const loadingChanges = ref(false)
const searchText = ref('')
const targets = ref<MonitorTargetView[]>([])
const changes = ref<MonitorTargetChangeView[]>([])
const changeSearchText = ref('')
const changeFilterHost = ref<string | null>(null)
const isAdmin = ref(false)
const totalTargets = computed(() => targets.value.length)
const enabledTargets = computed(() => targets.value.filter((item) => item.enabled).length)
const serverTargets = computed(() => targets.value.filter((item) => ['host', 'server'].includes((item.role || '').toLowerCase())).length)
const vmTargets = computed(() => targets.value.filter((item) => (item.role || '').toLowerCase() === 'vm').length)
const containerTargets = computed(() => targets.value.filter((item) => (item.role || '').toLowerCase() === 'container').length)
const changeCount = computed(() => changes.value.length)

const filteredTargets = computed(() => {
  const keyword = searchText.value.trim().toLowerCase()
  if (!keyword) return targets.value
  return targets.value.filter((item) =>
    [item.host, item.role].some((field) => String(field || '').toLowerCase().includes(keyword)),
  )
})

async function getApi<T>(path: string, params?: Record<string, string | undefined>): Promise<T> {
  const query = new URLSearchParams()
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value && value.trim()) query.set(key, value)
  })
  const url = `${baseUrl}${path}${query.size ? `?${query.toString()}` : ''}`
  const response = await fetch(url, { headers: authHeaders() })
  if (handleAuthError(response)) {
    throw new Error('未登录或登录已过期')
  }
  const body = (await response.json()) as ApiResponse<T>
  if (!response.ok || body.code !== 200) {
    throw new Error(body?.message || `请求失败: ${response.status}`)
  }
  return body.data
}

async function postApi<T>(path: string, payload?: unknown): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: payload ? JSON.stringify(payload) : undefined,
  })
  if (handleAuthError(response)) {
    throw new Error('未登录或登录已过期')
  }
  const body = (await response.json()) as ApiResponse<T>
  if (!response.ok || body.code !== 200) {
    throw new Error(body?.message || `请求失败: ${response.status}`)
  }
  return body.data
}

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('pc_token')
  if (!token) return {}
  return { Authorization: `Bearer ${token}` }
}

function handleAuthError(response: Response): boolean {
  if (response.status !== 401) return false
  localStorage.removeItem('pc_token')
  localStorage.removeItem('pc_username')
  window.location.href = loginPath
  return true
}

async function loadMe() {
  const me = await getApi<AuthMe>('/api/auth/me')
  isAdmin.value = me.admin
}

async function loadTargets() {
  loading.value = true
  try {
    targets.value = await getApi<MonitorTargetView[]>('/api/monitor-targets', { start: '-7d', end: 'now()' })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载监控对象失败')
  } finally {
    loading.value = false
  }
}

async function loadChanges(host?: string, role?: string) {
  loadingChanges.value = true
  try {
    changes.value = await getApi<MonitorTargetChangeView[]>('/api/monitor-targets/changes', {
      host: host || undefined,
      role: role || undefined,
    })
    changeFilterHost.value = host || null
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载变更记录失败')
  } finally {
    loadingChanges.value = false
  }
}

async function toggleTarget(item: MonitorTargetView) {
  if (!isAdmin.value) return
  const nextEnabled = !item.enabled
  try {
    await postApi('/api/monitor-targets/toggle', {
      host: item.host,
      role: item.role,
      enabled: nextEnabled,
    })
    item.enabled = nextEnabled
    await loadChanges(item.host, item.role)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '更新失败')
  }
}

function roleLabel(role: string) {
  const value = (role || '').toLowerCase()
  if (value === 'host' || value === 'server') return '服务器'
  if (value === 'vm') return '虚拟机'
  if (value === 'container') return '容器'
  return role
}

function roleTagType(role: string): 'primary' | 'warning' | 'success' | 'info' {
  const value = (role || '').toLowerCase()
  if (value === 'host' || value === 'server') return 'primary'
  if (value === 'vm') return 'warning'
  if (value === 'container') return 'success'
  return 'info'
}

const filteredChanges = computed(() => {
  const keyword = changeSearchText.value.trim().toLowerCase()
  if (!keyword) return changes.value
  return changes.value.filter((item) =>
    [item.host, item.role, item.changedBy].some((field) => String(field || '').toLowerCase().includes(keyword)),
  )
})

function formatLocalTime(value?: string) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')
  return `${y}年${m}月${d}日${hh}时${mm}分`
}
onMounted(async () => {
  await loadMe()
  await loadTargets()
  await loadChanges()
})

useGlobalRefresh(async () => {
  if (loading.value || loadingChanges.value) {
    return
  }
  await Promise.all([loadTargets(), loadChanges(changeFilterHost.value || undefined)])
}, { minGapMs: 1200 })
</script>

<template>
  <div class="page">
    <el-card class="toolbar-card panel-card" shadow="hover">
      <div class="toolbar-head">
        <div class="toolbar-title">监控对象管理</div>
        <div class="toolbar-head-right">
          <el-tag size="small" type="info" effect="plain">对象 {{ totalTargets }}</el-tag>
          <el-tag size="small" type="success" effect="plain">启用 {{ enabledTargets }}</el-tag>
          <el-tag size="small" :type="isAdmin ? 'warning' : 'info'" effect="plain">
            {{ isAdmin ? '管理员可切换' : '只读权限' }}
          </el-tag>
        </div>
      </div>
      <div class="toolbar-actions">
        <el-input v-model="searchText" clearable placeholder="搜索监控对象（名称/类型）" class="search-input" />
        <div class="toolbar-buttons">
          <el-button v-if="changeFilterHost" @click="loadChanges()">查看全部变更</el-button>
          <el-button @click="loadTargets">刷新列表</el-button>
        </div>
      </div>
    </el-card>

    <el-row :gutter="14" class="summary-row">
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">监控对象总数</div>
          <div class="kpi-value primary">{{ totalTargets }}</div>
          <div class="kpi-meta">当前可管理目标</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">启用监控</div>
          <div class="kpi-main">
            <span class="kpi-value success">{{ enabledTargets }}</span>
            <span class="kpi-unit">/ {{ totalTargets }}</span>
          </div>
          <div class="kpi-meta">状态开关实时生效</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">类型分布</div>
          <div class="kpi-main stack">
            <el-tag size="small" type="primary" effect="light">服务器 {{ serverTargets }}</el-tag>
            <el-tag size="small" type="warning" effect="light">虚拟机 {{ vmTargets }}</el-tag>
            <el-tag size="small" type="success" effect="light">容器 {{ containerTargets }}</el-tag>
          </div>
          <div class="kpi-meta">按监控对象角色统计</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">变更记录</div>
          <div class="kpi-value">{{ changeCount }}</div>
          <div class="kpi-meta">用于追踪启停操作</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="panel-card table-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <div class="card-title-wrap">
            <span class="card-title">对象列表</span>
            <span class="path-text">支持按目标启停监控并查看记录</span>
          </div>
          <div class="table-head-actions">
            <el-tag size="small" type="info" effect="plain">筛选 {{ filteredTargets.length }}</el-tag>
          </div>
        </div>
      </template>
      <el-table :data="filteredTargets" border stripe class="targets-table" v-loading="loading" empty-text="暂无监控对象">
        <el-table-column prop="host" label="对象名称" min-width="220">
          <template #default="scope">
            <div class="host-cell">
              <el-avatar :size="26" class="host-avatar">{{ scope.row.host.slice(0, 1).toUpperCase() }}</el-avatar>
              <span>{{ scope.row.host }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="role" label="对象类型" width="120" align="center">
          <template #default="scope">
            <el-tag size="small" :type="roleTagType(scope.row.role)" effect="light">{{ roleLabel(scope.row.role) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用监控" width="140" align="center">
          <template #default="scope">
            <el-switch
              :model-value="scope.row.enabled"
              :disabled="!isAdmin"
              @change="() => toggleTarget(scope.row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center">
          <template #default="scope">
            <el-button size="small" type="primary" plain @click="loadChanges(scope.row.host, scope.row.role)">查看记录</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="panel-card table-card changes-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <div class="card-title-wrap">
            <span class="card-title">变更记录</span>
            <span class="path-text">{{ changeFilterHost ? `当前过滤：${changeFilterHost}` : '当前查看全部记录' }}</span>
          </div>
          <div class="card-actions">
            <el-button v-if="changeFilterHost" size="small" @click="loadChanges()">查看全部</el-button>
            <el-input v-model="changeSearchText" clearable placeholder="搜索变更记录" class="change-search" />
          </div>
        </div>
      </template>
      <el-table :data="filteredChanges" border stripe class="changes-table" v-loading="loadingChanges" empty-text="暂无变更记录">
        <el-table-column prop="host" label="对象名称" min-width="200" />
        <el-table-column prop="role" label="对象类型" width="120" align="center">
          <template #default="scope">
            <el-tag size="small" :type="roleTagType(scope.row.role)" effect="light">{{ roleLabel(scope.row.role) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="操作" width="120" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.enabled ? 'success' : 'info'" effect="light">
              {{ scope.row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="changedBy" label="操作人" width="140" />
        <el-table-column prop="changedAt" label="操作时间" width="180">
          <template #default="scope">
            {{ formatLocalTime(scope.row.changedAt) }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 8px 4px 4px;
}

.panel-card {
  border-radius: 18px;
  border: 1px solid var(--line);
  background: linear-gradient(145deg, var(--glass-1), var(--glass-2));
  backdrop-filter: blur(8px);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.88),
    0 12px 28px rgba(47, 68, 111, 0.14);
  overflow: hidden;
  animation: riseIn 0.55s ease both;
}

.panel-card :deep(.el-card__header) {
  border-bottom: 1px solid var(--line-soft);
  background: rgba(244, 248, 255, 0.84);
}

.panel-card :deep(.el-card__body) {
  background: transparent;
}

.toolbar-card :deep(.el-card__body) {
  padding: 18px 20px 14px;
}

.toolbar-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.toolbar-title {
  font-size: 18px;
  font-weight: 700;
  color: #1b2b4d;
}

.toolbar-head-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar-actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.toolbar-buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}

.search-input {
  width: 320px;
}

.summary-row {
  margin-top: 2px;
}

.summary-col {
  display: flex;
}

.summary-col .stat-card {
  width: 100%;
}

.stat-card :deep(.el-card__body) {
  padding: 16px 16px 14px;
}

.kpi-label {
  color: #6f809f;
  font-size: 12px;
}

.kpi-main {
  margin-top: 8px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.kpi-main.stack {
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.kpi-value {
  margin-top: 8px;
  font-size: 30px;
  font-weight: 700;
  color: #1d3158;
  line-height: 1.1;
}

.kpi-value.primary {
  color: #2563eb;
}

.kpi-value.success {
  color: #16a34a;
}

.kpi-unit {
  color: #6e7f9f;
  font-size: 13px;
}

.kpi-meta {
  margin-top: 8px;
  font-size: 12px;
  color: #7284a6;
}

.changes-card {
  margin-top: 2px;
}

.table-card :deep(.el-card__body) {
  padding: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-title {
  font-size: 15px;
  font-weight: 700;
  color: #233760;
}

.path-text {
  color: #6f7f9e;
  font-size: 12px;
}

.table-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.change-search {
  width: 220px;
}

.host-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.host-avatar {
  background: linear-gradient(135deg, #0ea5e9, #22c55e);
  color: #0b1120;
  font-weight: 700;
}

.targets-table :deep(.el-table__header-wrapper th),
.changes-table :deep(.el-table__header-wrapper th) {
  background: #f4f8ff;
}

.targets-table :deep(.el-table__row td),
.changes-table :deep(.el-table__row td) {
  background: rgba(255, 255, 255, 0.68);
}

@keyframes riseIn {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 1024px) {
  .toolbar-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .toolbar-head-right {
    width: 100%;
    flex-wrap: wrap;
  }

  .toolbar-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .search-input {
    width: 100%;
  }
}

@media (max-width: 760px) {
  .page {
    gap: 14px;
    padding: 4px 2px 2px;
  }

  .toolbar-card :deep(.el-card__body) {
    padding: 16px 14px 12px;
  }

  .card-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .card-title-wrap {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }
}
</style>
