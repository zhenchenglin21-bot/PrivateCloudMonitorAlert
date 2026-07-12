<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Connection, WarningFilled, Aim, Refresh } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { useGlobalRefresh } from '../composables/useGlobalRefresh'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

interface TopologyNode {
  host: string
  role: string
  queryHost?: string | null
  lastSeen?: string | null
}

interface MetricPoint {
  time: string
  value: number
}

interface AlertTodaySummary {
  startTime: string
  endTime: string
  totalCount: number
  firingCount: number
  resolvedCount: number
  criticalCount: number
  alertCount: number
  warningCount: number
  effectiveCount: number
  falsePositiveCount: number
  feedbackTotalCount: number
  feedbackCoverageRate: number
  effectiveRate: number
}

interface AlertFeedbackSummary {
  host: string | null
  ruleName: string | null
  metricName: string | null
  totalCount: number
  effectiveCount: number
  falsePositiveCount: number
  unresolvedCount: number
  effectiveRate: number
  falsePositiveRate: number
}

interface AlertHistoryItem {
  id: number
  time: string
  level: string
  rule: string
  metricName?: string | null
  thresholdType?: string | null
  host: string
  status: string
  reason?: string | null
}

interface PagedResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

type TStatus = 'online' | 'offline' | 'abnormal'

const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'
const ONLINE_THRESHOLD_MS = 10 * 60 * 1000
const loginPath = '/login'

const router = useRouter()
const loadingServers = ref(false)
const loadingTrend = ref(false)
const serverNodes = ref<TopologyNode[]>([])
const updatedAt = ref('')
const todayAlertCount = ref<number | null>(null)
const todayFiringCount = ref<number | null>(null)
const todayResolvedCount = ref<number | null>(null)
const todayCriticalCount = ref<number | null>(null)
const todayAlertLevelCount = ref<number | null>(null)
const todayWarningCount = ref<number | null>(null)
const effectiveAlertCount = ref<number | null>(null)
const falsePositiveCount = ref<number | null>(null)
const feedbackCoverageRate = ref<number | null>(null)
const feedbackSampleCount = ref<number | null>(null)
const hitRate = ref<number | null>(null)
const loadingRecentAlerts = ref(false)
const recentAlerts = ref<AlertHistoryItem[]>([])

const selectedTrendServerHost = ref('')
const trendCpuSeries = ref<MetricPoint[]>([])
const trendMemorySeries = ref<MetricPoint[]>([])
const trendDiskSeries = ref<MetricPoint[]>([])

const isServerRole = (role?: string) => {
  const value = (role || '').toLowerCase()
  return value === 'server' || value === 'host'
}

const allServers = computed(() => (Array.isArray(serverNodes.value) ? serverNodes.value : []).filter((node) => isServerRole(node.role)))

const selectedTrendServer = computed(
  () => allServers.value.find((node) => node.host === selectedTrendServerHost.value) ?? allServers.value[0],
)

const onlineServers = computed(() =>
  allServers.value.filter((node) => {
    if (!node.lastSeen) {
      return false
    }
    const last = Date.parse(node.lastSeen)
    if (Number.isNaN(last)) {
      return false
    }
    return Date.now() - last <= ONLINE_THRESHOLD_MS
  }),
)

const onlineServerCount = computed(() => onlineServers.value.length)
const totalServerCount = computed(() => allServers.value.length)
const offlineServerCount = computed(() => Math.max(totalServerCount.value - onlineServerCount.value, 0))
const onlineRate = computed(() => (totalServerCount.value ? Math.round((onlineServerCount.value / totalServerCount.value) * 100) : 0))

const recentServers = computed(() => {
  return [...allServers.value]
    .sort((a, b) => Date.parse(b.lastSeen || '') - Date.parse(a.lastSeen || ''))
    .slice(0, 8)
})

const goToMonitoring = () => {
  router.push({ name: 'monitoring' })
}

const goToAlertHistory = () => {
  router.push({ name: 'alertHistory' })
}

const formatTime = (value?: string | null) => {
  if (!value) {
    return '--'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '--'
  }
  return toDateTimeString(date)
}

const formatAgo = (value?: string | null) => {
  if (!value) {
    return '无数据'
  }
  const ts = Date.parse(value)
  if (Number.isNaN(ts)) {
    return '无数据'
  }
  const diffSec = Math.max(0, Math.floor((Date.now() - ts) / 1000))
  if (diffSec < 60) {
    return `${diffSec} 秒前`
  }
  if (diffSec < 3600) {
    return `${Math.floor(diffSec / 60)} 分钟前`
  }
  return `${Math.floor(diffSec / 3600)} 小时前`
}

const statusOf = (value?: string | null): TStatus => {
  if (value == null) {
    return 'offline'
  }
  const ts = Date.parse(value)
  if (Number.isNaN(ts)) {
    return 'abnormal'
  }
  return Date.now() - ts <= ONLINE_THRESHOLD_MS ? 'online' : 'offline'
}

const statusLabel = (s: TStatus) => {
  if (s === 'online') return '在线'
  if (s === 'offline') return '离线'
  return '异常'
}

const statusTagType = (s: TStatus) => {
  if (s === 'online') return 'success'
  if (s === 'offline') return 'danger'
  return 'warning'
}

const todayTotalClass = computed(() => {
  if (todayAlertCount.value == null) return 'danger'
  if (todayAlertCount.value > 10) return 'warning'
  return 'success'
})

const smartRateClass = computed(() => {
  if (hitRate.value == null) return 'danger'
  if (hitRate.value >= 80) return 'success'
  return 'warning'
})

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

function thresholdTypeText(type?: string | null) {
  switch ((type || '').toLowerCase()) {
    case 'static':
      return '静态阈值'
    case 'dynamic':
      return '动态阈值'
    case 'hybrid':
      return '静态+动态'
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
    case 'hybrid':
      return 'danger'
    default:
      return 'info'
  }
}

function alertStatusText(status?: string | null) {
  return (status || '').toLowerCase() === 'resolved' ? '已恢复' : '未恢复'
}

function alertStatusType(status?: string | null): 'success' | 'warning' {
  return (status || '').toLowerCase() === 'resolved' ? 'success' : 'warning'
}

function reasonSnippet(reason?: string | null) {
  const text = (reason || '').trim()
  if (!text) return '无异常说明'
  if (text.length <= 88) return text
  return `${text.slice(0, 88)}...`
}

function toDateTimeString(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')
  const ss = String(date.getSeconds()).padStart(2, '0')
  return `${y}-${m}-${d} ${hh}:${mm}:${ss}`
}

function calcWindow(start: string, end: string): string {
  const s = new Date(start).getTime()
  const e = new Date(end).getTime()
  if (Number.isNaN(s) || Number.isNaN(e)) {
    return '5m'
  }
  const hours = Math.abs(e - s) / (1000 * 60 * 60)
  if (hours > 96) return '30m'
  if (hours > 24) return '10m'
  return '1m'
}

async function getApi<T>(path: string, params?: Record<string, string | undefined>) {
  const query = new URLSearchParams()
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value && value.trim()) {
      query.set(key, value)
    }
  })
  const suffix = query.size ? `?${query.toString()}` : ''
  const url = `${baseUrl}${path}${suffix}`
  const response = await fetch(url, { headers: authHeaders() })
  if (handleAuthError(response)) {
    throw new Error('未登录或登录已过期')
  }
  if (!response.ok) {
    throw new Error(`请求失败: ${response.status}`)
  }
  const payload = (await response.json()) as ApiResponse<T>
  return payload.data
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

function normalizePercentSeries(series: MetricPoint[]): MetricPoint[] {
  const grouped = new Map<number, { sum: number; count: number }>()
  series
    .filter((point) => point && point.time && typeof point.value === 'number')
    .forEach((point) => {
      const ts = Date.parse(point.time)
      if (Number.isNaN(ts)) {
        return
      }
      const secondTs = Math.floor(ts / 1000) * 1000
      const value = Math.max(0, Math.min(100, point.value))
      const current = grouped.get(secondTs)
      if (current) {
        current.sum += value
        current.count += 1
      } else {
        grouped.set(secondTs, { sum: value, count: 1 })
      }
    })
  return [...grouped.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([ts, agg]) => ({
      time: new Date(ts).toISOString(),
      value: agg.sum / agg.count,
    }))
}

function smoothStorageSeries(series: MetricPoint[]): MetricPoint[] {
  if (!series.length) {
    return []
  }
  const sorted = [...series].sort((a, b) => Date.parse(a.time) - Date.parse(b.time))
  const result: MetricPoint[] = []
  let prevBounded: number | null = null
  let prevSmoothed: number | null = null

  sorted.forEach((point) => {
    const raw = point.value
    let bounded = raw
    if (prevBounded != null) {
      const maxStepUp = 1.2
      const maxStepDown = 0.4
      bounded = Math.min(prevBounded + maxStepUp, Math.max(prevBounded - maxStepDown, raw))
    }
    const smoothed = prevSmoothed == null ? bounded : prevSmoothed * 0.75 + bounded * 0.25
    result.push({
      time: point.time,
      value: Number(smoothed.toFixed(3)),
    })
    prevBounded = bounded
    prevSmoothed = smoothed
  })
  return result
}

const chart = {
  width: 860,
  height: 300,
  paddingLeft: 56,
  paddingRight: 18,
  paddingTop: 18,
  paddingBottom: 42,
}

function parseSeries(series: MetricPoint[]) {
  return series
    .map((item) => ({ ts: Date.parse(item.time), value: item.value }))
    .filter((item) => !Number.isNaN(item.ts))
    .sort((a, b) => a.ts - b.ts)
}

const parsedCpuSeries = computed(() => parseSeries(trendCpuSeries.value))
const parsedMemorySeries = computed(() => parseSeries(trendMemorySeries.value))
const parsedDiskSeries = computed(() => parseSeries(trendDiskSeries.value))

const trendHasSeries = computed(
  () => parsedCpuSeries.value.length > 0 || parsedMemorySeries.value.length > 0 || parsedDiskSeries.value.length > 0,
)

const trendDomain = computed<[number, number]>(() => {
  const allTs = [
    ...parsedCpuSeries.value.map((p) => p.ts),
    ...parsedMemorySeries.value.map((p) => p.ts),
    ...parsedDiskSeries.value.map((p) => p.ts),
  ]
  if (!allTs.length) {
    const now = Date.now()
    return [now - 24 * 60 * 60 * 1000, now]
  }
  const minTs = Math.min(...allTs)
  const maxTs = Math.max(...allTs)
  if (minTs === maxTs) {
    return [minTs - 60 * 1000, maxTs + 60 * 1000]
  }
  return [minTs, maxTs]
})

function xFromTs(ts: number): number {
  const [minTs, maxTs] = trendDomain.value
  const innerW = chart.width - chart.paddingLeft - chart.paddingRight
  const ratio = (ts - minTs) / (maxTs - minTs)
  return chart.paddingLeft + ratio * innerW
}

function yFromValue(value: number): number {
  const innerH = chart.height - chart.paddingTop - chart.paddingBottom
  return chart.height - chart.paddingBottom - (Math.max(0, Math.min(100, value)) / 100) * innerH
}

function buildPolyline(series: { ts: number; value: number }[]): string {
  if (!series.length) {
    return ''
  }
  return series.map((point) => `${xFromTs(point.ts).toFixed(2)},${yFromValue(point.value).toFixed(2)}`).join(' ')
}

function buildSmoothPath(series: { ts: number; value: number }[]): string {
  if (!series.length) {
    return ''
  }
  const points = series.map((point) => ({ x: xFromTs(point.ts), y: yFromValue(point.value) }))
  if (points.length < 3) {
    return points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`).join(' ')
  }
  let path = `M ${points[0]!.x.toFixed(2)} ${points[0]!.y.toFixed(2)}`
  for (let i = 0; i < points.length - 1; i += 1) {
    const p0 = points[i - 1] ?? points[i]!
    const p1 = points[i]!
    const p2 = points[i + 1]!
    const p3 = points[i + 2] ?? p2
    const c1x = p1.x + (p2.x - p0.x) / 6
    const c1y = p1.y + (p2.y - p0.y) / 6
    const c2x = p2.x - (p3.x - p1.x) / 6
    const c2y = p2.y - (p3.y - p1.y) / 6
    path += ` C ${c1x.toFixed(2)} ${c1y.toFixed(2)}, ${c2x.toFixed(2)} ${c2y.toFixed(2)}, ${p2.x.toFixed(2)} ${p2.y.toFixed(2)}`
  }
  return path
}

function formatTickTime(ts: number): string {
  const d = new Date(ts)
  const [start, end] = trendDomain.value
  const spanHours = (end - start) / (1000 * 60 * 60)
  if (spanHours > 24) {
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    const h = String(d.getHours()).padStart(2, '0')
    const mm = String(d.getMinutes()).padStart(2, '0')
    return `${m}-${day} ${h}:${mm}`
  }
  const h = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${h}:${mm}`
}

const xTicks = computed(() => {
  const [minTs, maxTs] = trendDomain.value
  const count = 6
  const ticks: { x: number; y: number; label: string }[] = []
  for (let i = 0; i < count; i += 1) {
    const ratio = i / (count - 1)
    const ts = minTs + (maxTs - minTs) * ratio
    ticks.push({
      x: xFromTs(ts),
      y: chart.height - chart.paddingBottom + 18,
      label: formatTickTime(ts),
    })
  }
  return ticks
})

const yTicks = computed(() => {
  const values = [0, 20, 40, 60, 80, 100]
  return values.map((value) => ({
    value,
    y: yFromValue(value),
  }))
})

const cpuPolyline = computed(() => buildPolyline(parsedCpuSeries.value))
const memoryPolyline = computed(() => buildPolyline(parsedMemorySeries.value))
const diskSmoothPath = computed(() => buildSmoothPath(parsedDiskSeries.value))

const trendLatestTime = computed(() => {
  const candidates = [
    trendCpuSeries.value[trendCpuSeries.value.length - 1]?.time,
    trendMemorySeries.value[trendMemorySeries.value.length - 1]?.time,
    trendDiskSeries.value[trendDiskSeries.value.length - 1]?.time,
  ].filter(Boolean) as string[]
  if (!candidates.length) return '--'
  const maxTs = candidates
    .map((item) => Date.parse(item))
    .filter((item) => !Number.isNaN(item))
    .sort((a, b) => b - a)[0]
  if (maxTs == null) return '--'
  return toDateTimeString(new Date(maxTs))
})

async function loadTrendMetrics() {
  if (!selectedTrendServer.value) {
    trendCpuSeries.value = []
    trendMemorySeries.value = []
    trendDiskSeries.value = []
    return
  }

  const now = new Date()
  const startTime = new Date(now.getTime() - 24 * 60 * 60 * 1000)
  const start = toDateTimeString(startTime)
  const end = toDateTimeString(now)
  const window = calcWindow(start, end)
  const role = selectedTrendServer.value.role || 'host'
  const name = selectedTrendServer.value.host
  const primaryHost = selectedTrendServer.value.queryHost || selectedTrendServer.value.host
  const host = encodeURIComponent(primaryHost)

  loadingTrend.value = true
  try {
    const [cpu, memory, disk] = await Promise.all([
      getApi<MetricPoint[]>(`/api/cpu/history/${host}`, { start, end, window, role, name }),
      getApi<MetricPoint[]>(`/api/memory/history/${host}`, { start, end, window, role, name }),
      getApi<MetricPoint[]>(`/api/disk/history/${host}`, { start, end, window, role, name }),
    ])
    trendCpuSeries.value = normalizePercentSeries(cpu)
    trendMemorySeries.value = normalizePercentSeries(memory)
    trendDiskSeries.value = smoothStorageSeries(normalizePercentSeries(disk))
  } catch (error) {
    trendCpuSeries.value = []
    trendMemorySeries.value = []
    trendDiskSeries.value = []
    ElMessage.error(error instanceof Error ? error.message : '加载趋势数据失败')
  } finally {
    loadingTrend.value = false
  }
}

async function loadServers() {
  loadingServers.value = true
  try {
    const nodes = await getApi<TopologyNode[] | null>('/api/hosts/topology')
    serverNodes.value = Array.isArray(nodes) ? nodes : []
    if (selectedTrendServerHost.value && !allServers.value.some((node) => node.host === selectedTrendServerHost.value)) {
      selectedTrendServerHost.value = ''
    }
    if (!selectedTrendServerHost.value && allServers.value.length) {
      const firstServer = allServers.value[0]
      if (firstServer) {
        selectedTrendServerHost.value = firstServer.host
      }
    }
    updatedAt.value = toDateTimeString(new Date())
    void Promise.allSettled([loadTrendMetrics(), loadTodayAlertSummary(), loadSmartAlertSummary(), loadRecentAlerts()])
  } catch (error) {
    const message = error instanceof Error ? error.message : '加载服务器列表失败'
    ElMessage.error(message)
  } finally {
    loadingServers.value = false
  }
}

async function loadTodayAlertSummary() {
  try {
    const summary = await getApi<AlertTodaySummary>('/api/alert-history/today-summary')
    todayAlertCount.value = summary.totalCount
    todayFiringCount.value = summary.firingCount
    todayResolvedCount.value = summary.resolvedCount
    todayCriticalCount.value = summary.criticalCount
    todayAlertLevelCount.value = summary.alertCount
    todayWarningCount.value = summary.warningCount
  } catch (error) {
    todayAlertCount.value = null
    todayFiringCount.value = null
    todayResolvedCount.value = null
    todayCriticalCount.value = null
    todayAlertLevelCount.value = null
    todayWarningCount.value = null
    ElMessage.error(error instanceof Error ? error.message : '加载今日告警统计失败')
  }
}

async function loadSmartAlertSummary() {
  try {
    const summary = await getApi<AlertFeedbackSummary>('/api/alert-history/feedback-summary')
    effectiveAlertCount.value = summary.effectiveCount
    falsePositiveCount.value = summary.falsePositiveCount
    feedbackSampleCount.value = summary.totalCount + summary.unresolvedCount
    feedbackCoverageRate.value =
      summary.totalCount + summary.unresolvedCount > 0
        ? Math.round((summary.totalCount / (summary.totalCount + summary.unresolvedCount)) * 100)
        : null
    hitRate.value = summary.totalCount > 0 ? Math.round((summary.effectiveRate || 0) * 100) : null
  } catch (error) {
    effectiveAlertCount.value = null
    falsePositiveCount.value = null
    feedbackSampleCount.value = null
    feedbackCoverageRate.value = null
    hitRate.value = null
    ElMessage.error(error instanceof Error ? error.message : '加载智能告警反馈统计失败')
  }
}

async function loadRecentAlerts() {
  loadingRecentAlerts.value = true
  try {
    const result = await getApi<PagedResult<AlertHistoryItem>>('/api/alert-history', {
      page: '1',
      size: '6',
    })
    recentAlerts.value = result.items
  } catch (error) {
    recentAlerts.value = []
    ElMessage.error(error instanceof Error ? error.message : '加载最近告警失败')
  } finally {
    loadingRecentAlerts.value = false
  }
}

onMounted(loadServers)

useGlobalRefresh(async () => {
  if (loadingServers.value) {
    return
  }
  await loadServers()
}, { minGapMs: 1200 })
</script>

<template>
  <div class="dashboard">
    <el-row :gutter="28" class="top-kpi-row">
      <el-col :xs="24" :sm="24" :md="10" :lg="10" :xl="10" class="top-kpi-col">
        <el-card class="hero-card" shadow="hover" @click="goToMonitoring" v-loading="loadingServers">
          <div class="hero-top">
            <div>
              <div class="hero-title">服务器总览</div>
            </div>
            <el-button text type="primary" :icon="Refresh" :loading="loadingServers" @click.stop="loadServers">刷新</el-button>
          </div>
          <div class="hero-metrics">
            <div class="hero-item">
              <span>在线</span>
              <strong>{{ onlineServerCount }}</strong>
            </div>
            <div class="hero-item">
              <span>离线</span>
              <strong>{{ offlineServerCount }}</strong>
            </div>
            <div class="hero-item">
              <span>总数</span>
              <strong>{{ totalServerCount }}</strong>
            </div>
          </div>
          <div class="hero-progress">
            <span class="progress-label">在线率</span>
            <el-progress :percentage="onlineRate" :stroke-width="10" :show-text="true" />
          </div>
          <div class="hero-footer">最后刷新：{{ updatedAt || '--' }}</div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="7" :lg="7" :xl="7" class="top-kpi-col">
        <el-card class="today-alert-card" shadow="hover">
          <div class="today-head">
            <div class="today-title-wrap">
              <el-icon class="today-icon"><WarningFilled /></el-icon>
              <div>
                <div class="today-title">今日告警</div>
              </div>
            </div>
          </div>

          <div class="today-main">
            <div class="today-total" :class="todayTotalClass">{{ todayAlertCount ?? '--' }}</div>
            <div class="today-recovery">
              <div class="recovery-pill unresolved">
                <span>未恢复</span>
                <strong>{{ todayFiringCount ?? '--' }}</strong>
              </div>
              <div class="recovery-pill resolved">
                <span>已恢复</span>
                <strong>{{ todayResolvedCount ?? '--' }}</strong>
              </div>
            </div>
          </div>

          <div class="today-level-grid">
            <div class="level-item critical">
              <span>严重</span>
              <strong>{{ todayCriticalCount ?? '--' }}</strong>
            </div>
            <div class="level-item alert">
              <span>警报</span>
              <strong>{{ todayAlertLevelCount ?? '--' }}</strong>
            </div>
            <div class="level-item warning">
              <span>警告</span>
              <strong>{{ todayWarningCount ?? '--' }}</strong>
            </div>
          </div>

        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="7" :lg="7" :xl="7" class="top-kpi-col">
        <el-card class="smart-alert-card" shadow="hover">
          <div class="smart-head">
            <div class="smart-title-wrap">
              <el-icon class="smart-icon"><Aim /></el-icon>
              <div>
                <div class="smart-title">智能告警</div>
              </div>
            </div>
          </div>

          <div class="smart-main">
            <div class="smart-rate" :class="smartRateClass">{{ hitRate != null ? hitRate + '%' : '--' }}</div>
            <div class="smart-rate-text">命中率</div>
          </div>

          <div class="smart-progress">
            <div class="smart-progress-fill" :style="{ width: `${Math.max(0, Math.min(100, hitRate ?? 0))}%` }"></div>
          </div>

          <div class="smart-feedback-grid">
            <div class="feedback-item effective">
              <span>有效告警</span>
              <strong>{{ effectiveAlertCount ?? '--' }}</strong>
            </div>
            <div class="feedback-item false-positive">
              <span>误告</span>
              <strong>{{ falsePositiveCount ?? '--' }}</strong>
            </div>
          </div>
          <div class="smart-footnote">
            反馈覆盖率 {{ feedbackCoverageRate != null ? `${feedbackCoverageRate}%` : '--' }} · 样本
            {{ feedbackSampleCount ?? '--' }}
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="dashboard-row monitoring-row">
      <el-col :xs="24" :sm="24" :md="16" :lg="16" :xl="16">
        <el-card class="panel-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <span>服务器性能展示</span>
              <div class="trend-toolbar">
                <el-select
                  v-model="selectedTrendServerHost"
                  size="small"
                  placeholder="选择服务器"
                  style="width: 220px"
                  :loading="loadingServers"
                  @change="loadTrendMetrics"
                >
                  <el-option
                    v-for="server in allServers"
                    :key="server.host"
                    :label="server.host"
                    :value="server.host"
                  />
                </el-select>
                <el-tag type="info" effect="plain">最近24小时</el-tag>
              </div>
            </div>
          </template>
          <div class="trend-wrap" v-loading="loadingTrend">
            <svg
              v-if="trendHasSeries"
              class="trend-chart"
              :viewBox="`0 0 ${chart.width} ${chart.height}`"
              preserveAspectRatio="none"
            >
              <line
                :x1="chart.paddingLeft"
                :y1="chart.paddingTop"
                :x2="chart.paddingLeft"
                :y2="chart.height - chart.paddingBottom"
                class="axis-line"
              />
              <line
                :x1="chart.paddingLeft"
                :y1="chart.height - chart.paddingBottom"
                :x2="chart.width - chart.paddingRight"
                :y2="chart.height - chart.paddingBottom"
                class="axis-line"
              />
              <line
                v-for="tick in yTicks"
                :key="`y-grid-${tick.value}`"
                :x1="chart.paddingLeft"
                :y1="tick.y"
                :x2="chart.width - chart.paddingRight"
                :y2="tick.y"
                class="grid-line"
              />
              <text
                v-for="tick in yTicks"
                :key="`y-label-${tick.value}`"
                :x="chart.paddingLeft - 8"
                :y="tick.y + 4"
                class="tick-label tick-label-y"
              >
                {{ tick.value }}%
              </text>
              <line
                v-for="tick in xTicks"
                :key="`x-grid-${tick.label}-${tick.x}`"
                :x1="tick.x"
                :y1="chart.paddingTop"
                :x2="tick.x"
                :y2="chart.height - chart.paddingBottom"
                class="grid-line-x"
              />
              <text
                v-for="tick in xTicks"
                :key="`x-label-${tick.label}-${tick.x}`"
                :x="tick.x"
                :y="tick.y"
                class="tick-label tick-label-x"
              >
                {{ tick.label }}
              </text>
              <polyline v-if="cpuPolyline" :points="cpuPolyline" class="line-cpu" />
              <polyline v-if="memoryPolyline" :points="memoryPolyline" class="line-memory" />
              <path v-if="diskSmoothPath" :d="diskSmoothPath" class="line-disk" />
              <text
                :x="16"
                :y="chart.height / 2"
                :transform="`rotate(-90 16 ${chart.height / 2})`"
                class="axis-title axis-title-y"
              >
                利用率
              </text>
              <text
                :x="chart.width - chart.paddingRight - 24"
                :y="chart.height - 10"
                class="axis-title"
              >
                时间
              </text>
            </svg>
            <el-empty v-else description="所选服务器暂无CPU/内存/存储曲线数据" />
            <div class="trend-legend">
              <span><i class="legend-dot cpu"></i>CPU</span>
              <span><i class="legend-dot memory"></i>内存</span>
              <span><i class="legend-dot disk"></i>存储</span>
              <span class="legend-time">最新时间：{{ trendLatestTime }}</span>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="24" :md="8" :lg="8" :xl="8">
        <el-card class="panel-card server-status-card" shadow="hover" v-loading="loadingServers">
          <template #header>
            <div class="card-header">
              <span>服务器状态</span>
              <el-icon><Connection /></el-icon>
            </div>
          </template>

          <div class="server-scroll-area">
            <div v-if="recentServers.length" class="server-list">
              <div v-for="node in recentServers" :key="node.host" class="server-row">
                <div class="server-main">
                  <span class="server-name">{{ node.host }}</span>
                  <el-tag
                    size="small"
                    :type="statusTagType(statusOf(node.lastSeen))"
                  >
                    {{ statusLabel(statusOf(node.lastSeen)) }}
                  </el-tag>
                </div>
                <div class="server-meta">{{ formatAgo(node.lastSeen) }} · {{ formatTime(node.lastSeen) }}</div>
              </div>
            </div>
            <el-empty v-else description="暂无服务器数据" />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="dashboard-row">
      <el-col :span="24">
        <el-card class="panel-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <span>最近告警</span>
              <div class="recent-alert-header-actions">
                <el-tag type="info" effect="plain">最新 {{ recentAlerts.length }} 条</el-tag>
                <el-button text type="primary" @click="goToAlertHistory">查看全部</el-button>
              </div>
            </div>
          </template>
          <div v-if="recentAlerts.length" class="recent-alert-list" v-loading="loadingRecentAlerts">
            <div v-for="item in recentAlerts" :key="item.id" class="recent-alert-row">
              <div class="recent-alert-main">
                <div class="recent-alert-left">
                  <el-tag size="small" :type="levelTagType(item.level)">{{ levelText(item.level) }}</el-tag>
                  <span class="recent-alert-rule">{{ item.rule || 'AI Intelligent Alert' }}</span>
                  <el-tag size="small" effect="plain" :type="thresholdTagType(item.thresholdType)">
                    {{ thresholdTypeText(item.thresholdType) }}
                  </el-tag>
                </div>
                <div class="recent-alert-right">
                  <span class="recent-alert-time">{{ formatTime(item.time) }}</span>
                  <el-tag size="small" :type="alertStatusType(item.status)">{{ alertStatusText(item.status) }}</el-tag>
                </div>
              </div>
              <div class="recent-alert-meta">
                <span>对象：{{ item.host || '--' }}</span>
                <span>指标：{{ item.metricName || '--' }}</span>
              </div>
              <div class="recent-alert-reason">{{ reasonSnippet(item.reason) }}</div>
            </div>
          </div>
          <el-empty v-else description="暂无最近告警" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.dashboard {
  --title: #1b2b4d;
  --top-row-height: 292px;
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 8px 4px 4px;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.top-kpi-row {
  align-items: stretch;
  margin-bottom: 2px;
}

.top-kpi-col {
  display: flex;
}

.top-kpi-col :deep(.el-card) {
  width: 100%;
  height: 100%;
  border-radius: 18px;
  border: 1px solid var(--line);
  background: linear-gradient(145deg, var(--glass-1), var(--glass-2));
  backdrop-filter: blur(8px);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.8),
    0 10px 26px rgba(47, 68, 111, 0.14);
  overflow: hidden;
  animation: riseIn 0.55s ease both;
}

.top-kpi-col :deep(.el-card__body) {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 24px 24px 22px;
}

.top-kpi-col:nth-child(2) :deep(.el-card) {
  animation-delay: 0.08s;
}

.top-kpi-col:nth-child(3) :deep(.el-card) {
  animation-delay: 0.16s;
}

.hero-card {
  min-height: var(--top-row-height);
  cursor: pointer;
  position: relative;
  background:
    linear-gradient(132deg, #f5faff, #ecf5ff) padding-box,
    linear-gradient(132deg, rgba(56, 189, 248, 0.62), rgba(59, 130, 246, 0.26)) border-box;
}

.hero-card::before {
  content: '';
  position: absolute;
  inset: -46% -35% auto auto;
  width: 260px;
  height: 220px;
  background: radial-gradient(circle, rgba(59, 130, 246, 0.14), rgba(59, 130, 246, 0));
  pointer-events: none;
}

.hero-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.hero-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--title);
  letter-spacing: 0.3px;
}

.hero-metrics {
  margin-top: 22px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.hero-item {
  padding: 12px 14px 11px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(110, 139, 192, 0.2);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.hero-item span {
  font-size: 12px;
  color: #6f83a7;
}

.hero-item strong {
  font-size: 24px;
  color: #203153;
  line-height: 1;
}

.hero-progress {
  margin-top: 18px;
}

.progress-label {
  display: inline-block;
  margin-bottom: 8px;
  color: #6f83a7;
  font-size: 12px;
}

.hero-footer {
  margin-top: auto;
  padding-top: 12px;
  font-size: 12px;
  color: #6f83a7;
}

.kpi-card,
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

.today-alert-card {
  min-height: var(--top-row-height);
  background:
    linear-gradient(132deg, #fff8ef, #fff3e0) padding-box,
    linear-gradient(132deg, rgba(251, 146, 60, 0.55), rgba(245, 158, 11, 0.25)) border-box;
}

.today-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.today-title-wrap {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.today-icon {
  font-size: 18px;
  color: #f97316;
  margin-top: 2px;
}

.today-title {
  font-size: 22px;
  font-weight: 700;
  color: #7c2d12;
  letter-spacing: 0.2px;
}

.today-main {
  margin-top: 22px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
}

.today-total {
  font-size: 42px;
  font-weight: 700;
  line-height: 1;
  color: #ea580c;
  text-shadow: 0 0 12px rgba(251, 146, 60, 0.16);
}

.today-recovery {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 150px;
}

.recovery-pill {
  border-radius: 12px;
  padding: 9px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border: 1px solid rgba(251, 146, 60, 0.24);
  background: rgba(255, 255, 255, 0.76);
}

.recovery-pill span {
  font-size: 12px;
  color: #9a3412;
}

.recovery-pill strong {
  font-size: 15px;
  line-height: 1;
}

.recovery-pill.unresolved strong {
  color: #c2410c;
}

.recovery-pill.resolved strong {
  color: #15803d;
}

.today-level-grid {
  margin-top: auto;
  padding-top: 18px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.level-item {
  border-radius: 12px;
  padding: 10px 12px;
  border: 1px solid rgba(251, 146, 60, 0.24);
  background: rgba(255, 255, 255, 0.72);
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 84px;
}

.level-item span {
  font-size: 12px;
  color: #9a3412;
}

.level-item strong {
  font-size: 22px;
  line-height: 1;
}

.level-item.critical strong {
  color: #dc2626;
}

.level-item.alert strong {
  color: #f97316;
}

.level-item.warning strong {
  color: #ca8a04;
}

.smart-alert-card {
  min-height: var(--top-row-height);
  background:
    linear-gradient(132deg, #f2fbff, #ecfff8) padding-box,
    linear-gradient(132deg, rgba(16, 185, 129, 0.45), rgba(56, 189, 248, 0.28)) border-box;
}

.smart-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.smart-title-wrap {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.smart-icon {
  font-size: 18px;
  color: #0ea5e9;
  margin-top: 2px;
}

.smart-title {
  font-size: 22px;
  font-weight: 700;
  color: #0f3b53;
  letter-spacing: 0.2px;
}

.smart-main {
  margin-top: 22px;
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.smart-rate {
  font-size: 40px;
  font-weight: 700;
  line-height: 1;
}

.smart-rate-text {
  font-size: 13px;
  color: #5f788f;
}

.smart-progress {
  margin-top: 16px;
  width: 100%;
  height: 10px;
  border-radius: 999px;
  background: rgba(59, 130, 246, 0.14);
  overflow: hidden;
  box-shadow: inset 0 0 0 1px rgba(56, 189, 248, 0.2);
}

.smart-progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #22c55e, #14b8a6 42%, #0ea5e9);
  transition: width 0.45s ease;
  position: relative;
}

.smart-progress-fill::after {
  content: '';
  position: absolute;
  top: 0;
  left: -40%;
  width: 40%;
  height: 100%;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.55), transparent);
  animation: barShimmer 2.2s ease-in-out infinite;
}

.smart-feedback-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.feedback-item {
  border-radius: 12px;
  padding: 10px 12px 9px;
  border: 1px solid rgba(56, 189, 248, 0.2);
  background: rgba(255, 255, 255, 0.72);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.feedback-item span {
  font-size: 12px;
  color: #5f788f;
}

.feedback-item strong {
  font-size: 22px;
  line-height: 1;
}

.feedback-item.effective strong {
  color: #059669;
}

.feedback-item.false-positive strong {
  color: #d97706;
}

.smart-footnote {
  margin-top: auto;
  padding-top: 14px;
  font-size: 12px;
  color: #5f788f;
}

.today-total.warning,
.smart-rate.warning {
  color: #ea580c;
}

.today-total.success,
.smart-rate.success {
  color: #16a34a;
}

.today-total.danger,
.smart-rate.danger {
  color: #dc2626;
}

.dashboard-row {
  margin-top: 2px;
}

.monitoring-row {
  align-items: stretch;
}

.monitoring-row > .el-col {
  display: flex;
}

.monitoring-row > .el-col .panel-card {
  width: 100%;
  height: 100%;
}

.monitoring-row > .el-col .panel-card :deep(.el-card__body) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  color: #25385c;
  gap: 12px;
  letter-spacing: 0.2px;
}

.trend-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.trend-wrap {
  border: 1px solid var(--line-soft);
  border-radius: 14px;
  padding: 12px 12px 10px;
  background: linear-gradient(180deg, #f6faff, #f1f7ff);
}

.trend-chart {
  width: 100%;
  height: 300px;
}

.axis-line {
  stroke: rgba(100, 116, 139, 0.34);
  stroke-width: 1.2;
}

.grid-line {
  stroke: rgba(148, 163, 184, 0.22);
  stroke-width: 1;
  stroke-dasharray: 4 4;
}

.grid-line-x {
  stroke: rgba(148, 163, 184, 0.12);
  stroke-width: 1;
}

.tick-label {
  fill: #7182a2;
  font-size: 11px;
}

.tick-label-y {
  text-anchor: end;
}

.tick-label-x {
  text-anchor: middle;
}

.axis-title {
  fill: #5f708f;
  font-size: 12px;
  font-weight: 600;
}

.axis-title-y {
  text-anchor: middle;
}

.line-cpu {
  fill: none;
  stroke: #3b82f6;
  stroke-width: 2;
}

.line-memory {
  fill: none;
  stroke: #10b981;
  stroke-width: 2;
}

.line-disk {
  fill: none;
  stroke: #f97316;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.trend-legend {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 12px;
  color: #6e7f9f;
}

.legend-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
}

.legend-dot.cpu {
  background: #3b82f6;
}

.legend-dot.memory {
  background: #10b981;
}

.legend-dot.disk {
  background: #f97316;
}

.legend-time {
  margin-left: auto;
}

.server-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.server-status-card {
  min-height: 420px;
}

.server-scroll-area {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
}

.server-scroll-area :deep(.el-empty) {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.server-row {
  padding: 10px 12px;
  border: 1px solid rgba(110, 139, 192, 0.2);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.72);
}

.server-main {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}

.server-name {
  color: #1f2f4f;
  font-weight: 600;
}

.server-meta {
  margin-top: 6px;
  font-size: 12px;
  color: #7082a3;
}

.recent-alert-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.recent-alert-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.recent-alert-row {
  border: 1px solid rgba(110, 139, 192, 0.2);
  background: rgba(255, 255, 255, 0.72);
  border-radius: 12px;
  padding: 10px 12px;
}

.recent-alert-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.recent-alert-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.recent-alert-rule {
  font-size: 13px;
  color: #1f2f4f;
  font-weight: 600;
}

.recent-alert-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.recent-alert-time {
  font-size: 12px;
  color: #6f83a7;
}

.recent-alert-meta {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 14px;
  color: #7082a3;
  font-size: 12px;
}

.recent-alert-reason {
  margin-top: 6px;
  color: #4a5f83;
  font-size: 12px;
  line-height: 1.45;
}

.panel-card :deep(.el-card__header) {
  border-bottom: 1px solid var(--line-soft);
  background: rgba(244, 248, 255, 0.86);
}

.panel-card :deep(.el-card__body) {
  background: transparent;
}

.panel-card :deep(.el-empty__description p) {
  color: #7587a7;
}

.trend-toolbar :deep(.el-select .el-input__wrapper) {
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 0 0 1px rgba(110, 139, 192, 0.2) inset;
}

.trend-toolbar :deep(.el-select .el-input__inner) {
  color: #1f2f4f;
}

.hero-card :deep(.el-progress-bar__outer) {
  background-color: rgba(59, 130, 246, 0.14);
}

.hero-card :deep(.el-progress-bar__inner) {
  background: linear-gradient(90deg, #38bdf8, #2563eb);
}

.hero-card :deep(.el-progress__text) {
  color: #2f466f;
}

.hero-card :deep(.el-button.is-text) {
  color: #2563eb;
}

.hero-card :deep(.el-button.is-text:hover) {
  color: #1d4ed8;
  background: rgba(59, 130, 246, 0.1);
}

:deep(.el-tag.el-tag--success) {
  border-color: rgba(34, 197, 94, 0.3);
  background: rgba(34, 197, 94, 0.1);
  color: #15803d;
}

:deep(.el-tag.el-tag--warning) {
  border-color: rgba(245, 158, 11, 0.3);
  background: rgba(245, 158, 11, 0.1);
  color: #b45309;
}

:deep(.el-tag.el-tag--danger) {
  border-color: rgba(239, 68, 68, 0.3);
  background: rgba(239, 68, 68, 0.1);
  color: #b91c1c;
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

@keyframes barShimmer {
  0% {
    left: -40%;
    opacity: 0;
  }
  35% {
    opacity: 1;
  }
  100% {
    left: 120%;
    opacity: 0;
  }
}

@media (max-width: 992px) {
  .hero-card,
  .kpi-card {
    min-height: auto;
  }

  .hero-metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .trend-toolbar {
    width: 100%;
  }

  .dashboard {
    gap: 18px;
  }
}

@media (max-width: 640px) {
  .hero-metrics {
    grid-template-columns: 1fr;
  }

  .card-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .trend-toolbar {
    width: 100%;
    justify-content: space-between;
  }

  .legend-time {
    margin-left: 0;
    width: 100%;
  }

  .top-kpi-col :deep(.el-card__body) {
    padding: 20px 18px;
  }
}
</style>
