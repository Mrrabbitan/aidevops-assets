import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
export default defineConfig({
  plugins: [vue()],
  server: {
    host: "0.0.0.0",
    port: 19385,
    strictPort: true,
    proxy: {
      "/api": {
        target: process.env.API_PROXY_TARGET || "http://localhost:19382",
        changeOrigin: true,
      },
    },
  },
  build: { sourcemap: true },
  test: { environment: "jsdom", include: ["src/**/*.test.ts"] },
} as any);
