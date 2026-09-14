import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

// 开发期把 /api 与 /actuator 代理到 core(8081)；
// 生产期产物由 core 的 classpath:/static/ 同源托管，走相对路径，无需代理。
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      /**
       * Semi UI 的 package.json `exports` 没有暴露 `dist/css/*`，
       * Vite 严格解析会报：Missing "./dist/css/semi.min.css" specifier。
       * 官方 FlowGram 物料包（defaultFixedSemiMaterials）里的内联交互组件
       * 是基于 Semi 实现的，需要它的样式，这里直接映射到真实文件。
       */
      '@douyinfe/semi-ui/dist/css/semi.min.css': fileURLToPath(
        new URL('./node_modules/@douyinfe/semi-ui/dist/css/semi.min.css', import.meta.url)
      ),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8081', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8081', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
