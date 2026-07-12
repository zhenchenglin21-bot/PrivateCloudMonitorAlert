<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
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
  lastSeen?: string | null
}

interface MetricPoint {
  time: string
  value: number
}

interface TopProcess {
  name?: string
  value?: number
  field?: string
  time?: string
}

interface ProcessMetric {
  name?: string
  value?: number
  field?: string
  time?: string
}

interface ProcessTopLists {
  cpu: ProcessMetric[]
  mem: ProcessMetric[]
}

interface CpuCoreUsage {
  cpu: string
  usageUser?: number
  usageSystem?: number
}

type MetricCardType = 'text' | 'pie' | 'liquid' | 'process'

interface MetricCard {
  key: string
  label: string
  type: MetricCardType
  value?: string
  percent?: number
  color?: string
  raw?: TopProcess | null
}

const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'
const loginPath = '/login'

const topologyNodes = ref<TopologyNode[]>([])
const selectedServerHost = ref('')
const selectedVmHost = ref<string | null>(null)
const selectedContainerHost = ref<string | null>(null)
const selectedContainerFrom = ref<'server' | 'vm' | null>(null)
const loadingTopology = ref(false)
const loadingMetrics = ref(false)

const now = new Date()
const defaultEnd = new Date(now.getTime())
const queryTime = ref<string>(toDateTimeString(defaultEnd))
const autoRefreshEnabled = ref(false)
const autoRefreshSeconds = ref(30)
const AUTO_REFRESH_ENABLED_KEY = 'pc_monitoring_auto_refresh_enabled'
const AUTO_REFRESH_SECONDS_KEY = 'pc_monitoring_auto_refresh_seconds'
const queryLookbackMinutes = 5
let autoRefreshTimer: ReturnType<typeof setInterval> | null = null

const cpuSeries = ref<MetricPoint[]>([])
const cpuSystemSeries = ref<MetricPoint[]>([])
const memorySeries = ref<MetricPoint[]>([])
const storageSeries = ref<MetricPoint[]>([])
const swapSeries = ref<MetricPoint[]>([])
const networkInSeries = ref<MetricPoint[]>([])
const networkOutSeries = ref<MetricPoint[]>([])
const topProcess = ref<TopProcess | null>(null)
const containerPidsSeries = ref<MetricPoint[]>([])
const containerMemUsageSeries = ref<MetricPoint[]>([])
const containerRwSizeSeries = ref<MetricPoint[]>([])
const containerCpuRateSeries = ref<MetricPoint[]>([])
const containerNetInRateSeries = ref<MetricPoint[]>([])
const containerNetOutRateSeries = ref<MetricPoint[]>([])
const cpuDetailVisible = ref(false)
const cpuCoreLoading = ref(false)
const cpuCoreStats = ref<CpuCoreUsage[]>([])
const processDetailVisible = ref(false)
const processDetailLoading = ref(false)
const processTopLists = ref<ProcessTopLists>({
  cpu: [],
  mem: [],
})

const isVmRole = (role?: string) => {
  const value = (role || '').toLowerCase()
  return value === 'vm'
}
const isContainerRole = (role?: string) => {
  const value = (role || '').toLowerCase()
  return value === 'container'
}
const isServerRole = (role?: string) => {
  const value = (role || '').toLowerCase()
  return value === 'server' || value === 'host'
}

const servers = computed(() => topologyNodes.value.filter((node) => isServerRole(node.role)))
const vmPool = computed(() => topologyNodes.value.filter((node) => isVmRole(node.role)))
const containerPool = computed(() => topologyNodes.value.filter((node) => isContainerRole(node.role)))

const currentServer = computed(
  () => servers.value.find((server) => server.host === selectedServerHost.value) ?? servers.value[0],
)

const vmList = computed(() => {
  const server = currentServer.value
  if (!server) {
    return []
  }
  const linked = vmPool.value.filter((vm) => vm.parentHost === server.host)
  return linked.length ? linked : vmPool.value
})

const serverContainers = computed(() => {
  const server = currentServer.value
  if (!server) {
    return []
  }
  const linked = containerPool.value.filter((node) => node.parentHost === server.host)
  return linked.length ? linked : containerPool.value.filter((node) => !node.parentHost)
})

const currentVm = computed(() => vmList.value.find((vm) => vm.host === selectedVmHost.value))

const vmContainers = computed(() => {
  const vm = currentVm.value
  if (!vm) {
    return []
  }
  const linked = containerPool.value.filter((node) => node.parentHost === vm.host)
  return linked.length ? linked : []
})

const allServerContainers = computed(() => {
  const vmChildHosts = new Set(vmList.value.map((vm) => vm.host))
  return containerPool.value.filter((container) => {
    if (container.parentHost === currentServer.value?.host) {
      return true
    }
    return Boolean(container.parentHost && vmChildHosts.has(container.parentHost))
  })
})

const currentContainer = computed(() => {
  if (!selectedContainerHost.value) {
    return undefined
  }
  return containerPool.value.find((node) => node.host === selectedContainerHost.value)
})

const currentLevel = computed<'server' | 'vm' | 'container'>(() => {
  if (currentContainer.value) {
    return 'container'
  }
  if (currentVm.value) {
    return 'vm'
  }
  return 'server'
})

const currentTarget = computed(() => {
  if (currentLevel.value === 'container' && currentContainer.value) {
    return currentContainer.value
  }
  if (currentLevel.value === 'vm' && currentVm.value) {
    return currentVm.value
  }
  return currentServer.value
})

const metricCards = computed<MetricCard[]>(() => {
  if (currentLevel.value === 'container') {
    return [
      { key: 'container-pids', label: '容器进程数', type: 'text', value: formatCount(latestValue(containerPidsSeries.value)) },
      { key: 'container-mem', label: '容器内存使用量', type: 'text', value: formatBytes(latestValue(containerMemUsageSeries.value)) },
      { key: 'container-rw', label: '容器读写层大小', type: 'text', value: formatBytes(latestValue(containerRwSizeSeries.value)) },
      { key: 'container-cpu', label: '容器 CPU 消耗速率', type: 'text', value: formatRate(latestValue(containerCpuRateSeries.value), 'core') },
      { key: 'container-net-in', label: '容器网络入速率', type: 'text', value: formatMbps(latestValue(containerNetInRateSeries.value)) },
      { key: 'container-net-out', label: '容器网络出速率', type: 'text', value: formatMbps(latestValue(containerNetOutRateSeries.value)) },
    ]
  }
  const cpuUser = clampPercent(latestValue(cpuSeries.value))
  const cpuSystem = clampPercent(latestValue(cpuSystemSeries.value))
  const memory = clampPercent(latestValue(memorySeries.value))
  const storage = clampPercent(latestValue(storageSeries.value))
  const swap = clampPercent(latestValue(swapSeries.value))

  return [
    { key: 'cpu-user', label: 'CPU 用户态占比', type: 'pie', percent: cpuUser, color: '#2563eb' },
    { key: 'cpu-system', label: 'CPU 系统态占比', type: 'pie', percent: cpuSystem, color: '#f97316' },
    { key: 'process', label: '进程(资源最高)', type: 'process', value: formatTopProcess(topProcess.value), raw: topProcess.value },
    { key: 'memory', label: '内存使用率', type: 'liquid', percent: memory, color: '#10b981' },
    { key: 'storage', label: '存储使用率', type: 'liquid', percent: storage, color: '#6366f1' },
    { key: 'swap', label: 'Swap 使用率', type: 'liquid', percent: swap, color: '#06b6d4' },
    { key: 'network-in', label: '网络入速率', type: 'text', value: formatMbps(latestValue(networkInSeries.value)) },
    { key: 'network-out', label: '网络出速率', type: 'text', value: formatMbps(latestValue(networkOutSeries.value)) },
    { key: 'last-time', label: '数据时间点', type: 'text', value: formatLastTime() },
  ]
})

const currentTitle = computed(() => {
  if (currentLevel.value === 'container') {
    return `容器指标 · ${currentContainer.value?.host ?? '--'}`
  }
  if (currentLevel.value === 'vm') {
    return `虚拟机指标 · ${currentVm.value?.host ?? '--'}`
  }
  return `服务器指标 · ${currentServer.value?.host ?? '--'}`
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

function parseDateTime(value?: string | null): Date {
  const date = value ? new Date(value) : new Date()
  if (Number.isNaN(date.getTime())) {
    return new Date()
  }
  return date
}

function getQueryStartEnd(useLatest = false): [string, string] {
  const endDate = useLatest ? new Date() : parseDateTime(queryTime.value)
  const startDate = new Date(endDate.getTime() - queryLookbackMinutes * 60 * 1000)
  return [toDateTimeString(startDate), toDateTimeString(endDate)]
}

function stopAutoRefreshTimer() {
  if (autoRefreshTimer != null) {
    clearInterval(autoRefreshTimer)
    autoRefreshTimer = null
  }
}

function startAutoRefreshTimer() {
  stopAutoRefreshTimer()
  if (!autoRefreshEnabled.value) {
    return
  }
  const seconds = Math.max(1, Math.floor(Number(autoRefreshSeconds.value) || 30))
  autoRefreshSeconds.value = seconds
  autoRefreshTimer = setInterval(() => {
    void refreshLatestData()
  }, seconds * 1000)
}

function onAutoRefreshConfigChange() {
  persistAutoRefreshSettings()
  startAutoRefreshTimer()
  if (autoRefreshEnabled.value) {
    void refreshLatestData()
  }
}

function persistAutoRefreshSettings() {
  const seconds = Math.max(1, Math.floor(Number(autoRefreshSeconds.value) || 30))
  autoRefreshSeconds.value = seconds
  localStorage.setItem(AUTO_REFRESH_ENABLED_KEY, autoRefreshEnabled.value ? '1' : '0')
  localStorage.setItem(AUTO_REFRESH_SECONDS_KEY, String(seconds))
}

function restoreAutoRefreshSettings() {
  const enabledRaw = localStorage.getItem(AUTO_REFRESH_ENABLED_KEY)
  const secondsRaw = localStorage.getItem(AUTO_REFRESH_SECONDS_KEY)
  if (enabledRaw != null) {
    autoRefreshEnabled.value = enabledRaw === '1'
  }
  if (secondsRaw != null) {
    const parsed = Number(secondsRaw)
    if (!Number.isNaN(parsed) && Number.isFinite(parsed)) {
      autoRefreshSeconds.value = Math.max(1, Math.min(3600, Math.floor(parsed)))
    }
  }
}

function latestValue(series: MetricPoint[]): number | null {
  if (!series.length) {
    return null
  }
  const last = series[series.length - 1]
  return last ? last.value : null
}

function clampPercent(value: number | null): number {
  if (value == null || Number.isNaN(value)) {
    return 0
  }
  return Math.min(100, Math.max(0, value))
}

function formatPercent(value: number | null): string {
  if (value == null) {
    return '--'
  }
  return `${value.toFixed(2)}%`
}

function formatMbps(value: number | null): string {
  if (value == null) {
    return '--'
  }
  const mbps = (value * 8) / 1_000_000
  return `${mbps.toFixed(3)} Mbps`
}

function formatCount(value: number | null): string {
  if (value == null) {
    return '--'
  }
  return String(Math.round(value))
}

function formatRate(value: number | null, unit: string): string {
  if (value == null) {
    return '--'
  }
  return `${value.toFixed(3)} ${unit}`
}

function formatTopProcess(value: TopProcess | null): string {
  if (!value || !value.name) {
    return '--'
  }
  if (value.value == null) {
    return value.name
  }
  const field = (value.field || '').toLowerCase()
  if (field.includes('cpu')) {
    return `${value.name} (${value.value.toFixed(2)}%)`
  }
  if (field.includes('mem') || field.includes('rss') || field.includes('vms')) {
    return `${value.name} (${formatBytes(value.value)})`
  }
  return `${value.name} (${value.value.toFixed(2)})`
}

function formatProcessMetric(value?: number | null, field?: string | null): string {
  if (value == null) return '--'
  const f = (field || '').toLowerCase()
  if (f.includes('cpu')) {
    return `${value.toFixed(2)}%`
  }
  if (f.includes('mem') || f.includes('memory') || f.includes('rss') || f.includes('vms') || f.includes('swap')) {
    return formatBytes(value)
  }
  if (f.includes('read_bytes') || f.includes('write_bytes') || f.includes('bytes')) {
    return formatBytes(value)
  }
  return value.toFixed(2)
}
function pieStyle(percent: number, color: string) {
  const angle = Math.min(100, Math.max(0, percent)) * 3.6
  return {
    background: `conic-gradient(${color} 0deg ${angle}deg, #e5e7eb ${angle}deg 360deg)`,
    '--pie-color': color,
  }
}

function liquidStyle(percent: number, color: string) {
  return {
    '--fill-percent': `${Math.min(100, Math.max(0, percent))}%`,
    '--liquid-color': color,
  } as Record<string, string>
}

function corePieStyle(core: CpuCoreUsage) {
  const user = clampPercent(core.usageUser ?? 0)
  const system = clampPercent(core.usageSystem ?? 0)
  const other = Math.max(0, 100 - user - system)
  const userDeg = user * 3.6
  const systemDeg = system * 3.6
  const otherDeg = other * 3.6
  return {
    background: `conic-gradient(#2563eb 0deg ${userDeg}deg, #f97316 ${userDeg}deg ${userDeg + systemDeg}deg, #e5e7eb ${userDeg + systemDeg}deg ${userDeg + systemDeg + otherDeg}deg)`,
  }
}

function formatBytes(value: number | null): string {
  if (value == null) {
    return '--'
  }
  if (value < 1024) return `${value.toFixed(0)} B`
  const kb = value / 1024
  if (kb < 1024) return `${kb.toFixed(2)} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(2)} MB`
  return `${(mb / 1024).toFixed(2)} GB`
}

function formatDateTime(value?: string): string {
  if (!value) {
    return '--'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '--'
  }
  return toDateTimeString(date)
}

function formatLastTime(): string {
  const groups = currentLevel.value === 'container'
    ? [
        containerPidsSeries.value,
        containerMemUsageSeries.value,
        containerRwSizeSeries.value,
        containerCpuRateSeries.value,
        containerNetInRateSeries.value,
        containerNetOutRateSeries.value,
      ]
    : [
        cpuSeries.value,
        cpuSystemSeries.value,
        memorySeries.value,
        storageSeries.value,
        swapSeries.value,
        networkInSeries.value,
        networkOutSeries.value,
      ]
  const lastTimes = groups
    .map((series) => getLastTime(series))
    .filter((value): value is string => Boolean(value))
  if (!lastTimes.length) {
    return '--'
  }
  const latest = lastTimes.reduce((current, candidate) => {
    const currentTs = new Date(current).getTime()
    const candidateTs = new Date(candidate).getTime()
    if (Number.isNaN(candidateTs)) {
      return current
    }
    if (Number.isNaN(currentTs) || candidateTs > currentTs) {
      return candidate
    }
    return current
  })
  return formatDateTime(latest)
}

function getLastTime(series: MetricPoint[]): string | undefined {
  if (!series.length) {
    return undefined
  }
  return series[series.length - 1]?.time
}

function calcWindow(start: string, end: string): string {
  const s = new Date(start).getTime()
  const e = new Date(end).getTime()
  if (Number.isNaN(s) || Number.isNaN(e)) {
    return '5m'
  }
  const hours = Math.abs(e - s) / (1000 * 60 * 60)
  if (hours > 96) {
    return '30m'
  }
  if (hours > 24) {
    return '10m'
  }
  return '1m'
}

async function getApi<T>(path: string, params?: Record<string, string | undefined>): Promise<T> {
  const query = new URLSearchParams()
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value && value.trim()) {
      query.set(key, value)
    }
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

async function loadCpuCoreStats() {
  if (!currentTarget.value) {
    cpuCoreStats.value = []
    return
  }
  const [start, end] = getQueryStartEnd()
  const role = currentTarget.value.role || undefined
  const primaryHost = currentTarget.value.queryHost || currentTarget.value.host
  const fallbackHost = currentTarget.value.host
  const host = encodeURIComponent(primaryHost)
  const name = currentTarget.value.host || undefined

  cpuCoreLoading.value = true
  try {
    let cores = await getApi<CpuCoreUsage[]>(`/api/cpu/cores/${host}`, { start, end, role, name })
    if (!cores.length && fallbackHost && fallbackHost !== primaryHost) {
      const altHost = encodeURIComponent(fallbackHost)
      cores = await getApi<CpuCoreUsage[]>(`/api/cpu/cores/${altHost}`, { start, end, role, name })
    }
    cpuCoreStats.value = cores
  } catch (error) {
    cpuCoreStats.value = []
    ElMessage.error(error instanceof Error ? error.message : 'CPU 核心指标查询失败')
  } finally {
    cpuCoreLoading.value = false
  }
}

async function loadProcessLists() {
  if (!currentTarget.value) {
    processTopLists.value = { cpu: [], mem: [] }
    return
  }
  const [start, end] = getQueryStartEnd()
  const role = currentTarget.value.role || undefined
  const primaryHost = currentTarget.value.queryHost || currentTarget.value.host
  const fallbackHost = currentTarget.value.host
  const host = encodeURIComponent(primaryHost)
  const name = currentTarget.value.host || undefined

  processDetailLoading.value = true
  try {
    const fetchMetric = async (metric: string) =>
      getApi<ProcessMetric[]>(`/api/process/toplist/${host}`, { start, end, role, name, metric, limit: '5' })

    let [cpu, mem] = await Promise.all([
      fetchMetric('cpu'),
      fetchMetric('mem'),
    ])
    if ((!cpu.length || !mem.length) && fallbackHost && fallbackHost !== primaryHost) {
      const altHost = encodeURIComponent(fallbackHost)
      const fetchAlt = async (metric: string) =>
        getApi<ProcessMetric[]>(`/api/process/toplist/${altHost}`, { start, end, role, name, metric, limit: '5' })
      if (!cpu.length) cpu = await fetchAlt('cpu')
      if (!mem.length) mem = await fetchAlt('mem')
    }
    processTopLists.value = { cpu, mem }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载进程详情失败')
    processTopLists.value = { cpu: [], mem: [] }
  } finally {
    processDetailLoading.value = false
  }
}

function openCpuDetail() {
  if (currentLevel.value === 'container') {
    return
  }
  cpuDetailVisible.value = true
  void loadCpuCoreStats()
}

function openProcessDetail() {
  processDetailVisible.value = true
  void loadProcessLists()
}

async function loadTopology() {
  loadingTopology.value = true
  try {
    const [start, end] = getQueryStartEnd()
    const nodes = await getApi<TopologyNode[] | null>('/api/hosts/topology', { start, end })
    topologyNodes.value = Array.isArray(nodes) ? nodes : []

    if (!servers.value.length) {
      selectedServerHost.value = ''
      return
    }
    const firstServer = servers.value[0]
    if (firstServer && !servers.value.some((server) => server.host === selectedServerHost.value)) {
      selectedServerHost.value = firstServer.host
    }
  } finally {
    loadingTopology.value = false
  }
}

async function loadMetricsForCurrentTarget() {
  if (!currentTarget.value) {
    cpuSeries.value = []
    cpuSystemSeries.value = []
    memorySeries.value = []
    storageSeries.value = []
    swapSeries.value = []
    networkInSeries.value = []
    networkOutSeries.value = []
    topProcess.value = null
    containerPidsSeries.value = []
    containerMemUsageSeries.value = []
    containerRwSizeSeries.value = []
    containerCpuRateSeries.value = []
    containerNetInRateSeries.value = []
    containerNetOutRateSeries.value = []
    return
  }
  const [start, end] = getQueryStartEnd()
  const role = currentTarget.value.role || undefined
  const window = calcWindow(start, end)
  const primaryHost = currentTarget.value.queryHost || currentTarget.value.host
  const fallbackHost = currentTarget.value.host
  const host = encodeURIComponent(primaryHost)
  const name = currentTarget.value.host || undefined

  loadingMetrics.value = true
  try {
    if (currentLevel.value === 'container') {
      const [pids, memUsage, rwSize, cpuRate, netInRate, netOutRate] = await Promise.all([
        getApi<MetricPoint[]>(`/api/container-metrics/history/${host}`, { start, end, window, role, name, measurement: 'podman_container_pids' }),
        getApi<MetricPoint[]>(`/api/container-metrics/history/${host}`, { start, end, window, role, name, measurement: 'podman_container_mem_usage_bytes' }),
        getApi<MetricPoint[]>(`/api/container-metrics/history/${host}`, { start, end, window, role, name, measurement: 'podman_container_rw_size_bytes' }),
        getApi<MetricPoint[]>(`/api/container-metrics/history/${host}`, { start, end, window, role, name, measurement: 'podman_container_cpu_seconds_total', derivative: 'true' }),
        getApi<MetricPoint[]>(`/api/container-metrics/history/${host}`, { start, end, window, role, name, measurement: 'podman_container_net_input_total', derivative: 'true' }),
        getApi<MetricPoint[]>(`/api/container-metrics/history/${host}`, { start, end, window, role, name, measurement: 'podman_container_net_output_total', derivative: 'true' }),
      ])
      containerPidsSeries.value = pids
      containerMemUsageSeries.value = memUsage
      containerRwSizeSeries.value = rwSize
      containerCpuRateSeries.value = cpuRate
      containerNetInRateSeries.value = netInRate
      containerNetOutRateSeries.value = netOutRate

      cpuSeries.value = []
      cpuSystemSeries.value = []
      memorySeries.value = []
      storageSeries.value = []
      swapSeries.value = []
      networkInSeries.value = []
      networkOutSeries.value = []
      topProcess.value = null
    } else {
      const [cpu, cpuSystem, memory, storage, swap, process] = await Promise.all([
        getApi<MetricPoint[]>(`/api/cpu/history/${host}`, { start, end, window, role, name }),
        getApi<MetricPoint[]>(`/api/cpu/history/${host}`, { start, end, window, role, name, field: 'usage_system' }),
        getApi<MetricPoint[]>(`/api/memory/history/${host}`, { start, end, window, role, name }),
        getApi<MetricPoint[]>(`/api/disk/history/${host}`, { start, end, window, role, name }),
        getApi<MetricPoint[]>(`/api/swap/history/${host}`, { start, end, window, role, name }),
        getApi<TopProcess | null>(`/api/process/top/${host}`, { start, end, role, name, metric: 'cpu' }),
      ])
      let networkIn = await getApi<MetricPoint[]>(`/api/network/history-smart/${host}`, { start, end, window, role, name, direction: 'in' })
      let networkOut = await getApi<MetricPoint[]>(`/api/network/history-smart/${host}`, { start, end, window, role, name, direction: 'out' })
      let top = process
      if ((!networkIn.length || !networkOut.length) && fallbackHost && fallbackHost !== primaryHost) {
        const altHost = encodeURIComponent(fallbackHost)
        if (!networkIn.length) {
          networkIn = await getApi<MetricPoint[]>(`/api/network/history-smart/${altHost}`, { start, end, window, role, name, direction: 'in' })
        }
        if (!networkOut.length) {
          networkOut = await getApi<MetricPoint[]>(`/api/network/history-smart/${altHost}`, { start, end, window, role, name, direction: 'out' })
        }
      }
      if (!top && fallbackHost && fallbackHost !== primaryHost) {
        const altHost = encodeURIComponent(fallbackHost)
        top = await getApi<TopProcess | null>(`/api/process/top/${altHost}`, { start, end, role, name, metric: 'cpu' })
      }
      cpuSeries.value = cpu
      cpuSystemSeries.value = cpuSystem
      memorySeries.value = memory
      storageSeries.value = storage
      swapSeries.value = swap
      networkInSeries.value = networkIn
      networkOutSeries.value = networkOut
      topProcess.value = top

      containerPidsSeries.value = []
      containerMemUsageSeries.value = []
      containerRwSizeSeries.value = []
      containerCpuRateSeries.value = []
      containerNetInRateSeries.value = []
      containerNetOutRateSeries.value = []
    }
  } finally {
    loadingMetrics.value = false
  }
}

function selectServer(host: string) {
  selectedServerHost.value = host
  selectedVmHost.value = null
  selectedContainerHost.value = null
  selectedContainerFrom.value = null
  void loadMetricsForCurrentTarget()
}

function selectVm(host: string) {
  if (selectedVmHost.value === host && currentLevel.value === 'vm') {
    return
  }
  selectedVmHost.value = host
  selectedContainerHost.value = null
  selectedContainerFrom.value = null
  void loadMetricsForCurrentTarget()
}

function selectServerContainer(host: string) {
  selectedVmHost.value = null
  selectedContainerHost.value = host
  selectedContainerFrom.value = 'server'
  void loadMetricsForCurrentTarget()
}

function selectVmContainer(vmHost: string, containerHost: string) {
  selectedVmHost.value = vmHost
  selectedContainerHost.value = containerHost
  selectedContainerFrom.value = 'vm'
  void loadMetricsForCurrentTarget()
}

async function refreshLatestData() {
  if (!autoRefreshEnabled.value || loadingMetrics.value || loadingTopology.value) {
    return
  }
  queryTime.value = toDateTimeString(new Date())
  try {
    if (!topologyNodes.value.length) {
      await loadTopology()
    }
    await loadMetricsForCurrentTarget()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '自动刷新失败')
  }
}

async function onQuery() {
  try {
    await loadTopology()
    selectedVmHost.value = null
    selectedContainerHost.value = null
    selectedContainerFrom.value = null
    await loadMetricsForCurrentTarget()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '查询失败')
  }
}

async function onReset() {
  queryTime.value = toDateTimeString(new Date())
  autoRefreshEnabled.value = false
  autoRefreshSeconds.value = 30
  onAutoRefreshConfigChange()
  selectedVmHost.value = null
  selectedContainerHost.value = null
  selectedContainerFrom.value = null
  await onQuery()
}

onMounted(async () => {
  restoreAutoRefreshSettings()
  await onQuery()
  startAutoRefreshTimer()
})

useGlobalRefresh(async () => {
  if (loadingTopology.value || loadingMetrics.value) {
    return
  }
  await onQuery()
}, { minGapMs: 1200 })

onUnmounted(() => {
  stopAutoRefreshTimer()
})
</script>

<template>
  <div class="page">
    <el-card class="toolbar-card panel-card" shadow="hover">
      <div class="toolbar-head">
        <div class="toolbar-title">基础监控查询</div>
        <el-tag size="small" type="info" effect="plain">
          当前层级：{{ currentLevel === 'server' ? '服务器' : currentLevel === 'vm' ? '虚拟机' : '容器' }}
        </el-tag>
      </div>
      <el-form :inline="true" label-width="80" class="toolbar-form">
        <el-form-item label="服务器">
          <el-select
            :model-value="selectedServerHost"
            placeholder="请选择服务器"
            style="width: 220px"
            size="default"
            :loading="loadingTopology"
            @change="selectServer"
          >
            <el-option
              v-for="server in servers"
              :key="server.host"
              :label="server.host"
              :value="server.host"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="查询时间">
          <el-date-picker
            v-model="queryTime"
            type="datetime"
            placeholder="选择时间点"
            value-format="YYYY-MM-DD HH:mm:ss"
          />
        </el-form-item>
        <el-form-item label="自动刷新">
          <el-switch v-model="autoRefreshEnabled" @change="onAutoRefreshConfigChange" />
        </el-form-item>
        <el-form-item label="秒数">
          <el-input-number
            v-model="autoRefreshSeconds"
            :min="1"
            :max="3600"
            :step="1"
            :disabled="!autoRefreshEnabled"
            controls-position="right"
            @change="onAutoRefreshConfigChange"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loadingTopology || loadingMetrics" @click="onQuery">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-row :gutter="16" class="content-row">
      <el-col :xs="24" :sm="24" :md="8" :lg="7" :xl="7" class="content-col">
        <el-card class="inventory-card panel-card" shadow="hover" v-loading="loadingTopology">
          <template #header>
            <div class="card-header">
              <div class="card-title-wrap">
                <span class="card-title">资源拓扑</span>
                <span class="path-text">{{ currentServer?.host ?? '--' }}</span>
              </div>
              <el-tag size="small" type="info" effect="plain">VM {{ vmList.length }} · 容器 {{ serverContainers.length }}</el-tag>
            </div>
          </template>

          <div class="section-block">
            <div class="section-head">
              <div class="section-title">虚拟机</div>
              <span class="section-count">{{ vmList.length }}</span>
            </div>
            <el-empty v-if="!vmList.length" description="当前范围无虚拟机数据" :image-size="56" />
            <div v-else class="entity-list">
              <div
                v-for="vm in vmList"
                :key="vm.host"
                :class="['entity-item', { active: currentLevel === 'vm' && currentVm?.host === vm.host }]"
                @click="selectVm(vm.host)"
              >
                <div class="entity-name">{{ vm.host }}</div>
                <span class="entity-meta">{{ vm.parentHost || '未标注父级' }}</span>
              </div>
            </div>
          </div>

          <div class="section-block">
            <div class="section-head">
              <div class="section-title">宿主机容器</div>
              <span class="section-count">{{ serverContainers.length }}</span>
            </div>
            <el-empty v-if="!serverContainers.length" description="当前范围无宿主机容器" :image-size="56" />
            <div v-else class="entity-list">
              <div
                v-for="container in serverContainers"
                :key="container.host"
                :class="[
                  'entity-item',
                  { active: currentLevel === 'container' && currentContainer?.host === container.host && selectedContainerFrom === 'server' },
                ]"
                @click="selectServerContainer(container.host)"
              >
                <div class="entity-name">{{ container.host }}</div>
                <span class="entity-meta">{{ container.parentHost || '宿主机' }}</span>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="24" :md="16" :lg="17" :xl="17" class="content-col">
        <el-card class="metrics-panel panel-card" shadow="hover" v-loading="loadingMetrics">
          <template #header>
            <div class="card-header">
              <div class="card-title-wrap">
                <span class="card-title">{{ currentTitle }}</span>
                <span class="path-text path-bold">
                  {{ currentServer?.host ?? '--' }}
                  <template v-if="currentVm"> / {{ currentVm.host }}</template>
                  <template v-if="currentContainer"> / {{ currentContainer.host }}</template>
                </span>
              </div>
              <el-tag size="small" effect="plain" type="success">指标卡 {{ metricCards.length }}</el-tag>
            </div>
          </template>

          <el-row :gutter="12" class="metrics-grid">
            <el-col v-for="item in metricCards" :key="item.key" :xs="24" :sm="12" :md="8" class="metric-col">
              <div
                class="metric-card"
                :class="[
                  `metric-card-${item.type}`,
                  { clickable: item.type === 'pie' || item.type === 'process' },
                ]"
                @click="item.type === 'pie' ? openCpuDetail() : item.type === 'process' ? openProcessDetail() : undefined"
              >
                <div class="metric-card-head">
                  <div class="metric-label">{{ item.label }}</div>
                  <span class="metric-chip">{{ item.type.toUpperCase() }}</span>
                </div>
                <div v-if="item.type === 'pie'" class="metric-pie" :style="pieStyle(item.percent ?? 0, item.color || '#3b82f6')">
                  <div class="pie-center">{{ formatPercent(item.percent ?? 0) }}</div>
                </div>
                <div v-else-if="item.type === 'liquid'" class="liquid-wrap">
                  <div class="liquid-ball" :style="liquidStyle(item.percent ?? 0, item.color || '#3b82f6')">
                    <div class="liquid-fill"></div>
                    <div class="liquid-fill secondary"></div>
                    <div class="liquid-text">{{ formatPercent(item.percent ?? 0) }}</div>
                  </div>
                </div>
                <div v-else class="metric-value">{{ item.value }}</div>
                <div v-if="item.type === 'pie'" class="metric-hint">点击查看 CPU 核心详情</div>
                <div v-else-if="item.type === 'process'" class="metric-hint">点击查看进程详情</div>
              </div>
            </el-col>
          </el-row>

          <el-dialog v-model="cpuDetailVisible" title="CPU 核心占比详情" width="760px">
            <div class="cpu-detail">
              <el-empty v-if="!cpuCoreLoading && !cpuCoreStats.length" description="暂无 CPU 核心数据" :image-size="72" />
              <div v-else class="core-grid" v-loading="cpuCoreLoading">
                <div v-for="core in cpuCoreStats" :key="core.cpu" class="core-card">
                  <div class="core-title">{{ core.cpu }}</div>
                  <div class="core-pie" :style="corePieStyle(core)"></div>
                  <div class="core-legend">
                    <span><i class="dot user"></i>用户 {{ formatPercent(core.usageUser ?? 0) }}</span>
                    <span><i class="dot system"></i>系统 {{ formatPercent(core.usageSystem ?? 0) }}</span>
                    <span><i class="dot other"></i>其它 {{ formatPercent(Math.max(0, 100 - clampPercent(core.usageUser ?? 0) - clampPercent(core.usageSystem ?? 0))) }}</span>
                  </div>
                </div>
              </div>
            </div>
          </el-dialog>

          <el-dialog v-model="processDetailVisible" title="进程详情" width="520px">
            <div class="process-detail">
              <div class="process-summary">
                <div class="process-item">
                  <div class="process-label">CPU 最高进程</div>
                  <div class="process-value">{{ topProcess?.name || '--' }}</div>
                </div>
                <div class="process-item">
                  <div class="process-label">CPU 指标值</div>
                  <div class="process-value">{{ formatProcessMetric(topProcess?.value ?? null, topProcess?.field) }}</div>
                </div>
                <div class="process-item">
                  <div class="process-label">采集时间</div>
                  <div class="process-value">{{ formatDateTime(topProcess?.time) }}</div>
                </div>
              </div>

              <el-tabs type="border-card" class="process-tabs" v-loading="processDetailLoading">
                <el-tab-pane label="CPU Top">
                  <el-table :data="processTopLists.cpu" border size="small">
                    <el-table-column prop="name" label="进程" />
                    <el-table-column prop="field" label="字段" width="140" />
                    <el-table-column label="指标值" width="140">
                      <template #default="scope">
                        {{ formatProcessMetric(scope.row.value, scope.row.field) }}
                      </template>
                    </el-table-column>
                  </el-table>
                  <el-empty v-if="!processTopLists.cpu.length" description="暂无 CPU 进程数据" :image-size="56" />
                </el-tab-pane>
                <el-tab-pane label="内存 Top">
                  <el-table :data="processTopLists.mem" border size="small">
                    <el-table-column prop="name" label="进程" />
                    <el-table-column prop="field" label="字段" width="140" />
                    <el-table-column label="指标值" width="140">
                      <template #default="scope">
                        {{ formatProcessMetric(scope.row.value, scope.row.field) }}
                      </template>
                    </el-table-column>
                  </el-table>
                  <el-empty v-if="!processTopLists.mem.length" description="暂无内存进程数据" :image-size="56" />
                </el-tab-pane>
              </el-tabs>
            </div>
          </el-dialog>

          <div v-if="currentLevel === 'server'" class="sub-section">
            <div class="sub-title">当前服务器虚拟机</div>
            <el-empty v-if="!vmList.length" description="无虚拟机数据" :image-size="56" />
            <div v-else class="entity-list">
              <div
                v-for="vm in vmList"
                :key="vm.host"
                class="entity-item"
                @click="selectVm(vm.host)"
              >
                <div class="entity-name">{{ vm.host }}</div>
                <span class="entity-meta">父级 {{ vm.parentHost || '--' }}</span>
              </div>
            </div>

            <div class="sub-title">当前服务器全部容器</div>
            <el-empty v-if="!allServerContainers.length" description="无容器数据" :image-size="56" />
            <div v-else class="entity-list">
              <div
                v-for="container in allServerContainers"
                :key="container.host"
                class="entity-item"
                @click="
                  vmList.some((vm) => vm.host === container.parentHost)
                    ? selectVmContainer(container.parentHost!, container.host)
                    : selectServerContainer(container.host)
                "
              >
                <div class="entity-name">{{ container.host }}</div>
                <span class="entity-meta">{{ container.parentHost || '宿主机' }}</span>
              </div>
            </div>
          </div>

          <div v-if="currentLevel === 'vm'" class="sub-section">
            <div class="sub-title">该虚拟机搭载容器</div>
            <el-empty v-if="!vmContainers.length" description="该虚拟机无容器数据" :image-size="56" />
            <div v-else class="entity-list">
              <div
                v-for="container in vmContainers"
                :key="container.host"
                class="entity-item"
                @click="selectVmContainer(currentVm!.host, container.host)"
              >
                <div class="entity-name">{{ container.host }}</div>
                <span class="entity-meta">父级 {{ container.parentHost || '--' }}</span>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
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

.toolbar-title {
  font-size: 18px;
  font-weight: 700;
  color: #1b2b4d;
  letter-spacing: 0.2px;
}

.toolbar-form {
  margin-top: 14px;
}

.toolbar-form :deep(.el-form-item) {
  margin-bottom: 10px;
}

.toolbar-form :deep(.el-input__wrapper),
.toolbar-form :deep(.el-select__wrapper),
.toolbar-form :deep(.el-textarea__inner) {
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 0 0 1px rgba(110, 139, 192, 0.2) inset;
}

.toolbar-form :deep(.el-input-number) {
  width: 142px;
}

.content-row {
  margin-top: 2px;
  align-items: stretch;
}

.content-col {
  display: flex;
}

.content-col .panel-card {
  width: 100%;
  height: 100%;
}

.inventory-card {
  min-height: 640px;
}

.inventory-card :deep(.el-card__body),
.metrics-panel :deep(.el-card__body) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
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

.path-text.path-bold {
  color: #4c638c;
  font-weight: 500;
}

.section-block + .section-block {
  margin-top: 16px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.section-title {
  font-size: 13px;
  font-weight: 700;
  color: #2a3f68;
}

.section-count {
  min-width: 26px;
  height: 20px;
  border-radius: 999px;
  border: 1px solid rgba(56, 189, 248, 0.26);
  background: rgba(56, 189, 248, 0.12);
  color: #0f6d98;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 8px;
}

.entity-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.entity-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: 1px solid rgba(110, 139, 192, 0.2);
  border-radius: 12px;
  padding: 10px 12px;
  cursor: pointer;
  transition: all 0.2s ease;
  background: rgba(255, 255, 255, 0.72);
}

.entity-item:hover {
  border-color: rgba(59, 130, 246, 0.45);
  background: linear-gradient(145deg, rgba(239, 246, 255, 0.96), rgba(232, 244, 255, 0.92));
  transform: translateY(-1px);
}

.entity-item.active {
  border-color: rgba(37, 99, 235, 0.56);
  background: linear-gradient(145deg, rgba(226, 240, 255, 0.98), rgba(217, 236, 255, 0.94));
  box-shadow: inset 0 0 0 1px rgba(56, 189, 248, 0.2);
}

.entity-name {
  color: #1f2f4f;
  font-size: 13px;
  font-weight: 600;
}

.entity-meta {
  color: #6f809f;
  font-size: 12px;
}

.metrics-grid {
  row-gap: 12px;
}

.metric-col {
  display: flex;
}

.metric-col .metric-card {
  width: 100%;
}

.metric-card {
  position: relative;
  border: 1px solid rgba(110, 139, 192, 0.2);
  border-radius: 14px;
  padding: 12px;
  min-height: 170px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  background: rgba(255, 255, 255, 0.74);
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
}

.metric-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  border-radius: 3px 0 0 3px;
  opacity: 0.95;
}

.metric-card-text::before {
  background: linear-gradient(180deg, #38bdf8, #2563eb);
}

.metric-card-pie::before {
  background: linear-gradient(180deg, #3b82f6, #1d4ed8);
}

.metric-card-liquid::before {
  background: linear-gradient(180deg, #10b981, #0ea5e9);
}

.metric-card-process::before {
  background: linear-gradient(180deg, #f97316, #ea580c);
}

.metric-card.clickable {
  cursor: pointer;
}

.metric-card.clickable:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 24px rgba(24, 47, 84, 0.14);
  border-color: rgba(59, 130, 246, 0.4);
}

.metric-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.metric-label {
  font-size: 12px;
  color: #61789f;
}

.metric-chip {
  border-radius: 999px;
  border: 1px solid rgba(110, 139, 192, 0.24);
  background: rgba(239, 246, 255, 0.8);
  color: #4b638e;
  padding: 1px 7px;
  font-size: 10px;
  line-height: 1.35;
  letter-spacing: 0.4px;
}

.metric-value {
  margin-top: 10px;
  font-size: 24px;
  font-weight: 700;
  color: #1e2f50;
  line-height: 1.2;
  word-break: break-all;
}

.metric-hint {
  margin-top: 8px;
  font-size: 11px;
  color: #7d8fb0;
}

.metric-pie {
  margin-top: 10px;
  width: 92px;
  height: 92px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: conic-gradient(#3b82f6 0deg 0deg, #e5e7eb 0deg 360deg);
}

.pie-center {
  width: 62px;
  height: 62px;
  border-radius: 50%;
  background: radial-gradient(circle at 30% 30%, rgba(255, 255, 255, 0.95), color-mix(in srgb, var(--pie-color, #3b82f6) 18%, #ffffff 82%));
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  color: color-mix(in srgb, var(--pie-color, #3b82f6) 45%, #111827 55%);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--pie-color, #3b82f6) 30%, #e5e7eb 70%);
}

.liquid-wrap {
  margin-top: 8px;
}

.liquid-ball {
  position: relative;
  width: 90px;
  height: 90px;
  border-radius: 50%;
  background: #e5e7eb;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.38);
}

.liquid-fill {
  position: absolute;
  left: -20%;
  width: 140%;
  height: 140%;
  background: var(--liquid-color, #22c55e);
  top: calc(100% - var(--fill-percent, 0%));
  border-radius: 40%;
  animation: wave 6s linear infinite;
  opacity: 0.85;
}

.liquid-fill.secondary {
  animation: wave 4.5s linear infinite reverse;
  opacity: 0.6;
}

.liquid-text {
  position: relative;
  z-index: 1;
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
  text-shadow: 0 1px 0 rgba(255, 255, 255, 0.6);
}

.sub-section {
  margin-top: 16px;
  border-top: 1px dashed rgba(110, 139, 192, 0.28);
  padding-top: 14px;
}

.sub-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 700;
  color: #2a3f68;
}

.cpu-detail,
.process-detail {
  min-height: 120px;
}

.core-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.core-card {
  border: 1px solid rgba(110, 139, 192, 0.2);
  border-radius: 12px;
  padding: 10px;
  background: rgba(248, 251, 255, 0.9);
}

.core-title {
  font-size: 12px;
  font-weight: 700;
  color: #334155;
}

.core-pie {
  margin: 8px auto;
  width: 86px;
  height: 86px;
  border-radius: 50%;
  background: conic-gradient(#2563eb 0deg, #f97316 0deg, #e5e7eb 0deg 360deg);
}

.core-legend {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 11px;
  color: #64748b;
}

.core-legend .dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
}

.core-legend .dot.user {
  background: #2563eb;
}

.core-legend .dot.system {
  background: #f97316;
}

.core-legend .dot.other {
  background: #e5e7eb;
  border: 1px solid #cbd5e1;
}

.process-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.process-tabs {
  margin-top: 6px;
}

.process-item {
  border: 1px solid rgba(110, 139, 192, 0.2);
  border-radius: 12px;
  padding: 10px;
  background: rgba(248, 251, 255, 0.9);
}

.process-label {
  font-size: 12px;
  color: #64748b;
}

.process-value {
  margin-top: 6px;
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
  word-break: break-all;
}

:deep(.el-dialog) {
  border-radius: 16px;
}

:deep(.el-dialog__header) {
  border-bottom: 1px solid rgba(110, 139, 192, 0.16);
  margin-right: 0;
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

@keyframes wave {
  0% {
    transform: translateX(-10%) translateY(0);
  }
  50% {
    transform: translateX(10%) translateY(2%);
  }
  100% {
    transform: translateX(-10%) translateY(0);
  }
}

@media (max-width: 992px) {
  .toolbar-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .inventory-card {
    min-height: auto;
  }

  .metric-card {
    min-height: 162px;
  }
}

@media (max-width: 640px) {
  .page {
    gap: 14px;
    padding: 4px 2px 2px;
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

  .toolbar-card :deep(.el-card__body) {
    padding: 16px 14px 12px;
  }

  .toolbar-form :deep(.el-form-item) {
    width: 100%;
    margin-right: 0;
  }
}
</style>
