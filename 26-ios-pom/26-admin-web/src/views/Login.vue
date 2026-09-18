<template>
  <main class="login-shell">
    <aside class="login-rail">
      <div class="login-rail-brand">
        <div class="login-rail-mark">EP</div>
        <div>
          <strong>ENDPOINT</strong>
          <span>CONTROL CONSOLE</span>
        </div>
      </div>
      <div class="login-rail-hero">
        <p class="login-rail-kicker">SECURE ACCESS</p>
        <h1>黑金控制台</h1>
        <p class="login-rail-copy">简约 · 大气 · 秩序。进入设备与资产解析工作台。</p>
      </div>
      <div class="login-rail-foot">版本号 · 1.4.8</div>
    </aside>
    <section class="login-main">
      <div class="login-card">
        <div class="login-card-head">
          <span class="login-card-dot"></span>
          <h2>登录</h2>
        </div>
        <p class="login-card-sub">使用后台账号进入系统</p>
        <form class="login-panel" @submit.prevent="onSubmit">
          <div v-if="error" class="login-error" role="alert">{{ error }}</div>
          <div class="login-field">
            <label>用户名</label>
            <div class="login-input-wrap">
              <input v-model="username" type="text" placeholder="请输入用户名" autocomplete="username" required />
            </div>
          </div>
          <div class="login-field">
            <label>密码</label>
            <div class="login-input-wrap">
              <input v-model="password" :type="show ? 'text' : 'password'" placeholder="请输入密码" autocomplete="current-password" required />
              <button type="button" class="login-eye" :aria-label="show ? '隐藏密码' : '显示密码'" :title="show ? '隐藏密码' : '显示密码'" @click="show = !show">
                <!-- eye-off when hidden, eye when shown -->
                <svg v-if="!show" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                  <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                  <path d="M3 3l18 18" />
                  <path d="M10.6 10.6a2.5 2.5 0 0 0 3.5 3.5" />
                  <path d="M9.9 5.1A10.4 10.4 0 0 1 12 5c6.5 0 10 7 10 7a17.6 17.6 0 0 1-3.2 4.1" />
                  <path d="M6.1 6.1A17.3 17.3 0 0 0 2 12s3.5 7 10 7a10.4 10.4 0 0 0 4.2-.9" />
                </svg>
              </button>
            </div>
          </div>
          <button type="submit" class="login-submit" :disabled="loading">{{ loading ? "登录中…" : "进入控制台" }}</button>
        </form>
      </div>
    </section>
  </main>
</template>
<script>
import { useAuthStore } from "../stores/auth";
import { useRoute, useRouter } from "vue-router";

export default {
  data() {
    return { username: "", password: "", show: false, error: "", loading: false };
  },
  setup() {
    return { auth: useAuthStore(), route: useRoute(), router: useRouter() };
  },
  mounted() {
    document.body.classList.add("login-body");
  },
  beforeUnmount() {
    document.body.classList.remove("login-body");
  },
  methods: {
    async onSubmit() {
      this.loading = true;
      this.error = "";
      try {
        await this.auth.login(this.username, this.password);
        this.router.push(this.route.query.redirect || "/devices");
      } catch (e) {
        this.error = e.message || "登录失败";
      } finally {
        this.loading = false;
      }
    },
  },
};
</script>
<style scoped>
.login-shell { min-height: 100vh; }
</style>
