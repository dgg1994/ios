<template>
  <main class="admin-shell">
    <aside class="admin-rail">
      <div class="admin-rail-brand">
        <div class="admin-rail-mark" aria-hidden="true">EP</div>
        <div class="admin-rail-brand-text">
          <strong>ENDPOINT</strong>
          <span>CONTROL CONSOLE</span>
        </div>
      </div>
      <nav class="admin-sidebar">
        <div class="admin-nav-group">
          <template v-for="node in mainMenu" :key="node.code">
            <div v-if="node.children && node.children.length" class="admin-nav-fold" :class="{ 'is-open': isOpen(node) }">
              <button type="button" class="admin-nav-item admin-nav-toggle" :class="{ 'is-active-parent': isOpen(node) }" @click="toggle(node.code)">
                <NavIcon :name="node.icon" />
                <div class="nav-text">
                  <span class="nav-title">{{ node.name }}</span>
                  <span class="nav-en">{{ node.nameEn || "" }}</span>
                </div>
              </button>
              <div class="admin-nav-sub">
                <router-link
                  v-for="c in node.children"
                  :key="c.code"
                  :to="menuPath(c.path)"
                  class="admin-nav-item admin-nav-child"
                  :class="{ 'is-active': isActive(c.path) }"
                >
                  <NavIcon :name="c.icon || node.icon" />
                  <div class="nav-text">
                    <span class="nav-title">{{ c.name }}</span>
                    <span class="nav-en">{{ c.nameEn || "" }}</span>
                  </div>
                </router-link>
              </div>
            </div>
            <router-link
              v-else
              :to="menuPath(node.path)"
              class="admin-nav-item"
              :class="{ 'is-active': isActive(node.path) }"
            >
              <NavIcon :name="node.icon" />
              <div class="nav-text">
                <span class="nav-title">{{ node.name }}</span>
                <span class="nav-en">{{ node.nameEn || "" }}</span>
              </div>
            </router-link>
          </template>
        </div>
        <div class="admin-sidebar-spacer"></div>
        <div class="admin-rail-foot">
          <div class="admin-sidebar-version">
            <span>版本号 · {{ version }}</span>
            <button type="button" class="admin-changelog-link" @click="showLog = true">更新日志</button>
          </div>
          <div class="admin-sidebar-divider"></div>
          <div class="admin-nav-group">
            <router-link
              v-for="node in bottomMenu"
              :key="node.code"
              :to="menuPath(node.path)"
              class="admin-nav-item"
              :class="{ 'is-active': isActive(node.path) }"
            >
              <NavIcon :name="node.icon" />
              <div class="nav-text">
                <span class="nav-title">{{ node.name }}</span>
                <span class="nav-en">{{ node.nameEn || "" }}</span>
              </div>
            </router-link>
          </div>
          <div class="admin-user-chip">
            <span class="admin-user-role">{{ auth.user && (auth.user.roleName || auth.user.role) }}</span>
            <strong>{{ auth.user && auth.user.username }}</strong>
          </div>
          <form class="admin-logout-form" @submit.prevent="onLogout">
            <button type="submit" class="admin-logout-btn">退出登录</button>
          </form>
        </div>
      </nav>
    </aside>
    <section class="admin-main">
      <header class="admin-topbar">
        <div class="admin-topbar-title">
          <span class="admin-topbar-dot"></span>
          <h1>{{ title }}</h1>
        </div>
        <div class="admin-topbar-meta">N° {{ code }}</div>
      </header>
      <div class="admin-main-inner">
        <div v-if="pageLoad.visible" class="page-loading" role="status" aria-live="polite">
          <div class="page-loading-card">
            <span class="page-loading-spin" aria-hidden="true"></span>
            <span>加载中</span>
          </div>
        </div>
        <router-view />
      </div>
    </section>
    <div v-if="showLog" class="reveal-modal is-open is-in" @click.self="showLog = false">
      <div class="reveal-modal-card">
        <div class="reveal-modal-title">更新日志</div>
        <div class="changelog-modal-body">{{ changelog }}</div>
        <div class="reveal-actions" style="margin-top:16px">
          <button type="button" class="reveal-cancel" @click="showLog = false">关闭</button>
        </div>
      </div>
    </div>
  </main>
</template>

<script>
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "../stores/auth";
import { api } from "../api/http";
import { menuPath } from "../utils/ui";
import NavIcon from "../components/NavIcon.vue";
import { pageLoad } from "../utils/pageLoad";

export default {
  components: { NavIcon },
  setup() {
    const auth = useAuthStore();
    const route = useRoute();
    const router = useRouter();
    const open = ref({});
    const showLog = ref(false);
    const changelog = ref("加载中…");

    const mainMenu = computed(() => (auth.menu || []).filter((n) => n.groupKey !== "bottom"));
    const bottomMenu = computed(() => (auth.menu || []).filter((n) => n.groupKey === "bottom"));
    const version = computed(() => (auth.user && auth.user.version) || "1.4.8");
    const title = computed(() => route.meta.title || "控制台");
    const code = computed(() => route.meta.code || "000");

    function isActive(path) {
      const p = menuPath(path);
      return p && (route.path === p || route.path.startsWith(p + "/"));
    }
    function isOpen(node) {
      if (open.value[node.code]) return true;
      return (node.children || []).some((c) => isActive(c.path));
    }
    function toggle(code) {
      open.value = { ...open.value, [code]: !open.value[code] };
    }
    async function onLogout() {
      await auth.logout();
      router.push("/login");
    }
    onMounted(async () => {
      try {
        const body = await api.changelog();
        changelog.value = (body.data && body.data.markdown) || "暂无";
      } catch (e) {
        changelog.value = e.message || "加载失败";
      }
    });
    return { auth, mainMenu, bottomMenu, version, title, code, showLog, changelog, pageLoad, menuPath, isActive, isOpen, toggle, onLogout };
  },
};
</script>
