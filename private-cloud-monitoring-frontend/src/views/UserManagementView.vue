<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useGlobalRefresh } from '../composables/useGlobalRefresh'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

interface UserRow {
  id: number
  username: string
  enabled: boolean
  createdAt: string
  roles: string[]
  serverHosts: string[]
}

interface RoleRow {
  id: number
  name: string
}

interface TopologyNode {
  host: string
  role: string
}

const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'
const loginPath = '/login'

const loading = ref(false)
const users = ref<UserRow[]>([])
const roles = ref<RoleRow[]>([])
const servers = ref<string[]>([])
const isAdmin = ref(false)
const currentUsername = ref('')

const createVisible = ref(false)
const editVisible = ref(false)
const passwordVisible = ref(false)

const createForm = reactive({
  username: '',
  password: '',
  roles: ['USER'] as string[],
  serverHosts: [] as string[],
})

const editForm = reactive({
  id: 0,
  username: '',
  enabled: true,
  roles: [] as string[],
  serverHosts: [] as string[],
})

const passwordForm = reactive({
  id: 0,
  username: '',
  password: '',
})

const roleOptions = computed(() => roles.value.map((item) => item.name))

const createIsAdmin = computed(() => createForm.roles.includes('ADMIN'))
const editIsAdmin = computed(() => editForm.roles.includes('ADMIN'))
const totalUsers = computed(() => users.value.length)
const enabledUsers = computed(() => users.value.filter((item) => item.enabled).length)
const adminUsers = computed(() => users.value.filter((item) => item.roles.includes('ADMIN')).length)
const restrictedUsers = computed(() => users.value.filter((item) => !item.roles.includes('ADMIN')).length)
const serverCoverage = computed(() => {
  const hostSet = new Set<string>()
  users.value.forEach((item) => {
    if (item.roles.includes('ADMIN')) return
    item.serverHosts.forEach((host) => hostSet.add(host))
  })
  return hostSet.size
})

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

async function postApi<T>(path: string, payload: unknown): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(payload),
  })
  if (handleAuthError(response)) {
    throw new Error('未登录或登录已过期')
  }
  const body = (await response.json()) as ApiResponse<T>
  if (!response.ok || body.code !== 200) throw new Error(body?.message || `请求失败: ${response.status}`)
  return body.data
}

async function putApi<T>(path: string, payload: unknown): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(payload),
  })
  if (handleAuthError(response)) {
    throw new Error('未登录或登录已过期')
  }
  const body = (await response.json()) as ApiResponse<T>
  if (!response.ok || body.code !== 200) throw new Error(body?.message || `请求失败: ${response.status}`)
  return body.data
}

async function deleteApi<T>(path: string): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, { method: 'DELETE', headers: authHeaders() })
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

async function loadUsers() {
  users.value = await getApi<UserRow[]>('/api/users')
}

async function loadMe() {
  const me = await getApi<{ username: string; roles: string[]; admin: boolean }>('/api/auth/me')
  isAdmin.value = !!me?.admin
  currentUsername.value = me?.username || ''
}

async function loadRoles() {
  roles.value = await getApi<RoleRow[]>('/api/roles')
}

async function loadServers() {
  const nodes = await getApi<TopologyNode[]>('/api/hosts/topology', { start: '-7d', end: 'now()' })
  servers.value = nodes
    .filter((item) => ['host', 'server'].includes((item.role || '').toLowerCase()))
    .map((item) => item.host)
}

async function refreshAll() {
  loading.value = true
  try {
    await Promise.all([loadMe(), loadUsers(), loadRoles(), loadServers()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  if (!isAdmin.value) {
    ElMessage.warning('仅管理员可新增用户')
    return
  }
  createForm.username = ''
  createForm.password = ''
  createForm.roles = ['USER']
  createForm.serverHosts = []
  createVisible.value = true
}

function openEdit(row: UserRow) {
  if (!isAdmin.value) {
    ElMessage.warning('仅管理员可编辑用户')
    return
  }
  editForm.id = row.id
  editForm.username = row.username
  editForm.enabled = row.enabled
  editForm.roles = [...row.roles]
  editForm.serverHosts = [...row.serverHosts]
  editVisible.value = true
}

function openResetPassword(row: UserRow) {
  passwordForm.id = row.id
  passwordForm.username = row.username
  passwordForm.password = ''
  passwordVisible.value = true
}

async function createUser() {
  if (!createForm.username || !createForm.password) {
    ElMessage.warning('请填写用户名和密码')
    return
  }
  const payload = {
    username: createForm.username,
    password: createForm.password,
    roles: createForm.roles,
    serverHosts: createIsAdmin.value ? [] : createForm.serverHosts,
  }
  await postApi('/api/users', payload)
  ElMessage.success('用户已创建')
  createVisible.value = false
  await loadUsers()
}

async function updateUser() {
  const payload = {
    roles: editForm.roles,
    enabled: editForm.enabled,
    serverHosts: editIsAdmin.value ? [] : editForm.serverHosts,
  }
  await putApi(`/api/users/${editForm.id}`, payload)
  ElMessage.success('用户已更新')
  editVisible.value = false
  await loadUsers()
}

async function resetPassword() {
  if (!passwordForm.password) {
    ElMessage.warning('请输入新密码')
    return
  }
  await putApi(`/api/users/${passwordForm.id}/password`, { password: passwordForm.password })
  ElMessage.success('密码已更新')
  passwordVisible.value = false
}

async function removeUser(row: UserRow) {
  if (!isAdmin.value) {
    ElMessage.warning('仅管理员可删除用户')
    return
  }
  await ElMessageBox.confirm(`确认删除用户 ${row.username}？`, '提示', { type: 'warning' })
  await deleteApi(`/api/users/${row.id}`)
  ElMessage.success('用户已删除')
  await loadUsers()
}

function formatDateTime(value?: string | null): string {
  if (!value) {
    return '--'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')
  const ss = String(date.getSeconds()).padStart(2, '0')
  return `${y}-${m}-${d} ${hh}:${mm}:${ss}`
}

function roleTagType(role: string): '' | 'success' | 'warning' {
  if (role === 'ADMIN') {
    return 'warning'
  }
  if (role === 'USER') {
    return 'success'
  }
  return ''
}

onMounted(refreshAll)

useGlobalRefresh(async () => {
  if (loading.value || createVisible.value || editVisible.value || passwordVisible.value) {
    return
  }
  await refreshAll()
}, { minGapMs: 1200 })
</script>

<template>
  <div class="page" v-loading="loading">
    <el-card class="toolbar-card panel-card" shadow="hover">
      <div class="toolbar-head">
        <div class="toolbar-title">用户管理</div>
        <div class="toolbar-head-right">
          <el-tag size="small" type="info" effect="plain">当前用户 {{ currentUsername || '--' }}</el-tag>
          <el-button @click="refreshAll">刷新</el-button>
          <el-button v-if="isAdmin" type="primary" @click="openCreate">新增用户</el-button>
        </div>
      </div>
    </el-card>

    <el-row v-if="isAdmin" :gutter="14" class="summary-row">
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">账号总数</div>
          <div class="kpi-value primary">{{ totalUsers }}</div>
          <div class="kpi-meta">当前系统已创建用户</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">启用账号</div>
          <div class="kpi-value success">{{ enabledUsers }}</div>
          <div class="kpi-meta">状态为启用的用户</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">管理员账号</div>
          <div class="kpi-value warning">{{ adminUsers }}</div>
          <div class="kpi-meta">拥有全部服务器权限</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">受限账号/覆盖服务器</div>
          <div class="kpi-main">
            <span class="kpi-value">{{ restrictedUsers }}</span>
            <span class="kpi-unit">/ {{ serverCoverage }}</span>
          </div>
          <div class="kpi-meta">非管理员账号数量与分配主机数</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="panel-card table-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <div class="card-title-wrap">
            <span class="card-title">用户列表</span>
            <span class="path-text">权限与监控主机分配</span>
          </div>
          <div v-if="isAdmin" class="table-head-actions">
            <el-tag size="small" type="info" effect="plain">总用户 {{ totalUsers }}</el-tag>
            <el-tag size="small" type="success" effect="plain">启用 {{ enabledUsers }}</el-tag>
          </div>
        </div>
      </template>

      <el-table :data="users" border stripe class="users-table" empty-text="暂无用户数据">
        <el-table-column prop="username" label="账号" min-width="160">
          <template #default="scope">
            <div class="username-cell">
              <el-avatar :size="28" class="cell-avatar">{{ scope.row.username?.slice(0, 1).toUpperCase() }}</el-avatar>
              <span>{{ scope.row.username }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="roles" label="角色" min-width="180">
          <template #default="scope">
            <el-tag
              v-for="role in scope.row.roles"
              :key="role"
              size="small"
              :type="roleTagType(role)"
              effect="light"
              class="role-tag"
            >
              {{ role }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="状态" width="100">
          <template #default="scope">
            <el-tag :type="scope.row.enabled ? 'success' : 'info'" effect="light">
              {{ scope.row.enabled ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="serverHosts" label="监控服务器" min-width="220">
          <template #default="scope">
            <el-tag v-if="scope.row.roles.includes('ADMIN')" size="small" type="warning" effect="plain">全部服务器</el-tag>
            <span v-else class="hosts-text">{{ scope.row.serverHosts.join(', ') || '未分配' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="scope">
            {{ formatDateTime(scope.row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="scope">
            <el-link v-if="isAdmin" type="primary" :underline="false" @click="openEdit(scope.row)">编辑</el-link>
            <el-divider v-if="isAdmin" direction="vertical" />
            <el-link type="warning" :underline="false" @click="openResetPassword(scope.row)">
              重置密码
            </el-link>
            <el-divider v-if="isAdmin" direction="vertical" />
            <el-link v-if="isAdmin" type="danger" :underline="false" @click="removeUser(scope.row)">删除</el-link>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-if="isAdmin" v-model="createVisible" title="新增用户" width="520">
      <el-form label-width="100" class="dialog-form">
        <el-form-item label="账号">
          <el-input v-model="createForm.username" placeholder="请输入账号名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="createForm.password" type="password" show-password placeholder="请输入初始密码" />
        </el-form-item>
        <el-form-item label="角色">
          <el-checkbox-group v-model="createForm.roles">
            <el-checkbox v-for="role in roleOptions" :key="role" :label="role">{{ role }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="监控服务器">
          <el-select v-model="createForm.serverHosts" multiple :disabled="createIsAdmin" placeholder="选择服务器" collapse-tags collapse-tags-tooltip>
            <el-option v-for="host in servers" :key="host" :label="host" :value="host" />
          </el-select>
          <div class="hint" v-if="createIsAdmin">管理员默认拥有全部服务器权限</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="createUser">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-if="isAdmin" v-model="editVisible" title="编辑用户" width="520">
      <el-form label-width="100" class="dialog-form">
        <el-form-item label="账号">
          <el-input v-model="editForm.username" disabled />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="editForm.enabled" />
        </el-form-item>
        <el-form-item label="角色">
          <el-checkbox-group v-model="editForm.roles">
            <el-checkbox v-for="role in roleOptions" :key="role" :label="role">{{ role }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="监控服务器">
          <el-select v-model="editForm.serverHosts" multiple :disabled="editIsAdmin" placeholder="选择服务器" collapse-tags collapse-tags-tooltip>
            <el-option v-for="host in servers" :key="host" :label="host" :value="host" />
          </el-select>
          <div class="hint" v-if="editIsAdmin">管理员默认拥有全部服务器权限</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="updateUser">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="passwordVisible" title="重置密码" width="420">
      <el-form label-width="100" class="dialog-form">
        <el-form-item label="账号">
          <el-input v-model="passwordForm.username" disabled />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="passwordForm.password" type="password" show-password placeholder="请输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">取消</el-button>
        <el-button type="primary" @click="resetPassword">确认</el-button>
      </template>
    </el-dialog>
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
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.toolbar-head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.toolbar-title {
  font-size: 18px;
  font-weight: 700;
  color: #1b2b4d;
  letter-spacing: 0.2px;
}

.summary-row {
  margin-top: 2px;
  align-items: stretch;
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

.kpi-value.warning {
  color: #d97706;
}

.kpi-unit {
  color: #6e7f9f;
  font-size: 14px;
}

.kpi-meta {
  margin-top: 8px;
  font-size: 12px;
  color: #7284a6;
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

.users-table :deep(.el-table__header-wrapper th) {
  background: #f4f8ff;
}

.users-table :deep(.el-table__row td) {
  background: rgba(255, 255, 255, 0.68);
}

.username-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.cell-avatar {
  background: linear-gradient(135deg, #0ea5e9, #22c55e);
  color: #0b1120;
  font-weight: 700;
}

.role-tag {
  margin-right: 6px;
}

.hosts-text {
  color: #233760;
}

.hint {
  margin-top: 6px;
  font-size: 12px;
  color: #6b7280;
}

.dialog-form :deep(.el-input__wrapper),
.dialog-form :deep(.el-select__wrapper) {
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 0 0 1px rgba(110, 139, 192, 0.2) inset;
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

@media (max-width: 900px) {
  .toolbar-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .toolbar-head-right {
    width: 100%;
    flex-wrap: wrap;
  }
}

@media (max-width: 640px) {
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
}
</style>
