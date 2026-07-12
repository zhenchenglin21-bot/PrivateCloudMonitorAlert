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
  lastSeen?: string | null
}

interface MetricPoint {
  time: string
  value: number
}

type QuickRangeKey = '1h' | '6h' | '24h' | '72h' | 'custom'

const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'
const ONLINE_THRESHOLD_MS = 10 * 60 * 1000
const loginPath = '/login'

const loading = ref(false)
const servers = ref<TopologyNode[]>([])
const selectedHost = ref('')
const timeRange = ref<[string, string]>([
  toDateTimeString(new Date(Date.now() - 24 * 60 * 60 * 1000)),
  toDateTimeString(new Date()),
])

const influxStatus = ref<'online' | 'offline'>('offline')
const networkInSeries = ref<MetricPoint[]>([])
const networkOutSeries = ref<MetricPoint[]>([])

const currentServer = computed(() => servers.value.find((item) => item.host === selectedHost.value) ?? servers.value[0])
const latestIn = computed(() => networkInSeries.value[networkInSeries.value.length - 1]?.value ?? null)
const latestOut = computed(() => networkOutSeries.value[networkOutSeries.value.length - 1]?.value ?? null)

const hostStatus = computed(() => {
  const ts = Date.parse(currentServer.value?.lastSeen || '')
  if (Number.isNaN(ts)) return '离线'
  return Date.now() - ts <= ONLINE_THRESHOLD_MS ? '在线' : '离线'
})

const hostStatusTagType = computed<'success' | 'danger'>(() => (hostStatus.value === '在线' ? 'success' : 'danger'))
const influxStatusTagType = computed<'success' | 'danger'>(() => (influxStatus.value === 'online' ? 'success' : 'danger'))

const queryWindow = computed(() => calcWindow(timeRange.value[0], timeRange.value[1]))

const quickRangeKey = computed<QuickRangeKey>(() => {
  const [start, end] = timeRange.value
  const s = Date.parse(start)
  const e = Date.parse(end)
  if (Number.isNaN(s) || Number.isNaN(e) || e <= s) {
    return 'custom'
  }
  const diffHours = (e - s) / (1000 * 60 * 60)
  if (Math.abs(diffHours - 1) < 0.02) return '1h'
  if (Math.abs(diffHours - 6) < 0.02) return '6h'
  if (Math.abs(diffHours - 24) < 0.02) return '24h'
  if (Math.abs(diffHours - 72) < 0.02) return '72h'
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
  if (Number.isNaN(s) || Number.isNaN(e)) {
    return '5m'
  }
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

function formatMbps(value: number | null): string {
  if (value == null) return '--'
  return `${((value * 8) / 1_000_000).toFixed(3)} Mbps`
}

const chart = {
  width: 860,
  height: 300,
  paddingLeft: 52,
  paddingRight: 20,
  paddingTop: 18,
  paddingBottom: 40,
}

function parseSeries(series: MetricPoint[]) {
  return series
    .map((item) => ({ ts: Date.parse(item.time), value: item.value }))
    .filter((item) => !Number.isNaN(item.ts))
    .sort((a, b) => a.ts - b.ts)
}

const parsedInSeries = computed(() => parseSeries(networkInSeries.value))
const parsedOutSeries = computed(() => parseSeries(networkOutSeries.value))
const mergedSeries = computed(() => [...parsedInSeries.value, ...parsedOutSeries.value])

const sampleCount = computed(() => Math.max(parsedInSeries.value.length, parsedOutSeries.value.length))

const latestSampleTime = computed(() => {
  const inTs = parsedInSeries.value[parsedInSeries.value.length - 1]?.ts
  const outTs = parsedOutSeries.value[parsedOutSeries.value.length - 1]?.ts
  const maxTs = Math.max(inTs ?? 0, outTs ?? 0)
  if (!maxTs) return '--'
  return formatDateTime(new Date(maxTs).toISOString())
})

const yDomain = computed<[number, number]>(() => {
  if (!mergedSeries.value.length) {
    return [0, 1]
  }
  const values = mergedSeries.value.map((item) => item.value)
  const min = Math.min(...values)
  const max = Math.max(...values)
  if (min === max) {
    return [Math.max(0, min - 1), max + 1]
  }
  const pad = (max - min) * 0.1
  return [Math.max(0, min - pad), max + pad]
})

const xDomain = computed<[number, number]>(() => {
  const points = mergedSeries.value
  if (!points.length) {
    const now = Date.now()
    return [now - 60 * 60 * 1000, now]
  }
  const minTs = Math.min(...points.map((p) => p.ts))
  const maxTs = Math.max(...points.map((p) => p.ts))
  if (minTs === maxTs) {
    return [minTs - 60 * 1000, maxTs + 60 * 1000]
  }
  return [minTs, maxTs]
})

function xFromTs(ts: number): number {
  const [minTs, maxTs] = xDomain.value
  const innerW = chart.width - chart.paddingLeft - chart.paddingRight
  const ratio = (ts - minTs) / (maxTs - minTs)
  return chart.paddingLeft + ratio * innerW
}

function yFromValue(value: number): number {
  const [minV, maxV] = yDomain.value
  const innerH = chart.height - chart.paddingTop - chart.paddingBottom
  const ratio = (value - minV) / (maxV - minV)
  return chart.height - chart.paddingBottom - ratio * innerH
}

function buildPolyline(series: { ts: number; value: number }[]): string {
  if (!series.length) return ''
  return series.map((point) => `${xFromTs(point.ts).toFixed(2)},${yFromValue(point.value).toFixed(2)}`).join(' ')
}

const inPolyline = computed(() => buildPolyline(parsedInSeries.value))
const outPolyline = computed(() => buildPolyline(parsedOutSeries.value))

function formatTickTime(ts: number): string {
  const d = new Date(ts)
  const [start, end] = xDomain.value
  const spanHours = (end - start) / (1000 * 60 * 60)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  if (spanHours > 24) {
    const month = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    return `${month}-${day} ${hh}:${mm}`
  }
  return `${hh}:${mm}`
}

const xTicks = computed(() => {
  const [minTs, maxTs] = xDomain.value
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
  const [minV, maxV] = yDomain.value
  const count = 6
  const ticks: { y: number; label: string }[] = []
  for (let i = 0; i < count; i += 1) {
    const ratio = i / (count - 1)
    const value = minV + (maxV - minV) * ratio
    ticks.push({
      y: yFromValue(value),
      label: `${((value * 8) / 1_000_000).toFixed(2)}`,
    })
  }
  return ticks
})

function applyQuickRange(hours: number) {
  const end = new Date()
  const start = new Date(end.getTime() - hours * 60 * 60 * 1000)
  timeRange.value = [toDateTimeString(start), toDateTimeString(end)]
  void queryAll()
}

async function loadTopology() {
  const [start, end] = timeRange.value
  const nodes = await getApi<TopologyNode[]>('/api/hosts/topology', { start, end })
  servers.value = nodes.filter((item) => ['host', 'server'].includes((item.role || '').toLowerCase()))
  if (selectedHost.value && !servers.value.some((item) => item.host === selectedHost.value)) {
    selectedHost.value = ''
  }
  if (!selectedHost.value && servers.value.length) {
    selectedHost.value = servers.value[0]!.host
  }
}

async function loadHealth() {
  try {
    await getApi<boolean>('/api/health/influx')
    influxStatus.value = 'online'
  } catch {
    influxStatus.value = 'offline'
  }
}

async function loadNetworkSeries() {
  if (!currentServer.value) {
    networkInSeries.value = []
    networkOutSeries.value = []
    return
  }
  const [start, end] = timeRange.value
  const window = calcWindow(start, end)
  const role = currentServer.value.role || 'host'
  const name = currentServer.value.host
  const host = encodeURIComponent(currentServer.value.queryHost || currentServer.value.host)

  const [inSeries, outSeries] = await Promise.all([
    getApi<MetricPoint[]>(`/api/network/history-smart/${host}`, { start, end, window, role, name, direction: 'in' }),
    getApi<MetricPoint[]>(`/api/network/history-smart/${host}`, { start, end, window, role, name, direction: 'out' }),
  ])
  networkInSeries.value = inSeries
  networkOutSeries.value = outSeries
}

async function queryAll() {
  loading.value = true
  try {
    await loadTopology()
    const results = await Promise.allSettled([loadHealth(), loadNetworkSeries()])
    const rejected = results.find((item) => item.status === 'rejected')
    if (rejected?.status === 'rejected') {
      throw rejected.reason
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '查询失败')
  } finally {
    loading.value = false
  }
}

onMounted(queryAll)

useGlobalRefresh(async () => {
  if (loading.value) {
    return
  }
  await queryAll()
}, { minGapMs: 1200 })
</script>

<template>
  <div class="network-page" v-loading="loading">
    <el-card class="toolbar-card panel-card" shadow="hover">
      <div class="toolbar-head">
        <div>
          <div class="toolbar-title">网络与服务监控</div>
        </div>
        <div class="toolbar-head-right">
          <el-tag type="info" effect="plain">窗口粒度 {{ queryWindow }}</el-tag>
          <el-button text type="primary" :icon="Refresh" :loading="loading" @click="queryAll">刷新</el-button>
        </div>
      </div>

      <el-form :inline="true" label-width="86" class="toolbar-form">
        <el-form-item label="服务器">
          <el-select v-model="selectedHost" placeholder="请选择服务器" style="width: 220px" @change="queryAll">
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
            @change="queryAll"
          />
        </el-form-item>
        <el-form-item label="快捷范围">
          <div class="quick-range-group">
            <el-button size="small" :type="quickRangeKey === '1h' ? 'primary' : 'default'" @click="applyQuickRange(1)">1小时</el-button>
            <el-button size="small" :type="quickRangeKey === '6h' ? 'primary' : 'default'" @click="applyQuickRange(6)">6小时</el-button>
            <el-button size="small" :type="quickRangeKey === '24h' ? 'primary' : 'default'" @click="applyQuickRange(24)">24小时</el-button>
            <el-button size="small" :type="quickRangeKey === '72h' ? 'primary' : 'default'" @click="applyQuickRange(72)">3天</el-button>
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="queryAll">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-row :gutter="16" class="summary-row">
      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">InfluxDB 服务状态</div>
          <div class="kpi-main">
            <div class="kpi-value" :class="influxStatus">{{ influxStatus === 'online' ? '在线' : '离线' }}</div>
            <el-tag size="small" :type="influxStatusTagType">{{ influxStatus === 'online' ? '健康' : '异常' }}</el-tag>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">服务器在线状态</div>
          <div class="kpi-main host-status-main">
            <div class="kpi-value" :class="hostStatus === '在线' ? 'online' : 'offline'">{{ hostStatus }}</div>
            <el-tag size="small" :type="hostStatusTagType">{{ currentServer?.host || '--' }}</el-tag>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">当前网络入速率</div>
          <div class="kpi-main">
            <div class="kpi-value network in">{{ formatMbps(latestIn) }}</div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="12" :lg="6" class="summary-col">
        <el-card class="panel-card stat-card" shadow="hover">
          <div class="kpi-label">当前网络出速率</div>
          <div class="kpi-main">
            <div class="kpi-value network out">{{ formatMbps(latestOut) }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="panel-card trend-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <div class="card-title-wrap">
            <span class="card-title">网络带宽趋势（入 / 出）</span>
            <span class="path-text">{{ currentServer?.host || '未选择服务器' }}</span>
          </div>
          <div class="trend-header-actions">
            <el-tag size="small" type="success" effect="plain">样本 {{ sampleCount }}</el-tag>
            <span class="trend-updated">最新：{{ latestSampleTime }}</span>
          </div>
        </div>
      </template>

      <div class="trend-wrap">
        <svg v-if="inPolyline || outPolyline" class="chart" :viewBox="`0 0 ${chart.width} ${chart.height}`" preserveAspectRatio="none">
          <line
            :x1="chart.paddingLeft"
            :y1="chart.height - chart.paddingBottom"
            :x2="chart.width - chart.paddingRight"
            :y2="chart.height - chart.paddingBottom"
            class="axis"
          />
          <line
            :x1="chart.paddingLeft"
            :y1="chart.paddingTop"
            :x2="chart.paddingLeft"
            :y2="chart.height - chart.paddingBottom"
            class="axis"
          />
          <line
            v-for="tick in yTicks"
            :key="`y-grid-${tick.label}`"
            :x1="chart.paddingLeft"
            :y1="tick.y"
            :x2="chart.width - chart.paddingRight"
            :y2="tick.y"
            class="grid-line"
          />
          <text
            v-for="tick in yTicks"
            :key="`y-label-${tick.label}`"
            :x="chart.paddingLeft - 8"
            :y="tick.y + 4"
            class="tick-label tick-label-y"
          >
            {{ tick.label }}
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
          <polyline v-if="inPolyline" :points="inPolyline" class="line-in" />
          <polyline v-if="outPolyline" :points="outPolyline" class="line-out" />
          <text :x="12" :y="chart.height / 2" :transform="`rotate(-90 12 ${chart.height / 2})`" class="axis-title">
            Mbps
          </text>
          <text :x="chart.width - chart.paddingRight - 18" :y="chart.height - 8" class="axis-title">
            时间
          </text>
        </svg>

        <el-empty v-else description="当前范围暂无网络数据，请切换服务器或时间范围" />

        <div class="legend">
          <span><i class="dot in"></i>入流量</span>
          <span><i class="dot out"></i>出流量</span>
          <span class="legend-meta">单位：Mbps</span>
          <span class="legend-meta legend-meta-right">查询范围：{{ timeRange[0] }} ~ {{ timeRange[1] }}</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.network-page {
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
  background: rgba(244, 248, 255, 0.86);
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
  margin-top: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.kpi-value {
  font-size: 28px;
  font-weight: 700;
  color: #1d3158;
  line-height: 1.1;
}

.kpi-value.online,
.kpi-value.in.online {
  color: #16a34a;
}

.kpi-value.offline {
  color: #dc2626;
}

.kpi-value.network {
  font-size: 24px;
}

.kpi-value.network.in {
  color: #2563eb;
}

.kpi-value.network.out {
  color: #059669;
}

.trend-card :deep(.el-card__body) {
  padding: 14px 14px 12px;
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

.trend-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.trend-updated {
  color: #6f7f9e;
  font-size: 12px;
}

.trend-wrap {
  border: 1px solid var(--line-soft);
  border-radius: 14px;
  padding: 12px 12px 10px;
  background: linear-gradient(180deg, #f6faff, #f1f7ff);
}

.chart {
  width: 100%;
  height: 300px;
}

.axis {
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

.line-in {
  fill: none;
  stroke: #2563eb;
  stroke-width: 2;
}

.line-out {
  fill: none;
  stroke: #10b981;
  stroke-width: 2;
}

.legend {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  color: #64748b;
  font-size: 12px;
}

.legend-meta {
  color: #7487a8;
}

.legend-meta-right {
  margin-left: auto;
}

.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
}

.dot.in {
  background: #2563eb;
}

.dot.out {
  background: #10b981;
}

:deep(.el-tag.el-tag--success) {
  border-color: rgba(34, 197, 94, 0.3);
  background: rgba(34, 197, 94, 0.1);
  color: #15803d;
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

@media (max-width: 1100px) {
  .toolbar-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .trend-header-actions {
    flex-wrap: wrap;
    justify-content: flex-end;
  }
}

@media (max-width: 640px) {
  .network-page {
    gap: 14px;
    padding: 4px 2px 2px;
  }

  .toolbar-card :deep(.el-card__body) {
    padding: 16px 14px 12px;
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

  .trend-updated {
    width: 100%;
  }

  .legend-meta-right {
    margin-left: 0;
    width: 100%;
  }
}
</style>
