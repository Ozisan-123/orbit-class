import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * 开发期反向代理 —— 所有 API 统一进入 P3 gateway。
 *
 * 迁移期 Java 侧已经是两个进程，而 Python 侧是一个应用：
 *
 *   gateway    8080   统一入口，再按路径转发到各服务
 *
 * 路由归属只在 gateway 维护一份，前端不再复制分流表。
 *
 * 两点容易踩的细节：
 * 目标用 `127.0.0.1` 而不是 `localhost`：Windows 上 localhost 可能先解析到
 *    IPv6（::1），而 Spring Boot 默认只监听 IPv4，会出现"代理连不上、浏览器却能开"。
 */
const GATEWAY = process.env.VITE_GATEWAY_URL ?? 'http://127.0.0.1:8080'

/** 所有服务路由都由 gateway 决定。 */
const proxy: Record<string, { target: string; changeOrigin: boolean }> = {
  '/api': { target: GATEWAY, changeOrigin: true },
}

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy,
  },
})
