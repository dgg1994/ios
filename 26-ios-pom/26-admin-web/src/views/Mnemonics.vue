<template>
  <div>
    <div class="mn-head"><h1>助记词</h1><div class="rule"></div></div>
    <p class="mn-sub">WALLET · 已解析入库的助记词（列表脱敏；点击完整查看直接展示明文）</p>
    <form class="mn-filters" @submit.prevent="search">
      <input v-model="deviceId" placeholder="设备 ID" maxlength="64" />
      <input v-model="mnemonicId" placeholder="助记词 ID" maxlength="32" />
      <button type="submit" class="btn-filter-search">查询</button>
      <button type="button" class="btn-filter-reset" @click="deviceId=''; mnemonicId=''; search()">重置</button>
    </form>
    <div class="mn-stats-bar">
      <div class="mn-stats">共 {{ total }} 条 · 第 {{ page + 1 }} / {{ totalPages || 1 }} 页</div>
      <div class="mn-batch">
        <label class="mn-check-all"><input type="checkbox" :checked="allChecked" @change="toggleAll"> 全选本页</label>
        <span class="mn-selected-hint">已选 {{ selected.length }}</span>
        <button type="button" class="btn-mn-derive" :disabled="!selected.length" @click="derive">派生地址</button>
      </div>
    </div>
    <div class="mn-table-card">
      <div v-if="!items.length" class="mn-empty">暂无助记词数据</div>
      <table v-else class="mn-table">
        <thead>
          <tr>
            <th class="col-check"></th>
            <th class="col-id">ID</th>
            <th>设备</th>
            <th>来源</th>
            <th>归属</th>
            <th>助记词</th>
            <th>时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in items" :key="m.id" class="mn-row">
            <td class="col-check"><input type="checkbox" :value="m.id" v-model="selected" /></td>
            <td class="col-id">{{ m.id }}</td>
            <td>
              <div class="mn-device">{{ m.deviceId }}</div>
              <div class="mn-appid">{{ m.appId || "—" }}</div>
            </td>
            <td><span class="mn-source">{{ m.source }}</span></td>
            <td><div class="mn-owner">{{ m.ownerLabel }}</div></td>
            <td><div class="mn-phrase">{{ m.resultMasked }}</div></td>
            <td><div class="mn-time">{{ m.addtimeLabel }}</div></td>
            <td><button type="button" class="btn-mn-reveal" @click="open(m)">完整查看</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="vue-pager" v-if="total">
      <div>第 {{ page + 1 }} / {{ totalPages || 1 }} 页 · 共 {{ total }} 条</div>
      <div class="links">
        <button :disabled="page<=0" @click="go(page-1)">上一页</button>
        <button :disabled="page+1>=totalPages" @click="go(page+1)">下一页</button>
      </div>
    </div>
    <RevealModal ref="modal" :visible="openId!=null" :need-password="false" @close="openId=null" @confirm="reveal" />
  </div>
</template>
<script>
import { api } from "../api/http";
import { toast } from "../utils/ui";
import RevealModal from "../components/RevealModal.vue";

export default {
  components: { RevealModal },
  data() {
    return { items: [], page: 0, size: 20, total: 0, totalPages: 0, deviceId: this.$route.query.device_id || "", mnemonicId: this.$route.query.id || this.$route.query.mnemonic_id || "", selected: [], openId: null };
  },
  computed: {
    allChecked() {
      return this.items.length && this.items.every((m) => this.selected.includes(m.id));
    },
  },
  mounted() { this.reload(); },
  methods: {
    async reload() {
      try {
        const body = await api.mnemonics({ page: this.page, size: this.size, device_id: this.deviceId, mnemonic_id: this.mnemonicId });
        const d = body.data || {};
        this.items = d.items || [];
        this.total = d.total || 0;
        this.totalPages = d.totalPages || 0;
        this.page = d.page || 0;
        this.selected = [];
      } catch (e) { toast(e.message, "err"); }
    },
    search() { this.page = 0; this.reload(); },
    go(p) { this.page = p; this.reload(); },
    toggleAll(e) {
      this.selected = e.target.checked ? this.items.map((m) => m.id) : [];
    },
    open(m) { this.openId = m.id; },
    async reveal() {
      try {
        const body = await api.mnemonicReveal(this.openId, "");
        const phrase = (body.data && body.data.phrase) || "";
        if (this.$refs.modal && this.$refs.modal.setResult) this.$refs.modal.setResult(phrase);
        else toast(phrase || "无内容");
      } catch (e) {
        if (this.$refs.modal && this.$refs.modal.setError) this.$refs.modal.setError(e.message);
        else toast(e.message, "err");
      }
    },
    async derive() {
      try {
        const body = await api.deriveAddresses(this.selected);
        toast(body.message || "派生完成");
      } catch (e) { toast(e.message, "err"); }
    },
  },
};
</script>
