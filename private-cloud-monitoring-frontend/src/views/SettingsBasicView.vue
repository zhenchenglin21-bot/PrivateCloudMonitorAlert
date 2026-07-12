<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { GLOBAL_REFRESH_CONFIG_EVENT } from '../composables/useGlobalRefresh'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

interface NotificationSettingsView {
  emailEnabled: boolean
  recipientEmail: string
  smtpHost: string
  smtpPort: number
  smtpPasswordSet: boolean
  senderEmail: string
  senderName: string
  intervalMinutes: number
  lastSentAt?: string | null
  lastAttemptAt?: string | null
  nextSendAt?: string | null
  lastSendStatus?: string | null
  lastSendError?: string | null
}

type SmtpPreset = 'custom' | 'outlook' | 'qq' | '163' | 'gmail'

const SMTP_PRESETS: Record<Exclude<SmtpPreset, 'custom'>, { host: string; port: number }> = {
  outlook: { host: 'smtp.office365.com', port: 587 },
  qq: { host: 'smtp.qq.com', port: 465 },
  '163': { host: 'smtp.163.com', port: 465 },
  gmail: { host: 'smtp.gmail.com', port: 587 },
}

const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'
const settingsKey = 'pc-monitor-settings-v1'
const loginPath = '/login'

const loading = ref(false)
const savingNotify = ref(false)
const sendingNow = ref(false)
const smtpPreset = ref<SmtpPreset>('custom')

const baseForm = reactive({
  refreshEnabled: true,
  refreshIntervalSec: 30,
})

const notifyForm = reactive({
  emailEnabled: false,
  recipientEmail: '',
  smtpHost: '',
  smtpPort: 587,
  smtpPasswordSet: false,
  senderEmail: '',
  senderName: '',
  intervalMinutes: 10,
  lastSentAt: '--',
  lastAttemptAt: '--',
  nextSendAt: '--',
  lastSendStatus: '--',
  lastSendError: '--',
})

const smtpPasswordInput = ref('')

const securityModeText = computed(() => (Number(notifyForm.smtpPort) === 465 ? 'SSL' : 'STARTTLS'))

const sendStatusType = computed(() => {
  const text = notifyForm.lastSendStatus
  if (text.includes('成功')) return 'success'
  if (text.includes('失败')) return 'danger'
  if (text.includes('无告警')) return 'warning'
  return 'info'
})
const notifyEnabledText = computed(() => (notifyForm.emailEnabled ? '已启用' : '未启用'))
const notifyEnabledType = computed(() => (notifyForm.emailEnabled ? 'success' : 'info'))

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

function loadLocalBaseSettings() {
  const raw = localStorage.getItem(settingsKey)
  if (!raw) return
  try {
    const data = JSON.parse(raw) as {
      refreshEnabled?: boolean
      refreshIntervalSec?: number
    }
    baseForm.refreshEnabled = data.refreshEnabled ?? baseForm.refreshEnabled
    baseForm.refreshIntervalSec = data.refreshIntervalSec || baseForm.refreshIntervalSec
  } catch {
    // ignore invalid local cache
  }
}

function saveBaseSettings() {
  localStorage.setItem(settingsKey, JSON.stringify({ ...baseForm }))
  window.dispatchEvent(new CustomEvent(GLOBAL_REFRESH_CONFIG_EVENT))
  ElMessage.success('基础配置已保存')
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

function statusText(status?: string | null): string {
  const normalized = (status || '').toUpperCase()
  if (!normalized) return '--'
  if (normalized === 'SUCCESS') return '发送成功'
  if (normalized === 'FAILED') return '发送失败'
  if (normalized === 'EMPTY') return '窗口无告警'
  if (normalized === 'INIT') return '未发送'
  return status || '--'
}

function autoSmtpSecurityForPort(port: number) {
  const useSsl = Number(port) === 465
  return {
    smtpAuth: true,
    smtpStarttlsEnable: !useSsl,
    smtpSslEnable: useSsl,
  }
}

function detectPreset(host: string, port: number): SmtpPreset {
  const normalizedHost = (host || '').trim().toLowerCase()
  const matched = (Object.entries(SMTP_PRESETS) as Array<[Exclude<SmtpPreset, 'custom'>, { host: string; port: number }]>)
    .find(([, preset]) => preset.host === normalizedHost && preset.port === Number(port))
  return matched ? matched[0] : 'custom'
}

function applyPreset(value: SmtpPreset) {
  if (value === 'custom') return
  const preset = SMTP_PRESETS[value]
  notifyForm.smtpHost = preset.host
  notifyForm.smtpPort = preset.port
}

function applyNotificationSettings(data: NotificationSettingsView) {
  notifyForm.emailEnabled = Boolean(data.emailEnabled)
  notifyForm.recipientEmail = data.recipientEmail || ''
  notifyForm.smtpHost = data.smtpHost || ''
  notifyForm.smtpPort = Number(data.smtpPort || 587)
  notifyForm.smtpPasswordSet = Boolean(data.smtpPasswordSet)
  notifyForm.senderEmail = data.senderEmail || ''
  notifyForm.senderName = data.senderName || ''
  notifyForm.intervalMinutes = Math.max(1, Math.min(1440, Number(data.intervalMinutes || 10)))
  notifyForm.lastSentAt = formatDateTime(data.lastSentAt)
  notifyForm.lastAttemptAt = formatDateTime(data.lastAttemptAt)
  notifyForm.nextSendAt = formatDateTime(data.nextSendAt)
  notifyForm.lastSendStatus = statusText(data.lastSendStatus)
  notifyForm.lastSendError = data.lastSendError?.trim() || '暂无'
  smtpPasswordInput.value = ''
  smtpPreset.value = detectPreset(notifyForm.smtpHost, notifyForm.smtpPort)
}

async function loadNotificationSettings() {
  const data = await getApi<NotificationSettingsView>('/api/settings/notification')
  applyNotificationSettings(data)
}

async function saveNotificationSettings() {
  savingNotify.value = true
  try {
    const senderEmail = notifyForm.senderEmail.trim()
    const security = autoSmtpSecurityForPort(notifyForm.smtpPort)
    const payload: Record<string, unknown> = {
      emailEnabled: notifyForm.emailEnabled,
      recipientEmail: notifyForm.recipientEmail.trim(),
      smtpHost: notifyForm.smtpHost.trim(),
      smtpPort: notifyForm.smtpPort,
      smtpAuth: security.smtpAuth,
      smtpStarttlsEnable: security.smtpStarttlsEnable,
      smtpSslEnable: security.smtpSslEnable,
      smtpUsername: senderEmail,
      senderEmail,
      senderName: notifyForm.senderName.trim() || '私有云监控',
      intervalMinutes: notifyForm.intervalMinutes,
    }
    if (smtpPasswordInput.value.trim()) payload.smtpPassword = smtpPasswordInput.value.trim()

    const data = await postApi<NotificationSettingsView>('/api/settings/notification', payload)
    applyNotificationSettings(data)
    ElMessage.success('通知设置已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存通知设置失败')
  } finally {
    savingNotify.value = false
  }
}

async function sendDigestNow() {
  sendingNow.value = true
  try {
    const sent = await postApi<boolean>('/api/settings/notification/send-now')
    if (sent) {
      ElMessage.success('告警汇总邮件已发送')
    } else {
      ElMessage.warning('发送失败或当前窗口无可发送记录，请查看发送状态')
    }
    await loadNotificationSettings()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '立即发送失败')
  } finally {
    sendingNow.value = false
  }
}

onMounted(async () => {
  loading.value = true
  try {
    loadLocalBaseSettings()
    await loadNotificationSettings()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载通知设置失败')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="page" v-loading="loading">
    <el-card class="panel-card form-card" shadow="hover">
      <template #header>
        <div class="card-header-row">
          <div class="card-title-wrap">
            <div class="card-title">基础配置</div>
            <span class="path-text">本地生效，刷新后自动读取</span>
          </div>
          <el-tag size="small" type="info" effect="plain">系统默认项</el-tag>
        </div>
      </template>

      <el-form class="config-form base-form" label-width="120px">
        <el-form-item label="全局刷新">
          <el-switch
            v-model="baseForm.refreshEnabled"
            inline-prompt
            :active-value="true"
            :inactive-value="false"
          />
        </el-form-item>
        <el-form-item label="刷新间隔">
          <el-input-number v-model="baseForm.refreshIntervalSec" :min="5" :max="300" :disabled="!baseForm.refreshEnabled" />
          <span class="unit">秒</span>
        </el-form-item>
      </el-form>
      <div class="actions">
        <el-button type="primary" @click="saveBaseSettings">保存基础配置</el-button>
      </div>
    </el-card>

    <el-card class="panel-card form-card" shadow="hover">
      <template #header>
        <div class="card-header-row">
          <div class="card-title-wrap">
            <div class="card-title">通知设置</div>
            <span class="path-text">邮件链路、发件身份、发送周期</span>
          </div>
          <el-tag size="small" :type="notifyEnabledType" effect="plain">{{ notifyEnabledText }}</el-tag>
        </div>
      </template>

      <div class="content-grid">
        <el-form class="config-form notify-form" label-width="120px">
          <el-form-item label="启用通知">
            <el-switch v-model="notifyForm.emailEnabled" />
          </el-form-item>
          <el-form-item label="收件邮箱">
            <el-input v-model="notifyForm.recipientEmail" placeholder="例如：ops@example.com" />
          </el-form-item>
          <el-form-item label="发件邮箱">
            <el-input v-model="notifyForm.senderEmail" placeholder="将作为 SMTP 登录账号" />
          </el-form-item>
          <el-form-item label="服务商">
            <el-select v-model="smtpPreset" @change="applyPreset" placeholder="选择邮箱服务商">
              <el-option label="Outlook/Office365" value="outlook" />
              <el-option label="QQ 邮箱" value="qq" />
              <el-option label="163 邮箱" value="163" />
              <el-option label="Gmail" value="gmail" />
              <el-option label="自定义" value="custom" />
            </el-select>
          </el-form-item>
          <el-form-item label="SMTP 主机">
            <el-input v-model="notifyForm.smtpHost" placeholder="例如：smtp.office365.com" />
          </el-form-item>
          <el-form-item label="SMTP 端口">
            <el-input-number v-model="notifyForm.smtpPort" :min="1" :max="65535" />
            <span class="tip">{{ securityModeText }}</span>
          </el-form-item>
          <el-form-item label="SMTP 授权码">
            <el-input
              v-model="smtpPasswordInput"
              type="password"
              show-password
              placeholder="留空不修改，输入即更新"
            />
            <span class="tip">密码状态：{{ notifyForm.smtpPasswordSet ? '已设置' : '未设置' }}</span>
          </el-form-item>
          <el-form-item label="发送周期">
            <el-input-number v-model="notifyForm.intervalMinutes" :min="1" :max="1440" />
            <span class="unit">分钟</span>
          </el-form-item>
        </el-form>

        <aside class="status-card">
          <div class="status-title">发送状态</div>
          <div class="row">
            <span class="label">最近状态</span>
            <el-tag :type="sendStatusType" effect="light">{{ notifyForm.lastSendStatus }}</el-tag>
          </div>
          <div class="row">
            <span class="label">最近错误</span>
            <span class="value warn">{{ notifyForm.lastSendError }}</span>
          </div>
          <div class="row">
            <span class="label">上次成功</span>
            <span class="value">{{ notifyForm.lastSentAt }}</span>
          </div>
          <div class="row">
            <span class="label">下次发送</span>
            <span class="value">{{ notifyForm.nextSendAt }}</span>
          </div>
        </aside>
      </div>

      <div class="actions">
        <el-button :loading="sendingNow" @click="sendDigestNow">立即发送一次</el-button>
        <el-button type="primary" :loading="savingNotify" @click="saveNotificationSettings">保存通知设置</el-button>
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

.base-form,
.notify-form {
  max-width: 760px;
}

.config-form :deep(.el-input__wrapper),
.config-form :deep(.el-select__wrapper),
.config-form :deep(.el-input-number .el-input__wrapper) {
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 0 0 1px rgba(110, 139, 192, 0.2) inset;
}

.notify-form :deep(.el-form-item) {
  margin-bottom: 16px;
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

.form-card :deep(.el-card__body) {
  padding: 14px 14px 12px;
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(520px, 1fr) 320px;
  gap: 18px;
  align-items: start;
}

.status-card {
  border: 1px solid #dce5f8;
  border-radius: 14px;
  background: linear-gradient(165deg, #f5f9ff 0%, #eef4ff 100%);
  padding: 14px 14px 6px;
}

.status-title {
  font-size: 15px;
  font-weight: 700;
  color: #1e3a8a;
  margin-bottom: 10px;
}

.row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  border-top: 1px dashed #d7dff0;
  padding: 10px 0;
}

.row:first-of-type {
  border-top: none;
  padding-top: 0;
}

.label {
  font-size: 12px;
  color: #64748b;
}

.value {
  font-size: 13px;
  color: #1f2937;
  line-height: 1.5;
  word-break: break-word;
}

.value.warn {
  color: #b45309;
}

.tip {
  margin-left: 10px;
  color: #64748b;
  font-size: 12px;
}

.unit {
  margin-left: 8px;
  color: #64748b;
  font-size: 12px;
}

.actions {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
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
  .content-grid {
    grid-template-columns: 1fr;
  }

  .status-card {
    order: -1;
  }
}

@media (max-width: 900px) {
  .card-header-row {
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (max-width: 640px) {
  .page {
    gap: 14px;
    padding: 4px 2px 2px;
  }

  .card-title-wrap {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }
}
</style>
