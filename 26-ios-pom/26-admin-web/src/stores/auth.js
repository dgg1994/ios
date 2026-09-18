import { defineStore } from "pinia";
import { api } from "../api/http";

export const useAuthStore = defineStore("auth", {
  state: () => ({
    token: localStorage.getItem("admin_token") || "",
    user: null,
  }),
  getters: {
    loggedIn: (s) => Boolean(s.token),
    permissions: (s) => (s.user && s.user.permissions) || [],
    menu: (s) => (s.user && s.user.menu) || [],
    superadmin: (s) => Boolean(s.user && s.user.superadmin),
  },
  actions: {
    can(code) {
      if (this.superadmin) return true;
      return this.permissions.includes(code);
    },
    applySession(data, token) {
      if (token) {
        this.token = token;
        localStorage.setItem("admin_token", token);
      }
      this.user = data;
    },
    async login(username, password) {
      const body = await api.login(username, password);
      this.applySession(body.data, body.data && body.data.token);
      return body.data;
    },
    async loadMe() {
      const body = await api.me();
      this.applySession(body.data, this.token);
      return body.data;
    },
    async logout() {
      try {
        await api.logout();
      } catch (_) {
        /* ignore */
      }
      this.token = "";
      this.user = null;
      localStorage.removeItem("admin_token");
    },
  },
});
