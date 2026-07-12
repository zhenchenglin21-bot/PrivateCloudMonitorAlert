<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
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
}

interface MetricPoint {
  time: string
  value: number
}

interface StatRow {
  metric: string
  count: number
  latest: string
  average: string
  max: string
  min: string
}

type QuickRangeKey = '6h' | '24h' | '48h' | '72h' | 'custom'

const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'
const loginPath = '/login'
const loading = ref(false)
const topologyNodes = ref<TopologyNode[]>([])
const selectedHost = ref('')
const timeRange = ref<[string, string]>([
  toDateTimeString(new Date(Date.now() - 48 * 60 * 60 * 1000)),
  toDateTimeString(new Date()),
])

const cpuSeries = ref<MetricPoint[]>([])
const memorySeries = ref<MetricPoint[]>([])
const diskSeries = ref<MetricPoint[]>([])
const netInSeries = ref<MetricPoint[]>([])
const netOutSeries = ref<MetricPoint[]>([])

const servers = computed(() => topologyNodes.value.filter((item) => ['host', 'server'].includes((item.role || '').toLowerCase())))
const currentServer = computed(() => servers.value.find((item) => item.host === selectedHost.value) ?? servers.value[0])

const queryWindow = computed(() => calcWindow(timeRange.value[0], timeRange.value[1]))

const quickRangeKey = computed<QuickRangeKey>(() => {
  const [start, end] = timeRange.value
  const s = Date.parse(start)
  const e = Date.parse(end)
  if (Number.isNaN(s) || Number.isNaN(e) || e <= s) {
    return 'custom'
  }
  const diffHours = (e - s) / (1000 * 60 * 60)
  if (Math.abs(diffHours - 6) < 0.03) return '6h'
  if (Math.abs(diffHours - 24) < 0.03) return '24h'
  if (Math.abs(diffHours - 48) < 0.03) return '48h'
  if (Math.abs(diffHours - 72) < 0.03) return '72h'
  return 'custom'
})

function toDateTimeString(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')
  const ss = String(date.getSeconds()).padStart(2, '0')
  return `${y}-${m}-${d} ${hh}:${mm}:${ss}`
}

function formatDateTime(value?: string | null): string {
  if (!value) {
    return '--'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '--'
  }
  return toDateTimeString(date)
}

function calcWindow(start: string, end: string): string {
  const s = new Date(start).getTime()
  const e = new Date(end).getTime()
  const hours = Math.abs(e - s) / (1000 * 60 * 60)
  if (hours > 96) return '30m'
  if (hours > 24) return '10m'
  return '1m'
}

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

function fmt(value: number | null, suffix = '%'): string {
  if (value == null) return '--'
  return `${value.toFixed(2)}${suffix}`
}

function seriesStats(series: MetricPoint[]) {
  if (!series.length) {
    return { count: 0, latest: null, average: null, max: null, min: null }
  }
  const values = series.map((item) => item.value)
  const sum = values.reduce((a, b) => a + b, 0)
  return {
    count: values.length,
    latest: values[values.length - 1] ?? null,
    average: sum / values.length,
    max: Math.max(...values),
    min: Math.min(...values),
  }
}

const statsTable = computed<StatRow[]>(() => {
  const cpu = seriesStats(cpuSeries.value)
  const memory = seriesStats(memorySeries.value)
  const disk = seriesStats(diskSeries.value)
  const netIn = seriesStats(netInSeries.value)
  const netOut = seriesStats(netOutSeries.value)
  return [
    { metric: 'CPU 使用率', count: cpu.count, latest: fmt(cpu.latest), average: fmt(cpu.average), max: fmt(cpu.max), min: fmt(cpu.min) },
    { metric: '内存使用率', count: memory.count, latest: fmt(memory.latest), average: fmt(memory.average), max: fmt(memory.max), min: fmt(memory.min) },
    { metric: '存储使用率', count: disk.count, latest: fmt(disk.latest), average: fmt(disk.average), max: fmt(disk.max), min: fmt(disk.min) },
    { metric: '网络入速率', count: netIn.count, latest: fmt(netIn.latest, ' Mbps'), average: fmt(netIn.average, ' Mbps'), max: fmt(netIn.max, ' Mbps'), min: fmt(netIn.min, ' Mbps') },
    { metric: '网络出速率', count: netOut.count, latest: fmt(netOut.latest, ' Mbps'), average: fmt(netOut.average, ' Mbps'), max: fmt(netOut.max, ' Mbps'), min: fmt(netOut.min, ' Mbps') },
  ]
})

const totalSamples = computed(() => statsTable.value.reduce((sum, row) => sum + row.count, 0))

const activeMetricCount = computed(() => statsTable.value.filter((row) => row.count > 0).length)

const latestSampleTime = computed(() => {
  const all = [...cpuSeries.value, ...memorySeries.value, ...diskSeries.value, ...netInSeries.value, ...netOutSeries.value]
  if (!all.length) {
    return '--'
  }
  const latest = all
    .map((item) => Date.parse(item.time))
    .filter((ts) => !Number.isNaN(ts))
    .sort((a, b) => b - a)[0]
  if (!latest) {
    return '--'
  }
  return formatDateTime(new Date(latest).toISOString())
})

function toMbpsSeries(series: MetricPoint[]): MetricPoint[] {
  return series.map((item) => ({ time: item.time, value: (item.value * 8) / 1_000_000 }))
}

function applyQuickRange(hours: number) {
  const end = new Date()
  const start = new Date(end.getTime() - hours * 60 * 60 * 1000)
  timeRange.value = [toDateTimeString(start), toDateTimeString(end)]
  void onQuery()
}

async function loadTopology() {
  const [start, end] = timeRange.value
  topologyNodes.value = await getApi<TopologyNode[]>('/api/hosts/topology', { start, end })
  if (selectedHost.value && !servers.value.some((item) => item.host === selectedHost.value)) {
    selectedHost.value = ''
  }
  if (!selectedHost.value && servers.value.length) {
    selectedHost.value = servers.value[0]!.host
  }
}

async function loadMetrics() {
  if (!currentServer.value) {
    cpuSeries.value = []
    memorySeries.value = []
    diskSeries.value = []
    netInSeries.value = []
    netOutSeries.value = []
    return
  }
  const [start, end] = timeRange.value
  const window = calcWindow(start, end)
  const role = currentServer.value.role || 'host'
  const name = currentServer.value.host
  const host = encodeURIComponent(currentServer.value.queryHost || currentServer.value.host)

  const [cpu, memory, disk, netIn, netOut] = await Promise.all([
    getApi<MetricPoint[]>(`/api/cpu/history/${host}`, { start, end, window, role, name }),
    getApi<MetricPoint[]>(`/api/memory/history/${host}`, { start, end, window, role, name }),
    getApi<MetricPoint[]>(`/api/disk/history/${host}`, { start, end, window, role, name }),
    getApi<MetricPoint[]>(`/api/network/history-smart/${host}`, { start, end, window, role, name, direction: 'in' }),
    getApi<MetricPoint[]>(`/api/network/history-smart/${host}`, { start, end, window, role, name, direction: 'out' }),
  ])

  cpuSeries.value = cpu
  memorySeries.value = memory
  diskSeries.value = disk
  netInSeries.value = toMbpsSeries(netIn)
  netOutSeries.value = toMbpsSeries(netOut)
}

async function onQuery() {
  loading.value = true
  try {
    await loadTopology()
    await loadMetrics()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '查询失败')
  } finally {
    loading.value = false
  }
}

onMounted(onQuery)

useGlobalRefresh(async () => {
  if (loading.value) {
    return
  }
  await onQuery()
}, { minGapMs: 1200 })
</script>

<template>
  <div class="page" v-loading="loading">
    <el-card class="toolbar-card panel-card" shadow="hover">
      <div class="toolbar-head">
        <div>
          <div class="toolbar-title">监控数据管理</div>
        </div>
        <div class="toolbar-head-right">
          <el-tag type="info" effect="plain">窗口粒度 {{ queryWindow }}</el-tag>
          <el-button text type="primary" :icon="Refresh" :loading="loading" @click="onQuery">刷新</el-button>
        </div>
      </div>

      <el-form :inline="true" label-width="86" class="toolbar-form">
        <el-form-item label="服务器">
          <el-select v-model="selectedHost" style="width: 220px" placeholder="选择服务器" @change="onQuery">
            <el-option v-for="item in servers" :key="item.host" :label="item.host" :value="item.host" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm:ss"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            style="width: 392px"
            @change="onQuery"
          />
        </el-form-item>
        <el-form-item label="快捷范围">
          <div class="quick-range-group">
            <el-button size="small" :type="quickRangeKey === '6h' ? 'primary' : 'default'" @click="applyQuickRange(6)">6小时</el-button>
            <el-button size="small" :type="quickRangeKey === '24h' ? 'primary' : 'default'" @click="applyQuickRange(24)">24小时</el-button>
            <el-button size="small" :type="quickRangeKey === '48h' ? 'primary' : 'default'" @click="applyQuickRange(48)">48小时</el-button>
            <el-button size="small" :type="quickRangeKey === '72h' ? 'primary' : 'default'" @click="applyQuickRange(72)">3天</el-button>
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="onQuery">查询统计</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-row :gutter="16" class="summary-row">
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">当前监控对象</div>
          <div class="kpi-value host">{{ currentServer?.host || '--' }}</div>
          <div class="kpi-meta">角色：{{ currentServer?.role || '--' }}</div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">有效指标类型</div>
          <div class="kpi-main">
            <div class="kpi-value success">{{ activeMetricCount }}</div>
            <span class="kpi-unit">/ 5</span>
          </div>
          <div class="kpi-meta">有数据即计入有效指标</div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">总样本数</div>
          <div class="kpi-value primary">{{ totalSamples }}</div>
          <div class="kpi-meta">5 类指标样本汇总</div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">最新采样时间</div>
          <div class="kpi-value time">{{ latestSampleTime }}</div>
          <div class="kpi-meta">用于判断数据新鲜度</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="panel-card table-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <div class="card-title-wrap">
            <span class="card-title">指标统计汇总</span>
            <span class="path-text">{{ currentServer?.host || '--' }}</span>
          </div>
          <div class="table-head-actions">
            <el-tag size="small" type="info" effect="plain">窗口 {{ queryWindow }}</el-tag>
            <el-tag size="small" type="success" effect="plain">样本 {{ totalSamples }}</el-tag>
          </div>
        </div>
      </template>

      <el-table :data="statsTable" border stripe class="stats-table">
        <el-table-column prop="metric" label="指标" min-width="160" />
        <el-table-column prop="count" label="样本数" width="110" align="center">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.count > 0 ? 'success' : 'info'">{{ scope.row.count }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latest" label="最新值" min-width="150" />
        <el-table-column prop="average" label="平均值" min-width="150" />
        <el-table-column prop="max" label="峰值" min-width="150" />
        <el-table-column prop="min" label="最小值" min-width="150" />
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

.panel-card :deep(.el-empty__description p) {
  color: #7587a7;
}

.toolbar-card :deep(.el-card__body) {
  padding: 18px 20px 14px;
}

.toolbar-head {
  display: flex;
  align-items: flex-start;
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

.toolbar-form {
  margin-top: 12px;
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 10px;
}

.toolbar-form :deep(.el-form-item) {
  margin: 0;
}

.toolbar-form :deep(.el-input__wrapper),
.toolbar-form :deep(.el-select__wrapper),
.toolbar-form :deep(.el-textarea__inner),
.toolbar-form :deep(.el-date-editor.el-input__wrapper),
.toolbar-form :deep(.el-date-editor .el-range-input) {
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 0 0 1px rgba(110, 139, 192, 0.2) inset;
}

.quick-range-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
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
  font-size: 28px;
  font-weight: 700;
  color: #1d3158;
  line-height: 1.1;
  word-break: break-all;
}

.kpi-value.host {
  font-size: 24px;
  color: #1f2f4f;
}

.kpi-value.primary {
  color: #2563eb;
}

.kpi-value.success {
  color: #16a34a;
}

.kpi-value.time {
  font-size: 18px;
  color: #0f3c64;
}

.kpi-unit {
  color: #6e7f9f;
  font-size: 12px;
}

.kpi-meta {
  margin-top: 8px;
  font-size: 12px;
  color: #7284a6;
}

.table-card :deep(.el-card__body) {
  padding: 12px 12px 10px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-weight: 600;
  color: #25385c;
  letter-spacing: 0.2px;
}

.card-title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.card-title {
  font-size: 15px;
  font-weight: 700;
  color: #233760;
}

.path-text {
  color: #6f7f9e;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.stats-table :deep(.el-table__header-wrapper th) {
  background: #f4f8ff;
}

.stats-table :deep(.el-table__row td) {
  background: rgba(255, 255, 255, 0.68);
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

@media (max-width: 1100px) {
  .toolbar-head {
    flex-direction: column;
    align-items: flex-start;
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

  .toolbar-form {
    flex-wrap: wrap;
  }

  .toolbar-form :deep(.el-form-item) {
    width: 100%;
    margin-right: 0;
    margin-bottom: 8px;
  }

  .card-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .card-title-wrap {
    width: 100%;
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }
}
</style>
