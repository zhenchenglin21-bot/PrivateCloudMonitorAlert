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

interface AlertRuleView {
  id: number
  name: string
  metric: string
  threshold: string
  severity?: string
  installed: boolean
  enabled: boolean
  updatedAt: string
}

interface AlertRuleRequestView {
  id: number
  ruleId: number | null
  ruleName: string
  metric?: string
  threshold?: string
  severity?: string
  host: string | null
  action: string
  status: string
  requestedBy: string
  requestedAt: string
}

interface RuleFormState {
  id?: number
  name: string
  metric: string
  thresholdValue: string
  severity: string
}

type AssignAction = 'ENABLE' | 'DISABLE' | 'UNINSTALL'

interface MetricOption {
  label: string
  value: string
  unit: string
  placeholder: string
}

interface SeverityOption {
  label: string
  value: string
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

const METRIC_OPTIONS: MetricOption[] = [
  { label: 'CPU 使用率', value: 'CPU 使用率', unit: '%', placeholder: '例如 90' },
  { label: '内存使用率', value: '内存使用率', unit: '%', placeholder: '例如 85' },
  { label: '磁盘使用率', value: '磁盘使用率', unit: '%', placeholder: '例如 80' },
  { label: '网络入速率', value: '网络入速率', unit: 'Mbps', placeholder: '例如 200' },
  { label: '网络出速率', value: '网络出速率', unit: 'Mbps', placeholder: '例如 200' },
  { label: '异常进程 CPU', value: '异常进程 CPU', unit: '%', placeholder: '例如 70' },
  { label: '异常进程内存', value: '异常进程内存', unit: 'MB', placeholder: '例如 1024' },
]

const SEVERITY_OPTIONS: SeverityOption[] = [
  { label: '警告', value: 'warning' },
  { label: '警报', value: 'alert' },
  { label: '严重', value: 'critical' },
]

const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'
const loginPath = '/login'

const loadingOverviewRules = ref(false)
const loadingAssignmentRules = ref(false)
const loadingRuleRequests = ref(false)
const loadingAssignmentRequests = ref(false)

const searchText = ref('')
const selectedHost = ref('')
const topologyNodes = ref<TopologyNode[]>([])
const assignedServers = ref<string[]>([])
const overviewRuleList = ref<AlertRuleView[]>([])
const assignmentRuleList = ref<AlertRuleView[]>([])
const pendingRuleRequests = ref<AlertRuleRequestView[]>([])
const pendingAssignmentRequests = ref<AlertRuleRequestView[]>([])
const isAdmin = ref(false)

const createDialogVisible = ref(false)
const createForm = ref<RuleFormState>({
  name: '',
  metric: METRIC_OPTIONS[0]!.value,
  thresholdValue: '',
  severity: 'warning',
})

const editDialogVisible = ref(false)
const editForm = ref<RuleFormState>({
  id: 0,
  name: '',
  metric: METRIC_OPTIONS[0]!.value,
  thresholdValue: '',
  severity: 'warning',
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

const flatTargetOptions = computed(() => targetOptionGroups.value.flatMap((group) => group.options))

function getMetricOption(metric?: string | null): MetricOption | undefined {
  return METRIC_OPTIONS.find((item) => item.value === metric)
}

function getMetricUnit(metric?: string | null): string {
  return getMetricOption(metric)?.unit || ''
}

function getMetricPlaceholder(metric?: string | null): string {
  return getMetricOption(metric)?.placeholder || '请输入阈值'
}

function buildThreshold(metric: string, thresholdValue: string): string {
  const value = thresholdValue.trim()
  const unit = getMetricUnit(metric)
  return value && unit ? `${value} ${unit}` : value
}

function parseThresholdValue(metric: string, threshold?: string | null): string {
  const raw = String(threshold || '').trim()
  const unit = getMetricUnit(metric)
  if (!raw || !unit) return raw
  if (raw.endsWith(` ${unit}`)) return raw.slice(0, -(` ${unit}`).length).trim()
  if (raw.endsWith(unit)) return raw.slice(0, -unit.length).trim()
  return raw
}

function formatMetricLabel(metric?: string | null): string {
  return getMetricOption(metric)?.label || String(metric || '--')
}

function formatThresholdDisplay(metric?: string | null, threshold?: string | null): string {
  const value = String(threshold || '').trim()
  if (!value) return '--'
  const unit = getMetricUnit(metric)
  if (!unit || value.endsWith(unit)) return value
  return `${value} ${unit}`
}

function formatSeverityLabel(severity?: string | null): string {
  switch (String(severity || '').toLowerCase()) {
    case 'critical':
      return '严重'
    case 'alert':
      return '警报'
    default:
      return '警告'
  }
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

function formatAction(action?: string | null): string {
  switch ((action || '').toUpperCase()) {
    case 'CREATE':
      return '新增'
    case 'UPDATE':
      return '修改'
    case 'DELETE':
      return '删除'
    case 'ENABLE':
      return '启用'
    case 'DISABLE':
      return '停用'
    case 'UNINSTALL':
      return '卸载'
    default:
      return action || '--'
  }
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

async function getApi<T>(path: string, params?: Record<string, string | undefined>): Promise<T> {
  const query = new URLSearchParams()
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value && value.trim()) query.set(key, value)
  })
  const url = `${baseUrl}${path}${query.size ? `?${query.toString()}` : ''}`
  const response = await fetch(url, { headers: authHeaders() })
  if (handleAuthError(response)) throw new Error('未登录或登录已过期')
  const body = (await response.json()) as ApiResponse<T>
  if (!response.ok || body.code !== 200) throw new Error(body?.message || `请求失败: ${response.status}`)
  return body.data
}

async function postApi<T>(path: string, payload?: unknown): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: payload ? JSON.stringify(payload) : undefined,
  })
  if (handleAuthError(response)) throw new Error('未登录或登录已过期')
  const body = (await response.json()) as ApiResponse<T>
  if (!response.ok || body.code !== 200) throw new Error(body?.message || `请求失败: ${response.status}`)
  return body.data
}

function filterRuleList(list: AlertRuleView[]) {
  const keyword = searchText.value.trim().toLowerCase()
  if (!keyword) return list
  return list.filter((rule) =>
    [rule.name, formatMetricLabel(rule.metric), rule.threshold, formatSeverityLabel(rule.severity)].some((field) =>
      String(field || '').toLowerCase().includes(keyword),
    ),
  )
}

function assignmentStatus(rule: AlertRuleView): string {
  if (!rule.installed) return '未安装'
  return rule.enabled ? '已安装（启用）' : '已安装（停用）'
}

function assignmentTagType(rule: AlertRuleView): 'success' | 'warning' | 'info' {
  if (!rule.installed) return 'info'
  return rule.enabled ? 'success' : 'warning'
}

function resetCreateForm() {
  createForm.value = {
    name: '',
    metric: METRIC_OPTIONS[0]!.value,
    thresholdValue: '',
    severity: 'warning',
  }
}

function validateRuleForm(form: RuleFormState): boolean {
  if (!form.name.trim()) {
    ElMessage.error('请输入规则名称')
    return false
  }
  if (!form.metric.trim()) {
    ElMessage.error('请选择监控指标')
    return false
  }
  if (!form.thresholdValue.trim()) {
    ElMessage.error('请输入触发条件')
    return false
  }
  if (!form.severity.trim()) {
    ElMessage.error('请选择告警级别')
    return false
  }
  return true
}

function actionNeedsHost(action: string): boolean {
  return ['ENABLE', 'DISABLE', 'UNINSTALL'].includes(String(action || '').toUpperCase())
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
  const available = flatTargetOptions.value.map((item) => item.value)
  if (selectedHost.value && !available.includes(selectedHost.value)) selectedHost.value = ''
  if (!selectedHost.value && available.length) selectedHost.value = available[0] ?? ''
}

async function loadOverviewRules() {
  loadingOverviewRules.value = true
  try {
    overviewRuleList.value = await getApi<AlertRuleView[]>('/api/alert-rules')
  } finally {
    loadingOverviewRules.value = false
  }
}

async function loadAssignmentRules() {
  if (!selectedHost.value) {
    assignmentRuleList.value = []
    return
  }
  loadingAssignmentRules.value = true
  try {
    assignmentRuleList.value = await getApi<AlertRuleView[]>('/api/alert-rules', { host: selectedHost.value })
  } finally {
    loadingAssignmentRules.value = false
  }
}

async function loadRuleRequests() {
  loadingRuleRequests.value = true
  try {
    pendingRuleRequests.value = await getApi<AlertRuleRequestView[]>('/api/alert-rules/requests', {
      status: 'PENDING',
      category: 'rule',
    })
  } finally {
    loadingRuleRequests.value = false
  }
}

async function loadAssignmentRequests() {
  loadingAssignmentRequests.value = true
  try {
    pendingAssignmentRequests.value = await getApi<AlertRuleRequestView[]>('/api/alert-rules/requests', {
      status: 'PENDING',
      category: 'assignment',
    })
  } finally {
    loadingAssignmentRequests.value = false
  }
}

async function refreshPageData() {
  await Promise.all([loadOverviewRules(), loadAssignmentRules(), loadRuleRequests(), loadAssignmentRequests()])
}

async function submitRequest(payload: Record<string, unknown>, successMessage: string) {
  if (actionNeedsHost(String(payload.action || '')) && !selectedHost.value) {
    ElMessage.error('请先在“告警规则分配”中选择监控对象')
    return
  }
  try {
    await postApi('/api/alert-rules/requests', payload)
    ElMessage.success(successMessage)
    await refreshPageData()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '提交失败')
  }
}

async function createRule() {
  if (!validateRuleForm(createForm.value)) return
  await submitRequest(
    {
      action: 'CREATE',
      name: createForm.value.name.trim(),
      metric: createForm.value.metric,
      threshold: buildThreshold(createForm.value.metric, createForm.value.thresholdValue),
      severity: createForm.value.severity,
    },
    '已提交新增申请，等待管理员审核',
  )
  createDialogVisible.value = false
  resetCreateForm()
}

function openEditDialog(rule: AlertRuleView) {
  editForm.value = {
    id: rule.id,
    name: rule.name,
    metric: rule.metric,
    thresholdValue: parseThresholdValue(rule.metric, rule.threshold),
    severity: String(rule.severity || 'warning').toLowerCase(),
  }
  editDialogVisible.value = true
}

async function submitUpdateRule() {
  if (!validateRuleForm(editForm.value)) return
  await submitRequest(
    {
      action: 'UPDATE',
      ruleId: editForm.value.id,
      name: editForm.value.name.trim(),
      metric: editForm.value.metric,
      threshold: buildThreshold(editForm.value.metric, editForm.value.thresholdValue),
      severity: editForm.value.severity,
    },
    '已提交修改申请，等待管理员审核',
  )
  editDialogVisible.value = false
}

async function requestDelete(rule: AlertRuleView) {
  await submitRequest(
    {
      action: 'DELETE',
      ruleId: rule.id,
    },
    '已提交删除申请，等待管理员审核',
  )
}

async function requestAssignAction(rule: AlertRuleView, action: AssignAction) {
  await submitRequest(
    {
      action,
      ruleId: rule.id,
      host: selectedHost.value,
    },
    '已提交申请，等待管理员审核',
  )
}

async function reviewRequest(request: AlertRuleRequestView, approved: boolean) {
  try {
    await postApi(`/api/alert-rules/requests/${request.id}/review`, { approved })
    ElMessage.success(approved ? '已通过' : '已拒绝')
    await refreshPageData()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '审核失败')
  }
}

const overviewRules = computed(() => filterRuleList(overviewRuleList.value))
const assignmentRules = computed(() => filterRuleList(assignmentRuleList.value))
const createMetricUnit = computed(() => getMetricUnit(createForm.value.metric))
const editMetricUnit = computed(() => getMetricUnit(editForm.value.metric))
const totalRuleCount = computed(() => overviewRuleList.value.length)
const metricTypeCount = computed(() => new Set(overviewRuleList.value.map((item) => item.metric).filter(Boolean)).size)
const installedRuleCount = computed(() => assignmentRuleList.value.filter((item) => item.installed).length)
const enabledAssignmentCount = computed(() => assignmentRuleList.value.filter((item) => item.installed && item.enabled).length)
const pendingTotalCount = computed(() => pendingRuleRequests.value.length + pendingAssignmentRequests.value.length)
type SectionKey = 'overview' | 'assignment' | 'ruleReview' | 'assignmentReview'
const sectionExpanded = ref<Record<SectionKey, boolean>>({
  overview: true,
  assignment: true,
  ruleReview: true,
  assignmentReview: true,
})

function toggleSection(section: SectionKey) {
  sectionExpanded.value[section] = !sectionExpanded.value[section]
}

function severityTagType(severity?: string | null): 'danger' | 'warning' | 'info' {
  switch (String(severity || '').toLowerCase()) {
    case 'critical':
      return 'danger'
    case 'alert':
      return 'warning'
    default:
      return 'info'
  }
}

function actionTagType(action?: string | null): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  switch ((action || '').toUpperCase()) {
    case 'CREATE':
      return 'success'
    case 'UPDATE':
      return 'primary'
    case 'DELETE':
      return 'danger'
    case 'ENABLE':
      return 'success'
    case 'DISABLE':
      return 'warning'
    case 'UNINSTALL':
      return 'danger'
    default:
      return 'info'
  }
}

onMounted(async () => {
  await loadMe()
  const overviewTask = loadOverviewRules()
  const ruleRequestTask = loadRuleRequests()
  const assignmentRequestTask = loadAssignmentRequests()
  await loadHosts()
  await Promise.all([overviewTask, ruleRequestTask, assignmentRequestTask, loadAssignmentRules()])
})

useGlobalRefresh(async () => {
  if (loadingOverviewRules.value || loadingAssignmentRules.value || loadingRuleRequests.value || loadingAssignmentRequests.value) {
    return
  }
  await refreshPageData()
}, { minGapMs: 1200 })
</script>

<template>
  <div class="page">
    <el-card class="toolbar-card panel-card" shadow="hover">
      <div class="toolbar-head">
        <div class="toolbar-title">告警规则管理</div>
        <div class="toolbar-head-right">
          <el-input
            v-model="searchText"
            clearable
            placeholder="搜索规则名称、指标、阈值"
            class="search-input"
            @keyup.enter="refreshPageData"
          />
          <el-button @click="refreshPageData">刷新</el-button>
          <el-button type="primary" @click="createDialogVisible = true">新增规则</el-button>
        </div>
      </div>
    </el-card>

    <el-row :gutter="14" class="summary-row">
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">规则总数</div>
          <div class="kpi-value primary">{{ totalRuleCount }}</div>
          <div class="kpi-meta">当前可用告警规则</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">指标类型</div>
          <div class="kpi-main">
            <span class="kpi-value success">{{ metricTypeCount }}</span>
            <span class="kpi-unit">/ {{ METRIC_OPTIONS.length }}</span>
          </div>
          <div class="kpi-meta">规则覆盖监控指标</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">当前对象规则安装</div>
          <div class="kpi-main">
            <span class="kpi-value warning">{{ installedRuleCount }}</span>
            <span class="kpi-unit">/ {{ assignmentRuleList.length }}</span>
          </div>
          <div class="kpi-meta">对象 {{ selectedHost || '--' }} 的安装情况</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">待处理审核</div>
          <div class="kpi-value">{{ pendingTotalCount }}</div>
          <div class="kpi-meta">规则修订 {{ pendingRuleRequests.length }} / 分配 {{ pendingAssignmentRequests.length }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="panel-card table-card" shadow="hover">
      <template #header>
        <div class="card-header-row">
          <div class="card-title-wrap">
            <div class="card-title">告警规则总览</div>
            <span class="path-text">支持规则新增、修改、删除申请</span>
          </div>
          <div class="header-actions">
            <div class="table-head-actions">
              <el-tag size="small" type="info" effect="plain">总规则 {{ totalRuleCount }}</el-tag>
              <el-tag size="small" type="success" effect="plain">匹配 {{ overviewRules.length }}</el-tag>
            </div>
            <el-button text class="collapse-toggle" @click="toggleSection('overview')">
              {{ sectionExpanded.overview ? '收起' : '展开' }}
            </el-button>
          </div>
        </div>
      </template>
      <div v-show="sectionExpanded.overview">
        <el-table :data="overviewRules" border stripe class="rules-table" v-loading="loadingOverviewRules" empty-text="暂无告警规则">
          <el-table-column prop="name" label="规则名称" min-width="180" />
          <el-table-column label="监控指标" min-width="140">
            <template #default="scope">{{ formatMetricLabel(scope.row.metric) }}</template>
          </el-table-column>
          <el-table-column label="触发条件" min-width="130">
            <template #default="scope">{{ formatThresholdDisplay(scope.row.metric, scope.row.threshold) }}</template>
          </el-table-column>
          <el-table-column label="告警级别" width="120" align="center">
            <template #default="scope">
              <el-tag size="small" :type="severityTagType(scope.row.severity)" effect="light">
                {{ formatSeverityLabel(scope.row.severity) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近更新" width="180">
            <template #default="scope">{{ formatDateTime(scope.row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="scope">
              <div class="action-row">
                <el-button size="small" type="primary" plain @click="openEditDialog(scope.row)">申请修改</el-button>
                <el-button size="small" type="danger" plain @click="requestDelete(scope.row)">申请删除</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <el-card class="panel-card table-card" shadow="hover">
      <template #header>
        <div class="card-header-row">
          <div class="card-title-wrap">
            <div class="card-title">告警规则分配</div>
            <span class="path-text">按监控对象安装、启停和卸载规则</span>
          </div>
          <div class="header-actions">
            <div class="assign-toolbar">
              <el-select v-model="selectedHost" placeholder="选择监控对象" class="host-select" filterable @change="loadAssignmentRules">
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
              <el-tag size="small" type="warning" effect="plain">安装 {{ installedRuleCount }}</el-tag>
              <el-tag size="small" type="success" effect="plain">启用 {{ enabledAssignmentCount }}</el-tag>
            </div>
            <el-button text class="collapse-toggle" @click="toggleSection('assignment')">
              {{ sectionExpanded.assignment ? '收起' : '展开' }}
            </el-button>
          </div>
        </div>
      </template>
      <div v-show="sectionExpanded.assignment">
        <el-alert
          class="assign-tip"
          type="info"
          :closable="false"
          show-icon
          description="卸载审核通过后，对应监控对象将不再装载该规则。"
        />
        <el-table
          :data="assignmentRules"
          border
          stripe
          class="rules-table"
          v-loading="loadingAssignmentRules"
          empty-text="暂无可分配规则"
        >
          <el-table-column prop="name" label="规则名称" min-width="180" />
          <el-table-column label="监控指标" min-width="140">
            <template #default="scope">{{ formatMetricLabel(scope.row.metric) }}</template>
          </el-table-column>
          <el-table-column label="触发条件" min-width="120">
            <template #default="scope">{{ formatThresholdDisplay(scope.row.metric, scope.row.threshold) }}</template>
          </el-table-column>
          <el-table-column label="告警级别" width="120" align="center">
            <template #default="scope">
              <el-tag size="small" :type="severityTagType(scope.row.severity)" effect="light">
                {{ formatSeverityLabel(scope.row.severity) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="当前状态" width="130" align="center">
            <template #default="scope">
              <el-tag size="small" :type="assignmentTagType(scope.row)" effect="light">
                {{ assignmentStatus(scope.row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近更新" width="180">
            <template #default="scope">{{ formatDateTime(scope.row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="280" fixed="right">
            <template #default="scope">
              <div class="action-row">
                <el-button
                  v-if="!scope.row.installed || !scope.row.enabled"
                  size="small"
                  type="success"
                  @click="requestAssignAction(scope.row, 'ENABLE')"
                >
                  {{ scope.row.installed ? '申请启用' : '申请安装' }}
                </el-button>
                <el-button
                  v-if="scope.row.installed && scope.row.enabled"
                  size="small"
                  type="warning"
                  @click="requestAssignAction(scope.row, 'DISABLE')"
                >
                  申请停用
                </el-button>
                <el-button
                  v-if="scope.row.installed"
                  size="small"
                  type="danger"
                  plain
                  @click="requestAssignAction(scope.row, 'UNINSTALL')"
                >
                  申请卸载
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <el-row :gutter="14" class="request-row">
      <el-col :xs="24" :xl="12">
        <el-card class="panel-card table-card request-card" shadow="hover">
          <template #header>
            <div class="card-header-row">
              <div class="card-title-wrap">
                <div class="card-title">规则修订审核</div>
              </div>
              <div class="header-actions">
                <el-tag size="small" type="warning" effect="plain">待审 {{ pendingRuleRequests.length }}</el-tag>
                <el-button text class="collapse-toggle" @click="toggleSection('ruleReview')">
                  {{ sectionExpanded.ruleReview ? '收起' : '展开' }}
                </el-button>
              </div>
            </div>
          </template>
          <div v-show="sectionExpanded.ruleReview">
            <el-table :data="pendingRuleRequests" border stripe class="request-table" v-loading="loadingRuleRequests" empty-text="暂无规则修订待审核">
              <el-table-column prop="ruleName" label="规则" min-width="170" />
              <el-table-column label="申请动作" width="120" align="center">
                <template #default="scope">
                  <el-tag size="small" :type="actionTagType(scope.row.action)" effect="light">{{ formatAction(scope.row.action) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="触发条件" min-width="110">
                <template #default="scope">{{ formatThresholdDisplay(scope.row.metric, scope.row.threshold) }}</template>
              </el-table-column>
              <el-table-column label="告警级别" width="110" align="center">
                <template #default="scope">
                  <el-tag size="small" :type="severityTagType(scope.row.severity)" effect="light">
                    {{ formatSeverityLabel(scope.row.severity) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="requestedBy" label="申请人" width="120" />
              <el-table-column label="申请时间" width="170">
                <template #default="scope">{{ formatDateTime(scope.row.requestedAt) }}</template>
              </el-table-column>
              <el-table-column v-if="isAdmin" label="审核" width="150" fixed="right">
                <template #default="scope">
                  <el-button size="small" type="success" @click="reviewRequest(scope.row, true)">通过</el-button>
                  <el-button size="small" type="danger" plain @click="reviewRequest(scope.row, false)">拒绝</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :xl="12">
        <el-card class="panel-card table-card request-card" shadow="hover">
          <template #header>
            <div class="card-header-row">
              <div class="card-title-wrap">
                <div class="card-title">规则分配审核</div>
              </div>
              <div class="header-actions">
                <el-tag size="small" type="warning" effect="plain">待审 {{ pendingAssignmentRequests.length }}</el-tag>
                <el-button text class="collapse-toggle" @click="toggleSection('assignmentReview')">
                  {{ sectionExpanded.assignmentReview ? '收起' : '展开' }}
                </el-button>
              </div>
            </div>
          </template>
          <div v-show="sectionExpanded.assignmentReview">
            <el-table
              :data="pendingAssignmentRequests"
              border
              stripe
              class="request-table"
              v-loading="loadingAssignmentRequests"
              empty-text="暂无规则分配待审核"
            >
              <el-table-column prop="ruleName" label="规则" min-width="170" />
              <el-table-column label="监控对象" width="130">
                <template #default="scope">{{ scope.row.host || '--' }}</template>
              </el-table-column>
              <el-table-column label="分配动作" width="120" align="center">
                <template #default="scope">
                  <el-tag size="small" :type="actionTagType(scope.row.action)" effect="light">{{ formatAction(scope.row.action) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="触发条件" min-width="110">
                <template #default="scope">{{ formatThresholdDisplay(scope.row.metric, scope.row.threshold) }}</template>
              </el-table-column>
              <el-table-column label="告警级别" width="110" align="center">
                <template #default="scope">
                  <el-tag size="small" :type="severityTagType(scope.row.severity)" effect="light">
                    {{ formatSeverityLabel(scope.row.severity) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="requestedBy" label="申请人" width="120" />
              <el-table-column label="申请时间" width="170">
                <template #default="scope">{{ formatDateTime(scope.row.requestedAt) }}</template>
              </el-table-column>
              <el-table-column v-if="isAdmin" label="审核" width="150" fixed="right">
                <template #default="scope">
                  <el-button size="small" type="success" @click="reviewRequest(scope.row, true)">通过</el-button>
                  <el-button size="small" type="danger" plain @click="reviewRequest(scope.row, false)">拒绝</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="createDialogVisible" title="新建告警规则" width="460px">
      <el-form label-width="90" class="dialog-form">
        <el-form-item label="规则名称">
          <el-input v-model="createForm.name" placeholder="请输入规则名称" />
        </el-form-item>
        <el-form-item label="监控指标">
          <el-select v-model="createForm.metric" style="width: 100%">
            <el-option v-for="item in METRIC_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="触发条件">
          <div class="threshold-row">
            <el-input v-model="createForm.thresholdValue" :placeholder="getMetricPlaceholder(createForm.metric)" />
            <span class="threshold-unit">{{ createMetricUnit || '--' }}</span>
          </div>
        </el-form-item>
        <el-form-item label="告警级别">
          <el-select v-model="createForm.severity" style="width: 100%">
            <el-option v-for="item in SEVERITY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="createRule">确认创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editDialogVisible" title="修改告警规则申请" width="460px">
      <el-form label-width="90" class="dialog-form">
        <el-form-item label="规则名称">
          <el-input v-model="editForm.name" placeholder="请输入规则名称" />
        </el-form-item>
        <el-form-item label="监控指标">
          <el-select v-model="editForm.metric" style="width: 100%">
            <el-option v-for="item in METRIC_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="触发条件">
          <div class="threshold-row">
            <el-input v-model="editForm.thresholdValue" :placeholder="getMetricPlaceholder(editForm.metric)" />
            <span class="threshold-unit">{{ editMetricUnit || '--' }}</span>
          </div>
        </el-form-item>
        <el-form-item label="告警级别">
          <el-select v-model="editForm.severity" style="width: 100%">
            <el-option v-for="item in SEVERITY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitUpdateRule">提交修改申请</el-button>
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
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.toolbar-title {
  font-size: 18px;
  font-weight: 700;
  color: #1b2b4d;
  letter-spacing: 0.2px;
}

.toolbar-head-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.search-input {
  width: 320px;
}

.host-select {
  width: 230px;
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

.action-row {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}

.table-card :deep(.el-card__body) {
  padding: 12px;
}

.card-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.card-title-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
}

.card-title {
  font-weight: 700;
  color: #233760;
  font-size: 15px;
}

.path-text {
  color: #6f7f9e;
  font-size: 12px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.table-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.collapse-toggle {
  padding: 0 2px;
  font-weight: 600;
}

.assign-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.assign-tip {
  margin: 0 0 12px;
}

.request-card {
  height: 100%;
}

.request-row {
  margin-top: 2px;
}

.rules-table :deep(.el-table__header-wrapper th),
.request-table :deep(.el-table__header-wrapper th) {
  background: #f4f8ff;
}

.rules-table :deep(.el-table__row td),
.request-table :deep(.el-table__row td) {
  background: rgba(255, 255, 255, 0.68);
}

.threshold-row {
  width: 100%;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
  align-items: center;
}

.threshold-unit {
  min-width: 52px;
  color: #475569;
  font-size: 13px;
  text-align: right;
}

.dialog-form :deep(.el-input__wrapper),
.dialog-form :deep(.el-select__wrapper) {
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 0 0 1px rgba(110, 139, 192, 0.2) inset;
}

:deep(.el-tag.el-tag--success) {
  border-color: rgba(34, 197, 94, 0.3);
  background: rgba(34, 197, 94, 0.1);
  color: #15803d;
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

  .header-actions {
    width: 100%;
    justify-content: space-between;
  }

  .assign-toolbar {
    width: 100%;
  }
}

@media (max-width: 900px) {
  .toolbar-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .toolbar-head-right {
    width: 100%;
  }

  .search-input {
    width: 100%;
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
}
</style>
