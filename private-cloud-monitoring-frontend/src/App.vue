<script setup lang="ts">
import {
  Menu as IconMenu,
  DataAnalysis,
  Bell,
  Collection,
  Setting,
  Connection,
  DataLine,
  User,
} from '@element-plus/icons-vue'
import { useRouter, useRoute } from 'vue-router'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import loginAlertCloud from './assets/login-alert-cloud.svg'
import { GLOBAL_REFRESH_CONFIG_EVENT, GLOBAL_REFRESH_EVENT } from './composables/useGlobalRefresh'

const router = useRouter()
const route = useRoute()
const settingsKey = 'pc-monitor-settings-v1'
const defaultRefreshIntervalSec = 30
const defaultRefreshEnabled = true
const minRefreshIntervalSec = 5
const maxRefreshIntervalSec = 300
let globalRefreshTimer: ReturnType<typeof setInterval> | null = null
let nextGlobalRefreshAt = 0

const activeMenu = computed(() => route.name as string)

const isAuthLayout = computed(() => route.meta?.layout === 'auth')

const username = ref(localStorage.getItem('pc_username') || 'admin')
watch(
  () => route.fullPath,
  () => {
    username.value = localStorage.getItem('pc_username') || 'admin'
  },
  { immediate: true },
)

const handleSelect = (index: string) => {
  router.push({ name: index })
}

const handleCommand = (command: string) => {
  if (command === 'logout') {
    localStorage.removeItem('pc_token')
    localStorage.removeItem('pc_username')
    router.replace({ name: 'login' })
    return
  }
  if (command === 'settings') {
    router.push({ name: 'settings' })
    return
  }
  if (command === 'users') {
    router.push({ name: 'users' })
    return
  }
}

function readGlobalRefreshConfig(): { enabled: boolean; intervalSec: number } {
  const raw = localStorage.getItem(settingsKey)
  if (!raw) {
    return { enabled: defaultRefreshEnabled, intervalSec: defaultRefreshIntervalSec }
  }
  try {
    const parsed = JSON.parse(raw) as { refreshEnabled?: boolean; refreshIntervalSec?: number }
    const enabled = parsed.refreshEnabled ?? defaultRefreshEnabled
    const value = Math.floor(Number(parsed.refreshIntervalSec || defaultRefreshIntervalSec))
    if (Number.isNaN(value)) {
      return { enabled, intervalSec: defaultRefreshIntervalSec }
    }
    return {
      enabled,
      intervalSec: Math.max(minRefreshIntervalSec, Math.min(maxRefreshIntervalSec, value)),
    }
  } catch {
    return { enabled: defaultRefreshEnabled, intervalSec: defaultRefreshIntervalSec }
  }
}

function emitGlobalRefreshTick(intervalSec: number) {
  if (isAuthLayout.value || document.hidden) {
    return
  }
  window.dispatchEvent(
    new CustomEvent(GLOBAL_REFRESH_EVENT, {
      detail: {
        timestamp: Date.now(),
        intervalSec,
        route: route.name ?? '',
      },
    }),
  )
}

function stopGlobalRefreshHeartbeat() {
  if (globalRefreshTimer !== null) {
    clearInterval(globalRefreshTimer)
    globalRefreshTimer = null
  }
}

function startGlobalRefreshHeartbeat() {
  stopGlobalRefreshHeartbeat()
  nextGlobalRefreshAt = Date.now() + readGlobalRefreshConfig().intervalSec * 1000
  globalRefreshTimer = setInterval(() => {
    const now = Date.now()
    if (now < nextGlobalRefreshAt) {
      return
    }
    const config = readGlobalRefreshConfig()
    if (config.enabled) {
      emitGlobalRefreshTick(config.intervalSec)
    }
    nextGlobalRefreshAt = now + config.intervalSec * 1000
  }, 1000)
}

function handleGlobalRefreshConfigChanged() {
  nextGlobalRefreshAt = Date.now() + readGlobalRefreshConfig().intervalSec * 1000
}

onMounted(() => {
  window.addEventListener(GLOBAL_REFRESH_CONFIG_EVENT, handleGlobalRefreshConfigChanged as EventListener)
  startGlobalRefreshHeartbeat()
})

onUnmounted(() => {
  window.removeEventListener(GLOBAL_REFRESH_CONFIG_EVENT, handleGlobalRefreshConfigChanged as EventListener)
  stopGlobalRefreshHeartbeat()
})
</script>

<template>
  <div>
    <div v-if="isAuthLayout" class="auth-layout">
      <div class="auth-shell">
        <section class="auth-visual">
          <img :src="loginAlertCloud" class="auth-visual-image" alt="智能告警云图" />
        </section>
        <section class="auth-panel">
          <router-view />
        </section>
      </div>
    </div>
    <div v-else class="app-layout">
      <aside class="sidebar">
        <div class="logo-area">
          <span class="logo-mark">PC</span>
          <span class="logo-text">私有云监控平台</span>
        </div>
        <el-menu
          :default-active="activeMenu"
          class="el-menu-vertical"
          @select="handleSelect"
        >
          <el-menu-item index="dashboard">
            <el-icon><IconMenu /></el-icon>
            <span>总览</span>
          </el-menu-item>
          <el-menu-item index="monitoring">
            <el-icon><DataAnalysis /></el-icon>
            <span>基础监控</span>
          </el-menu-item>
          <el-menu-item index="networkService">
            <el-icon><Connection /></el-icon>
            <span>网络与服务监控</span>
          </el-menu-item>
          <el-menu-item index="dataManagement">
            <el-icon><DataLine /></el-icon>
            <span>监控数据管理</span>
          </el-menu-item>
          <el-menu-item index="users">
            <el-icon><User /></el-icon>
            <span>用户管理</span>
          </el-menu-item>
          <el-sub-menu index="alerts">
            <template #title>
              <el-icon><Bell /></el-icon>
              <span>智能告警</span>
            </template>
            <el-menu-item index="alerts">
              <el-icon><Collection /></el-icon>
              <span>告警规则</span>
            </el-menu-item>
            <el-menu-item index="alertHistory">
              <el-icon><Collection /></el-icon>
              <span>告警历史</span>
            </el-menu-item>
          </el-sub-menu>
          <el-sub-menu index="settings">
            <template #title>
              <el-icon><Setting /></el-icon>
              <span>系统设置</span>
            </template>
            <el-menu-item index="settingsBasic">
              <el-icon><Setting /></el-icon>
              <span>基础配置</span>
            </el-menu-item>
            <el-menu-item index="settingsTargets">
              <el-icon><Setting /></el-icon>
              <span>监控对象</span>
            </el-menu-item>
          </el-sub-menu>
        </el-menu>
      </aside>
      <section class="main">
        <header class="header">
          <div class="header-left">
            <h1 class="page-title">
              {{ route.meta.title ?? '私有云监控平台' }}
            </h1>
            <p class="page-subtitle">
              私有云基础监控与智能告警
            </p>
          </div>
          <div class="header-right">
            <el-dropdown @command="handleCommand">
              <div class="user-trigger">
                <el-avatar :size="32" class="avatar">{{ username.slice(0, 1).toUpperCase() }}</el-avatar>
                <span class="user-name">{{ username }}</span>
              </div>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="users">用户管理</el-dropdown-item>
                  <el-dropdown-item command="settings">系统设置</el-dropdown-item>
                  <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </header>
        <main class="content system-gradient-surface">
          <router-view />
        </main>
      </section>
    </div>
  </div>
</template>

<style scoped>
.auth-layout {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(circle at 12% 18%, rgba(56, 189, 248, 0.16) 0%, transparent 40%),
    radial-gradient(circle at 86% 86%, rgba(59, 130, 246, 0.18) 0%, transparent 45%),
    linear-gradient(160deg, #edf4ff 0%, #e7f0ff 48%, #ecf7ff 100%);
  padding: 24px;
}

.auth-shell {
  width: 100%;
  max-width: 1180px;
  min-height: 680px;
  border-radius: 24px;
  border: 1px solid rgba(129, 157, 201, 0.26);
  overflow: hidden;
  display: grid;
  grid-template-columns: 1.08fr 0.92fr;
  box-shadow:
    0 26px 52px rgba(37, 72, 128, 0.18),
    0 1px 0 rgba(255, 255, 255, 0.8) inset;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.82), rgba(245, 251, 255, 0.7));
  backdrop-filter: blur(8px);
}

.auth-visual {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 28px;
  background:
    radial-gradient(circle at 50% 40%, rgba(56, 189, 248, 0.2) 0%, rgba(56, 189, 248, 0) 58%),
    linear-gradient(145deg, #0d2f73 0%, #11479b 55%, #0c3a86 100%);
}

.auth-visual::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    linear-gradient(transparent 0%, transparent 88%, rgba(148, 207, 255, 0.12) 100%),
    repeating-linear-gradient(
      115deg,
      transparent 0 36px,
      rgba(158, 222, 255, 0.03) 36px 40px
    );
  pointer-events: none;
}

.auth-visual-image {
  width: min(86%, 640px);
  max-height: 86%;
  object-fit: contain;
  filter: drop-shadow(0 16px 30px rgba(2, 16, 43, 0.5));
}

.auth-panel {
  width: 100%;
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 36px 44px;
  background:
    radial-gradient(circle at 86% 12%, rgba(59, 130, 246, 0.14) 0%, transparent 45%),
    radial-gradient(circle at 10% 88%, rgba(16, 185, 129, 0.11) 0%, transparent 42%),
    linear-gradient(160deg, rgba(245, 250, 255, 0.92) 0%, rgba(233, 244, 255, 0.88) 100%);
}

.auth-panel :deep(.login-page) {
  max-width: 420px;
  width: 100%;
}

@media (max-width: 1024px) {
  .auth-shell {
    min-height: 0;
    grid-template-columns: 1fr;
  }

  .auth-visual {
    min-height: 280px;
    padding: 20px;
  }

  .auth-visual-image {
    width: min(92%, 520px);
  }
}

@media (max-width: 640px) {
  .auth-layout {
    padding: 12px;
  }

  .auth-shell {
    border-radius: 16px;
  }

  .auth-visual {
    min-height: 220px;
  }

  .auth-panel {
    padding: 22px 18px;
  }
}

.auth-logo {
  display: flex;
  align-items: center;
  margin-bottom: 18px;
}

.auth-text {
  display: flex;
  flex-direction: column;
}

.auth-title {
  font-size: 18px;
  font-weight: 700;
  color: #2b3a67;
}

.auth-subtitle {
  margin-top: 4px;
  font-size: 12px;
  color: #8f8063;
}

.app-layout {
  display: flex;
  height: 100vh;
  background: #f5f7fa;
  color: #1f2933;
}

.sidebar {
  width: 240px;
  background: linear-gradient(180deg, #1a2a6c 0%, #243b85 55%, #1f2f73 100%);
  color: #eef2ff;
  display: flex;
  flex-direction: column;
  padding: 16px 0;
}

.logo-area {
  display: flex;
  align-items: center;
  padding: 0 20px 16px;
  border-bottom: 1px solid #6f86d9;
  margin-bottom: 8px;
}

.logo-mark {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #f7e7b4, #d4af37);
  border: 1px solid #d4af37;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  color: #5a430a;
  margin-right: 10px;
}

.logo-text {
  font-size: 15px;
  font-weight: 600;
  color: #f4f6ff;
}

.el-menu-vertical {
  border-right: none;
  background-color: transparent;
  flex: 1;
}

:deep(.el-menu) {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #dfe7ff;
  --el-menu-hover-bg-color: #314fa8;
  --el-menu-active-color: #2a2f73;
}

:deep(.el-menu-item),
:deep(.el-sub-menu__title) {
  margin: 4px 10px;
  border-radius: 10px;
  color: #dfe7ff !important;
}

:deep(.el-menu-item:hover),
:deep(.el-sub-menu__title:hover) {
  background: #314fa8 !important;
  color: #f4f6ff !important;
}

:deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  background: #314fa8 !important;
  color: #f4f6ff !important;
}

:deep(.el-menu-item.is-active) {
  background: #f7f1dc !important;
  color: #2a2f73 !important;
  font-weight: 700;
  position: relative;
}

:deep(.el-menu-item.is-active::before) {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 4px;
  border-radius: 0 2px 2px 0;
  background: #d4af37;
}

:deep(.el-menu-item .el-icon),
:deep(.el-sub-menu__title .el-icon) {
  color: inherit !important;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.header {
  height: 72px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e5e7eb;
  background: #ffffffcc;
  backdrop-filter: blur(12px);
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.page-subtitle {
  margin: 4px 0 0;
  font-size: 12px;
  color: #6b7280;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}

.user-name {
  font-size: 13px;
  color: #111827;
}

.avatar {
  background: linear-gradient(135deg, #0ea5e9, #22c55e);
  color: #0b1120;
  font-weight: 700;
}

.content {
  flex: 1;
  padding: 16px 24px 24px;
  overflow: auto;
}

@media print {
  .sidebar {
    background: #ffffff !important;
    color: #000000 !important;
    border-right: 1px solid #000000;
  }

  .logo-area {
    border-bottom: 1px solid #000000;
  }

  .logo-mark {
    background: #ffffff !important;
    border: 1px solid #000000;
    color: #000000;
  }

  .logo-text {
    color: #000000;
  }

  :deep(.el-menu-item),
  :deep(.el-sub-menu__title) {
    color: #000000 !important;
    background: #ffffff !important;
    border: 1px solid #d1d5db;
  }

  :deep(.el-menu-item.is-active) {
    background: #e5e7eb !important;
    border-color: #000000;
  }

  :deep(.el-menu-item.is-active::before) {
    background: #000000;
  }
}
</style>
