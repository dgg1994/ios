<template>
  <div class="cr-page list-fill">
    <div class="list-fill-head">
    <div class="cr-head">
      <div>
        <h1>归集日志</h1>
        <p class="cr-sub">一键归集执行流水，按时间倒序展示。可见范围与全部地址一致。</p>
      </div>
      <router-link class="cr-link-back" to="/addresses">← 地址列表</router-link>
    </div>
    <form class="cr-filters" @submit.prevent="search">
      <select v-model="filters.chain">
        <option value="">全部链</option>
        <option value="tron">TRON</option>
        <option value="eth">ETH</option>
        <option value="bsc">BSC</option>
        <option value="btc">BTC</option>
      </select>
      <select v-model="filters.status">
        <option value="">全部状态</option>
        <option value="success">成功</option>
        <option value="failed">失败</option>
      </select>
      <input v-model="filters.device_id" placeholder="设备 ID" maxlength="64" />
      <input v-model="filters.appid" placeholder="应用 ID" maxlength="64" />
      <button type="submit" class="cr-btn-search">查询</button>
      <button type="button" class="cr-btn-reset" @click="reset">重置</button>
    </form>
    <div class="cr-stats">共 {{ total }} 条 · 第 {{ page + 1 }} / {{ totalPages || 1 }} 页</div>
    </div>
    <div class="cr-list">
      <div v-if="!items.length" class="mn-empty">暂无归集记录</div>
      <article v-for="r in items" :key="r.id" class="cr-card" :class="{ 'is-ok': r.status === 'success', 'is-fail': r.status === 'failed' }">
        <div class="cr-card-top">
          <div class="cr-card-id">#{{ r.id }}</div>
          <div class="cr-badge-chain">{{ r.chain }}</div>
          <div class="cr-badge-coin">{{ r.coin }}</div>
          <div class="cr-badge-status">{{ r.status_label || r.status }}</div>
          <div class="cr-amount">{{ r.amount }}</div>
          <div class="cr-time">{{ r.addtime_label || r.addtimeLabel }}</div>
        </div>
        <div class="cr-card-grid">
          <div class="cr-field"><span class="cr-k">转出</span><span class="cr-v mono">{{ r.outaddress }}</span></div>
          <div class="cr-field"><span class="cr-k">转入</span><span class="cr-v mono">{{ r.inaddress }}</span></div>
          <div class="cr-field"><span class="cr-k">交易</span><span class="cr-v mono">{{ r.tx_hash || r.txHash || "—" }}</span></div>
          <div class="cr-field">
            <span class="cr-k">设备</span>
            <span class="cr-v"><router-link v-if="r.deviceId" :to="'/devices/' + r.deviceId">{{ r.deviceId }}</router-link><span v-else>—</span></span>
          </div>
          <div class="cr-field"><span class="cr-k">AppID</span><span class="cr-v mono">{{ r.appId || "—" }}</span></div>
        </div>
      </article>
    </div>
    <div class="vue-pager list-fill-foot" v-if="total">
      <div>第 {{ page + 1 }} / {{ totalPages || 1 }} 页 · 共 {{ total }} 条</div>
      <div class="links">
        <button :disabled="page<=0" @click="go(page-1)">上一页</button>
        <button :disabled="page+1>=totalPages" @click="go(page+1)">下一页</button>
      </div>
    </div>
  </div>
</template>
<script>
import { api } from "../api/http";
import { toast } from "../utils/ui";
export default {
  data() {
    return { items: [], page: 0, size: 50, total: 0, totalPages: 0, filters: { device_id: "", chain: "", status: "", appid: "" } };
  },
  mounted() { this.reload(); },
  methods: {
    async reload() {
      try {
        const body = await api.collectRecords({ page: this.page, size: this.size, ...this.filters });
        const d = body.data || {};
        this.items = d.items || [];
        this.total = d.total || 0;
        this.totalPages = d.totalPages || 0;
        this.page = d.page || 0;
      } catch (e) { toast(e.message, "err"); }
    },
    search() { this.page = 0; this.reload(); },
    reset() { this.filters = { device_id: "", chain: "", status: "", appid: "" }; this.search(); },
    go(p) { this.page = p; this.reload(); },
  },
};
</script>
