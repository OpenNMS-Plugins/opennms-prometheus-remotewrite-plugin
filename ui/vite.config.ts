import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  build: {
    lib: {
      entry: resolve(__dirname, 'src/main.ts'),
      name: 'prometheusremotewrite',
      // IIFE format so Rollup replaces 'import from vue' with window.Vue.*
      // (OpenNMS provides Vue as window.Vue at runtime)
      formats: ['iife'],
      fileName: () => 'prometheusremotewrite.es.js',
    },
    rollupOptions: {
      external: ['vue'],
      output: {
        globals: { vue: 'Vue' },
      },
    },
    outDir: '../plugin/src/main/resources/prometheusremotewrite',
    emptyOutDir: true,
  },
})
