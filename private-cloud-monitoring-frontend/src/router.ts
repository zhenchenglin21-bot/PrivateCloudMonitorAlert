import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: { name: 'login' },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('./views/LoginView.vue'),
    meta: {
      title: '登录',
      layout: 'auth',
    },
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('./views/DashboardView.vue'),
    meta: {
      title: '总览',
      requiresAuth: true,
    },
  },
  {
    path: '/monitoring',
    name: 'monitoring',
    component: () => import('./views/MonitoringView.vue'),
    meta: {
      title: '基础监控',
      requiresAuth: true,
    },
  },
  {
    path: '/network-service',
    name: 'networkService',
    component: () => import('./views/NetworkServiceView.vue'),
    meta: {
      title: '网络与服务监控',
      requiresAuth: true,
    },
  },
  {
    path: '/data-management',
    name: 'dataManagement',
    component: () => import('./views/DataManagementView.vue'),
    meta: {
      title: '监控数据管理',
      requiresAuth: true,
    },
  },
  {
    path: '/users',
    name: 'users',
    component: () => import('./views/UserManagementView.vue'),
    meta: {
      title: '用户管理',
      requiresAuth: true,
    },
  },
  {
    path: '/alerts',
    name: 'alerts',
    component: () => import('./views/AlertsView.vue'),
    meta: {
      title: '告警规则',
      requiresAuth: true,
    },
  },
  {
    path: '/alerts/history',
    name: 'alertHistory',
    component: () => import('./views/AlertHistoryView.vue'),
    meta: {
      title: '告警历史',
      requiresAuth: true,
    },
  },
  {
    path: '/settings',
    name: 'settings',
    redirect: { name: 'settingsBasic' },
    meta: {
      title: '系统设置',
      requiresAuth: true,
    },
  },
  {
    path: '/settings/basic',
    name: 'settingsBasic',
    component: () => import('./views/SettingsBasicView.vue'),
    meta: {
      title: '基础配置',
      requiresAuth: true,
    },
  },
  {
    path: '/settings/targets',
    name: 'settingsTargets',
    component: () => import('./views/MonitorTargetsView.vue'),
    meta: {
      title: '监控对象',
      requiresAuth: true,
    },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  if (to.name === 'login') {
    return true
  }
  if (to.meta?.requiresAuth) {
    const token = localStorage.getItem('pc_token')
    if (!token) {
      return { name: 'login' }
    }
  }
  return true
})

router.afterEach((to) => {
  if (to.meta?.title) {
    document.title = `私有云监控平台 - ${to.meta.title as string}`
  } else {
    document.title = '私有云监控平台'
  }
})

export default router
