<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useGlobalRefresh } from '../composables/useGlobalRefresh'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

interface TopologyNode {
  host: string
  role: string
  parentHost?: string | null
  queryHost?: string | null
}

interface AuthMe {
  username: string
  roles: string[]
  servers: string[]
  admin: boolean
}

interface AlertHistoryItem {
  id: number
  time: string
  level: string
  rule: string
  metricName?: string | null
  alertState?: string | null
  previousState?: string | null
  thresholdType?: string | null
  host: string
  status: string
  reason?: string | null
  recommendation?: string | null
  decisionSource?: string | null
  feedbackStatus?: string | null
  feedbackSource?: string | null
  feedbackComment?: string | null
  durationSeconds?: number | null
  confidenceScore?: number | null
  value?: number | null
  thresholdValue?: number | null
  staticThreshold?: number | null
  dynamicThreshold?: number | null
  meanValue?: number | null
  stdValue?: number | null
  trendValue?: number | null
  agentStatus?: string | null
  agentModel?: string | null
  agentRiskScore?: number | null
  agentPrediction?: string | null
  agentAnalysis?: string | null
  agentRecommendation?: string | null
  agentRawResponse?: string | null
  agentUpdatedAt?: string | null
}

interface PagedResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

interface MonitoringTargetOption {
  value: string
  label: string
  role: 'server' | 'vm' | 'container'
}

interface MonitoringTargetGroup {
  serverHost: string
  options: MonitoringTargetOption[]
}

const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'
const loginPath = '/login'

const loading = ref(false)
const isAdmin = ref(false)
const topologyNodes = ref<TopologyNode[]>([])
const assignedServers = ref<string[]>([])
const selectedHost = ref('')
const selectedLevel = ref('')
const selectedThresholdType = ref('')
const selectedStatus = ref('')
const history = ref<AlertHistoryItem[]>([])
const feedbackLoadingId = ref<number | null>(null)
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

const filteredHistory = computed(() => history.value)
const latestAlertTime = computed(() => {
  const latest = filteredHistory.value
    .map((item) => Date.parse(item.time))
    .filter((ts) => !Number.isNaN(ts))
    .sort((a, b) => b - a)[0]
  if (!latest) return '--'
  return formatDateTime(new Date(latest).toISOString())
})

function isServerRole(role?: string | null): boolean {
  const value = String(role || '').toLowerCase()
  return value === 'server' || value === 'host'
}

function isVmRole(role?: string | null): boolean {
  return String(role || '').toLowerCase() === 'vm'
}

function isContainerRole(role?: string | null): boolean {
  return String(role || '').toLowerCase() === 'container'
}

function uniqueSortedHosts(list: string[]): string[] {
  return [...new Set(list.filter((item) => !!item))].sort((a, b) => a.localeCompare(b))
}

const serverNodes = computed(() => topologyNodes.value.filter((item) => isServerRole(item.role)))
const vmNodes = computed(() => topologyNodes.value.filter((item) => isVmRole(item.role)))
const containerNodes = computed(() => topologyNodes.value.filter((item) => isContainerRole(item.role)))

const visibleServerHosts = computed(() => {
  if (isAdmin.value) {
    return uniqueSortedHosts(serverNodes.value.map((item) => item.host))
  }
  return uniqueSortedHosts(assignedServers.value)
})

const targetOptionGroups = computed<MonitoringTargetGroup[]>(() => {
  const groups: MonitoringTargetGroup[] = []
  for (const serverHost of visibleServerHosts.value) {
    const options: MonitoringTargetOption[] = []
    const vmList = vmNodes.value.filter((item) => item.parentHost === serverHost)
    const serverContainers = containerNodes.value.filter((item) => item.parentHost === serverHost)
    const vmHostSet = new Set(vmList.map((item) => item.host))
    const vmContainers = containerNodes.value.filter((item) => item.parentHost && vmHostSet.has(item.parentHost))

    options.push({ value: serverHost, label: `服务器 · ${serverHost}`, role: 'server' })

    vmList.forEach((vm) => {
      options.push({ value: vm.host, label: `↳ 虚拟机 · ${vm.host}`, role: 'vm' })
      containerNodes.value
        .filter((node) => node.parentHost === vm.host)
        .forEach((container) => {
          options.push({ value: container.host, label: `↳↳ 容器 · ${container.host}`, role: 'container' })
        })
    })

    serverContainers.forEach((container) => {
      options.push({ value: container.host, label: `↳ 容器 · ${container.host}`, role: 'container' })
    })

    vmContainers.forEach((container) => {
      if (!options.some((item) => item.value === container.host)) {
        options.push({ value: container.host, label: `↳↳ 容器 · ${container.host}`, role: 'container' })
      }
    })

    const deduped = options.filter((item, index, arr) => arr.findIndex((other) => other.value === item.value) === index)
    groups.push({ serverHost, options: deduped })
  }
  return groups
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

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('pc_token')
  if (!token) return {}
  return { Authorization: `Bearer ${token}` }
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

function handleAuthError(response: Response): boolean {
  if (response.status !== 401) return false
  localStorage.removeItem('pc_token')
  localStorage.removeItem('pc_username')
  window.location.href = loginPath
  return true
}

function formatDateTime(value?: string | null): string {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')
  const ss = String(date.getSeconds()).padStart(2, '0')
  return `${y}-${m}-${d} ${hh}:${mm}:${ss}`
}

async function loadMe() {
  const me = await getApi<AuthMe>('/api/auth/me')
  isAdmin.value = me.admin
  assignedServers.value = uniqueSortedHosts(me.servers || [])
}

async function loadHosts() {
  try {
    const nodes = await getApi<TopologyNode[]>('/api/hosts/topology', { start: '-180d', end: 'now()' })
    topologyNodes.value = Array.isArray(nodes) ? nodes : []
  } catch (error) {
    topologyNodes.value = []
    if (isAdmin.value) {
      throw error
    }
  }
}

async function loadHistory() {
  loading.value = true
  try {
    const result = await getApi<PagedResult<AlertHistoryItem>>('/api/alert-history', {
      host: selectedHost.value || undefined,
      level: selectedLevel.value || undefined,
      thresholdType: selectedThresholdType.value || undefined,
      status: selectedStatus.value || undefined,
      page: String(currentPage.value),
      size: String(pageSize.value),
    })
    history.value = result.items
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '查询失败')
  } finally {
    loading.value = false
  }
}

async function onReset() {
  selectedHost.value = ''
  selectedLevel.value = ''
  selectedThresholdType.value = ''
  selectedStatus.value = ''
  currentPage.value = 1
  await loadHistory()
}

async function onSearch() {
  currentPage.value = 1
  await loadHistory()
}

async function onCurrentPageChange(page: number) {
  currentPage.value = page
  await loadHistory()
}

async function onPageSizeChange(size: number) {
  pageSize.value = size
  currentPage.value = 1
  await loadHistory()
}

function normalizeFeedbackStatus(status?: string | null) {
  const normalized = (status || '').toUpperCase()
  if (normalized === 'FALSE_POSITIVE') return 'FALSE_POSITIVE'
  if (normalized === 'VALID') return 'VALID'
  return 'UNLABELED'
}

function feedbackTagType(status?: string | null) {
  const normalized = normalizeFeedbackStatus(status)
  if (normalized === 'FALSE_POSITIVE') return 'danger'
  if (normalized === 'VALID') return 'success'
  return 'info'
}

function feedbackStatusText(status?: string | null) {
  const normalized = normalizeFeedbackStatus(status)
  if (normalized === 'FALSE_POSITIVE') return '误报'
  if (normalized === 'VALID') return '有效告警'
  return '未标注'
}

function decisionSourceText(source?: string | null) {
  if (!source) return '--'
  if (source === 'heuristic') return '规则决策'
  if (source === 'state_machine') return '状态机恢复'
  if (source === 'qwen' || source === 'llm') return '大模型决策'
  return source
}

function levelText(level?: string | null) {
  switch ((level || '').toLowerCase()) {
    case 'critical':
      return '严重'
    case 'alert':
      return '警报'
    case 'warning':
      return '警告'
    default:
      return level || '--'
  }
}

function levelTagType(level?: string | null): 'danger' | 'warning' | 'info' {
  switch ((level || '').toLowerCase()) {
    case 'critical':
      return 'danger'
    case 'alert':
      return 'warning'
    case 'warning':
      return 'info'
    default:
      return 'info'
  }
}

function stateText(state?: string | null) {
  switch ((state || '').toUpperCase()) {
    case 'NORMAL':
      return 'NORMAL'
    case 'WARNING':
      return 'WARNING'
    case 'ALERT':
      return 'ALERT'
    case 'CRITICAL':
      return 'CRITICAL'
    default:
      return state || '--'
  }
}

function stateTagType(state?: string | null): 'success' | 'warning' | 'danger' | 'info' {
  switch ((state || '').toUpperCase()) {
    case 'NORMAL':
      return 'success'
    case 'WARNING':
      return 'warning'
    case 'ALERT':
    case 'CRITICAL':
      return 'danger'
    default:
      return 'info'
  }
}

function statusText(status?: string | null): string {
  return status === 'resolved' ? '已恢复' : '未恢复'
}

function statusTagType(status?: string | null): 'success' | 'warning' {
  return status === 'resolved' ? 'success' : 'warning'
}

function thresholdTypeText(type?: string | null) {
  switch ((type || '').toLowerCase()) {
    case 'static':
      return '静态阈值'
    case 'dynamic':
      return '动态阈值'
    default:
      return '--'
  }
}

function thresholdTagType(type?: string | null): 'success' | 'warning' | 'danger' | 'info' {
  switch ((type || '').toLowerCase()) {
    case 'static':
      return 'info'
    case 'dynamic':
      return 'warning'
    default:
      return 'info'
  }
}

const METRIC_LABEL_MAP: Record<string, string> = {
  cpu: 'CPU用户态占比',
  mem: '内存使用率',
  disk: '磁盘使用率',
  net_in: '网络入流量',
  net_out: '网络出流量',
  abnormal_process: '异常进程',
  abnormal_process_cpu: '异常进程CPU',
  abnormal_process_mem: '异常进程内存',
}

function metricLabel(metric?: string | null): string {
  const raw = String(metric || '').trim()
  if (!raw) return '--'
  const normalized = raw.toLowerCase()
  return METRIC_LABEL_MAP[normalized] || raw
}

function formatReasonText(reason?: string | null): string {
  const raw = String(reason || '').trim()
  if (!raw) return '--'
  let text = raw

  // Compatible display for historical mojibake records.
  text = text
    .replace(/寮傚父杩涚▼鍐呭瓨/g, '异常进程内存')
    .replace(/寮傚父杩涚▼CPU/g, '异常进程CPU')

  const metricKeys = Object.keys(METRIC_LABEL_MAP).sort((a, b) => b.length - a.length)
  for (const key of metricKeys) {
    text = text.replace(new RegExp(`\\b${key}\\b`, 'gi'), METRIC_LABEL_MAP[key] || key)
  }

  return text
}

function formatMetricSummary(item: AlertHistoryItem) {
  const parts = [
    `阈值类型: ${thresholdTypeText(item.thresholdType)}`,
    `指标: ${metricLabel(item.metricName)}`,
    `当前值: ${formatNumber(item.value)}`,
    `阈值: ${formatNumber(item.thresholdValue)}`,
    `均值: ${formatNumber(item.meanValue)}`,
    `标准差: ${formatNumber(item.stdValue)}`,
    `趋势: ${formatNumber(item.trendValue)}`,
  ]
  return parts.join(' / ')
}

function formatNumber(value?: number | null) {
  if (value == null || Number.isNaN(value)) return '--'
  return value.toFixed(2)
}

function agentStatusText(status?: string | null) {
  switch ((status || '').toLowerCase()) {
    case 'pending':
      return '待分析'
    case 'running':
      return '分析中'
    case 'success':
      return '已完成'
    case 'failed':
      return '失败'
    case 'skipped':
      return '已跳过'
    default:
      return '--'
  }
}

function agentStatusTagType(status?: string | null): 'success' | 'warning' | 'danger' | 'info' {
  switch ((status || '').toLowerCase()) {
    case 'success':
      return 'success'
    case 'running':
    case 'pending':
      return 'warning'
    case 'failed':
      return 'danger'
    case 'skipped':
    default:
      return 'info'
  }
}

function formatRiskScore(value?: number | null) {
  if (value == null || Number.isNaN(value)) return '--'
  return `${(value * 100).toFixed(1)}%`
}

async function submitFeedback(item: AlertHistoryItem, feedbackStatus: 'VALID' | 'FALSE_POSITIVE') {
  feedbackLoadingId.value = item.id
  try {
    await postApi(`/api/alert-history/${item.id}/feedback`, { feedbackStatus })
    item.feedbackStatus = feedbackStatus
    item.feedbackComment = null
    ElMessage.success(feedbackStatus === 'VALID' ? '已标记为有效告警' : '已标记为误报')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '反馈提交失败')
  } finally {
    feedbackLoadingId.value = null
  }
}

onMounted(async () => {
  await loadMe()
  await Promise.all([loadHistory(), loadHosts()])
})

useGlobalRefresh(async () => {
  if (loading.value || feedbackLoadingId.value !== null) {
    return
  }
  await loadHistory()
}, { minGapMs: 1200 })
</script>

<template>
  <div class="page">
    <el-card class="toolbar-card panel-card" shadow="hover">
      <div class="toolbar-head">
        <div class="toolbar-title">告警历史查询</div>
        <div class="toolbar-head-right">
          <el-tag size="small" type="info" effect="plain">总记录 {{ total }}</el-tag>
          <el-tag size="small" type="success" effect="plain">最新 {{ latestAlertTime }}</el-tag>
        </div>
      </div>
      <el-form :inline="true" label-width="74" class="toolbar-form">
        <el-form-item label="监控对象">
          <el-select v-model="selectedHost" clearable filterable placeholder="全部监控对象" style="width: 240px">
            <el-option-group
              v-for="group in targetOptionGroups"
              :key="group.serverHost"
              :label="`服务器 ${group.serverHost}`"
            >
              <el-option
                v-for="option in group.options"
                :key="`${option.role}-${option.value}`"
                :label="option.label"
                :value="option.value"
              />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="告警级别">
          <el-select v-model="selectedLevel" clearable placeholder="全部级别" style="width: 140px">
            <el-option label="严重" value="critical" />
            <el-option label="警报" value="alert" />
            <el-option label="警告" value="warning" />
          </el-select>
        </el-form-item>
        <el-form-item label="阈值类型">
          <el-select v-model="selectedThresholdType" clearable placeholder="全部类型" style="width: 150px">
            <el-option label="静态阈值" value="static" />
            <el-option label="动态阈值" value="dynamic" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="selectedStatus" clearable placeholder="全部状态" style="width: 140px">
            <el-option label="未恢复" value="firing" />
            <el-option label="已恢复" value="resolved" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">筛选</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="panel-card table-card" shadow="hover">
      <template #header>
        <div class="card-header-row">
          <div class="card-title-wrap">
            <div class="card-title">告警历史列表</div>
            <span class="path-text">支持状态流转、Agent 分析与反馈学习</span>
          </div>
          <div class="table-head-actions">
            <el-tag size="small" type="success" effect="plain">当前页 {{ filteredHistory.length }}</el-tag>
          </div>
        </div>
      </template>
      <el-table :data="filteredHistory" border stripe class="history-table" v-loading="loading" empty-text="暂无告警历史">
        <el-table-column type="expand" width="56">
          <template #default="scope">
            <div class="detail-panel">
              <div class="detail-title">Agent 分析详情</div>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="分析状态">
                  <el-tag size="small" :type="agentStatusTagType(scope.row.agentStatus)">
                    {{ agentStatusText(scope.row.agentStatus) }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="风险评分">
                  {{ formatRiskScore(scope.row.agentRiskScore) }}
                </el-descriptions-item>
                <el-descriptions-item label="模型">
                  {{ scope.row.agentModel || '--' }}
                </el-descriptions-item>
                <el-descriptions-item label="更新时间">
                  {{ formatDateTime(scope.row.agentUpdatedAt) }}
                </el-descriptions-item>
                <el-descriptions-item label="趋势预测" :span="2">
                  {{ scope.row.agentPrediction || '--' }}
                </el-descriptions-item>
                <el-descriptions-item label="原因分析" :span="2">
                  {{ scope.row.agentAnalysis || '--' }}
                </el-descriptions-item>
                <el-descriptions-item label="处理建议" :span="2">
                  {{ scope.row.agentRecommendation || '--' }}
                </el-descriptions-item>
              </el-descriptions>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="触发时间" width="180">
          <template #default="scope">
            {{ formatDateTime(scope.row.time) }}
          </template>
        </el-table-column>
        <el-table-column label="级别" width="100" align="center">
          <template #default="scope">
            <el-tag size="small" :type="levelTagType(scope.row.level)">{{ levelText(scope.row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rule" label="告警规则" width="200" />
        <el-table-column label="阈值类型" width="120" align="center">
          <template #default="scope">
            <el-tag size="small" :type="thresholdTagType(scope.row.thresholdType)">
              {{ thresholdTypeText(scope.row.thresholdType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态流转" width="180">
          <template #default="scope">
            <div class="state-flow">
              <el-tag size="small" :type="stateTagType(scope.row.previousState)">{{ stateText(scope.row.previousState) }}</el-tag>
              <span class="state-arrow">→</span>
              <el-tag size="small" :type="stateTagType(scope.row.alertState)">{{ stateText(scope.row.alertState) }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="host" label="目标对象" min-width="130" />
        <el-table-column prop="status" label="状态" width="110" align="center">
          <template #default="scope">
            <el-tag size="small" :type="statusTagType(scope.row.status)">
              {{ statusText(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="指标摘要" min-width="280">
          <template #default="scope">
            {{ formatMetricSummary(scope.row) }}
          </template>
        </el-table-column>
        <el-table-column label="原因" min-width="220">
          <template #default="scope">
            {{ formatReasonText(scope.row.reason) }}
          </template>
        </el-table-column>
        <el-table-column label="决策方式" width="120">
          <template #default="scope">
            {{ decisionSourceText(scope.row.decisionSource) }}
          </template>
        </el-table-column>
        <el-table-column label="Agent" width="110" align="center">
          <template #default="scope">
            <el-tag size="small" :type="agentStatusTagType(scope.row.agentStatus)">
              {{ agentStatusText(scope.row.agentStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="recommendation" label="处理建议" min-width="240" />
        <el-table-column label="反馈" width="120" align="center">
          <template #default="scope">
            <el-tag size="small" :type="feedbackTagType(scope.row.feedbackStatus)">
              {{ feedbackStatusText(scope.row.feedbackStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="学习反馈" width="220" fixed="right">
          <template #default="scope">
            <div class="feedback-actions">
              <el-button
                size="small"
                type="success"
                plain
                :loading="feedbackLoadingId === scope.row.id && normalizeFeedbackStatus(scope.row.feedbackStatus) !== 'VALID'"
                @click="submitFeedback(scope.row, 'VALID')"
              >
                有效告警
              </el-button>
              <el-button
                size="small"
                type="danger"
                plain
                :loading="feedbackLoadingId === scope.row.id && normalizeFeedbackStatus(scope.row.feedbackStatus) !== 'FALSE_POSITIVE'"
                @click="submitFeedback(scope.row, 'FALSE_POSITIVE')"
              >
                误报
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-row">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :current-page="currentPage"
          :page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          @current-change="onCurrentPageChange"
          @size-change="onPageSizeChange"
        />
      </div>
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

.toolbar-card {
  padding-bottom: 2px;
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
  gap: 8px;
}

.toolbar-form {
  margin-top: 12px;
}

.toolbar-form :deep(.el-form-item) {
  margin-bottom: 8px;
}

.toolbar-form :deep(.el-input__wrapper),
.toolbar-form :deep(.el-select__wrapper) {
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 0 0 1px rgba(110, 139, 192, 0.2) inset;
}

.table-card :deep(.el-card__body) {
  padding: 12px;
}

.card-header-row {
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

.feedback-actions {
  display: flex;
  gap: 8px;
}

.state-flow {
  display: flex;
  align-items: center;
  gap: 6px;
}

.state-arrow {
  color: #64748b;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.detail-panel {
  padding: 8px 12px;
}

.detail-title {
  font-weight: 600;
  margin-bottom: 8px;
}

.history-table :deep(.el-table__header-wrapper th) {
  background: #f4f8ff;
}

.history-table :deep(.el-table__row td) {
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

@media (max-width: 1180px) {
  .card-header-row {
    flex-direction: column;
    align-items: flex-start;
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

  .card-title-wrap {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }

  .feedback-actions {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
