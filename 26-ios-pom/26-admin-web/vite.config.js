import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { apiOrigin } from "./src/config.js";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: apiOrigin,
        changeOrigin: true,
      },
    },
  },
});
