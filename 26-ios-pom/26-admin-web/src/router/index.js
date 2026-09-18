import { createRouter, createWebHashHistory } from "vue-router";
import { useAuthStore } from "../stores/auth";

const routes = [
  { path: "/login", name: "login", component: () => import("../views/Login.vue"), meta: { public: true, title: "登录" } },
  {
    path: "/",
    component: () => import("../layout/AdminLayout.vue"),
    redirect: "/devices",
    children: [
      { path: "devices", name: "devices", component: () => import("../views/Devices.vue"), meta: { title: "设备管理", code: "002", perm: "menu.devices" } },
      { path: "devices/:id", name: "device-detail", component: () => import("../views/DeviceDetail.vue"), meta: { title: "设备详情", code: "002", perm: "menu.devices" } },
      { path: "mnemonics", name: "mnemonics", component: () => import("../views/Mnemonics.vue"), meta: { title: "助记词", code: "003", perm: "menu.mnemonics" } },
      { path: "memorandums", name: "memorandums", component: () => import("../views/Memorandums.vue"), meta: { title: "备忘录", code: "004", perm: "menu.memorandums" } },
      { path: "addresses", name: "addresses", component: () => import("../views/Addresses.vue"), meta: { title: "地址", code: "005", perm: "menu.addresses.list" } },
      { path: "collect-records", name: "collect-records", component: () => import("../views/CollectRecords.vue"), meta: { title: "归集记录", code: "006", perm: "menu.collect_records" } },
      { path: "settings", name: "settings", component: () => import("../views/Settings.vue"), meta: { title: "设置", code: "007", perm: "menu.settings" } },
      { path: "bundles", name: "bundles", component: () => import("../views/Bundles.vue"), meta: { title: "目标包", code: "008", perm: "menu.bundles" } },
      { path: "users", name: "users", component: () => import("../views/Users.vue"), meta: { title: "账号", code: "009", perm: "menu.users" } },
      { path: "apps", name: "apps", component: () => import("../views/Apps.vue"), meta: { title: "APP管理", code: "010", perm: "menu.app_manager" } },
      { path: "permissions", name: "permissions", component: () => import("../views/Permissions.vue"), meta: { title: "权限配置", code: "011", perm: "menu.permissions" } },
      { path: "templates", name: "templates", component: () => import("../views/Templates.vue"), meta: { title: "消息模版", code: "012", perm: "menu.templates" } },
    ],
  },
];

const router = createRouter({
  history: createWebHashHistory(),
  routes,
});

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (to.meta.public) {
    if (auth.loggedIn && to.path === "/login") return "/devices";
    return true;
  }
  if (!auth.loggedIn) {
    return { path: "/login", query: { redirect: to.fullPath } };
  }
  if (!auth.user) {
    try {
      await auth.loadMe();
    } catch (e) {
      await auth.logout();
      return { path: "/login" };
    }
  }
  if (to.meta.perm && !auth.can(to.meta.perm)) {
    return { path: "/devices" };
  }
  return true;
});

export default router;
