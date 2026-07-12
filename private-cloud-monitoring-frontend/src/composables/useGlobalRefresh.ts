import { onMounted, onUnmounted } from 'vue'

export const GLOBAL_REFRESH_EVENT = 'pc-global-refresh-tick'
export const GLOBAL_REFRESH_CONFIG_EVENT = 'pc-global-refresh-config-changed'

interface UseGlobalRefreshOptions {
  minGapMs?: number
  disabled?: () => boolean
}

function isPromiseLike(value: unknown): value is Promise<unknown> {
  return typeof value === 'object' && value !== null && 'then' in value
}

export function useGlobalRefresh(
  handler: () => void | Promise<void>,
  options: UseGlobalRefreshOptions = {},
) {
  let running = false
  let lastInvokeAt = 0
  const minGapMs = Math.max(0, Math.floor(options.minGapMs ?? 1000))

  const onTick = () => {
    if (options.disabled?.()) {
      return
    }
    const now = Date.now()
    if (now - lastInvokeAt < minGapMs || running) {
      return
    }
    lastInvokeAt = now
    let result: void | Promise<void>
    try {
      result = handler()
    } catch {
      return
    }
    if (isPromiseLike(result)) {
      running = true
      result.catch(() => undefined).finally(() => {
        running = false
      })
    }
  }

  onMounted(() => {
    window.addEventListener(GLOBAL_REFRESH_EVENT, onTick as EventListener)
  })

  onUnmounted(() => {
    window.removeEventListener(GLOBAL_REFRESH_EVENT, onTick as EventListener)
  })
}
