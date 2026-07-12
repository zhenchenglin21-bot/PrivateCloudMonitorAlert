<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

interface LoginForm {
  username: string
  password: string
}

interface LoginResponseData {
  username: string
  token: string
}

interface ApiResponse<T> {
  code: number
  message: string
  data?: T
}

const router = useRouter()
const loading = ref(false)

const form = reactive<LoginForm>({
  username: '',
  password: '',
})

const handleLogin = async () => {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }

  loading.value = true
  try {
    const baseUrl =
      import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'
    const resp = await fetch(`${baseUrl}/api/auth/login`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(form),
    })

    const result = (await resp.json()) as ApiResponse<LoginResponseData>

    if (result.code === 200 && result.data) {
      localStorage.setItem('pc_token', result.data.token)
      localStorage.setItem('pc_username', result.data.username)
      ElMessage.success('登录成功')
      await router.replace({ name: 'dashboard' })
    } else {
      ElMessage.error(result.message || '登录失败')
    }
  } catch (e) {
    ElMessage.error('无法连接到后端服务，请检查 PrivateCloud 是否已启动')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <h1 class="title">私有云监控平台</h1>
    <p class="subtitle">基础监控与智能告警</p>

    <el-form class="form" label-width="72px" @submit.prevent>
      <el-form-item label="账号">
        <el-input
          v-model="form.username"
          placeholder="请输入用户名"
          autocomplete="username"
        />
      </el-form-item>
      <el-form-item label="密码">
        <el-input
          v-model="form.password"
          placeholder="请输入密码"
          type="password"
          show-password
          autocomplete="current-password"
        />
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          class="submit-btn"
          :loading="loading"
          @click="handleLogin"
        >
          登录
        </el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<style scoped>
.login-page {
  max-width: 420px;
  width: 100%;
}

.title {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 0.4px;
  color: #2b3a67;
}

.subtitle {
  margin: 6px 0 24px;
  font-size: 13px;
  color: #8f8063;
}

.form {
  margin-top: 8px;
}

.submit-btn {
  width: 100%;
  height: 42px;
  border: none;
  border-radius: 10px;
  background: linear-gradient(135deg, #2b3a67 0%, #405ea8 100%);
  color: #f8f8f8;
  font-weight: 600;
  letter-spacing: 0.2px;
}

:deep(.el-form-item__label) {
  color: #495882;
  font-weight: 600;
  justify-content: flex-start;
}

:deep(.el-input__wrapper) {
  border-radius: 10px;
  box-shadow: 0 0 0 1px #d9dfef inset !important;
  background: #fbfcff;
}

:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #4d69b8 inset !important;
}

:deep(.el-input__inner) {
  color: #233051;
}

:deep(.el-input__inner::placeholder) {
  color: #a0aabf;
}

:deep(.el-button.submit-btn:hover) {
  background: linear-gradient(135deg, #243256 0%, #375497 100%);
}

:deep(.el-button.submit-btn:focus-visible) {
  outline: 2px solid #d4af37;
  outline-offset: 2px;
}

</style>
