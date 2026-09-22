<template>
  <div class="list-fill">
    <div class="list-fill-head">
    <div class="mn-head"><h1>备忘录</h1><div class="rule"></div></div>
    <p class="mn-sub">NOTES · 入库备忘录列表脱敏；点击完整查看直接展示明文</p>
    <form class="mn-filters" @submit.prevent="search">
      <input v-model="deviceId" placeholder="设备 ID" maxlength="64" />
      <button type="submit" class="btn-filter-search">查询</button>
      <button type="button" class="btn-filter-reset" @click="deviceId=''; search()">重置</button>
    </form>
    <div class="mn-stats">共 {{ total }} 条 · 第 {{ page + 1 }} / {{ totalPages || 1 }} 页</div>
    </div>
    <div class="mn-table-card">
      <div v-if="!items.length" class="mn-empty">暂无备忘录</div>
      <table v-else class="mn-table">
        <thead><tr><th class="col-id">ID</th><th>设备</th><th>归属</th><th>内容</th><th>时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="m in items" :key="m.id">
            <td class="col-id">{{ m.id }}</td>
            <td><div class="mn-device">{{ m.deviceId }}</div></td>
            <td>{{ m.ownerLabel }}</td>
            <td><div class="mn-phrase">{{ m.resultMasked }}</div></td>
            <td>{{ m.addtimeLabel }}</td>
            <td><button class="btn-mn-reveal" @click="open(m)">完整查看</button></td>
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
    <RevealModal ref="modal" :visible="openId!=null" title="完整备忘录" :need-password="false" @close="openId=null" @confirm="reveal" />
  </div>
</template>
<script>
import { api } from "../api/http";
import { toast } from "../utils/ui";
import RevealModal from "../components/RevealModal.vue";
export default {
  components: { RevealModal },
  data() {
    return { items: [], page: 0, size: 20, total: 0, totalPages: 0, deviceId: this.$route.query.device_id || "", openId: null };
  },
  mounted() { this.reload(); },
  methods: {
    async reload() {
      try {
        const body = await api.memorandums({ page: this.page, size: this.size, device_id: this.deviceId });
        const d = body.data || {};
        this.items = d.items || [];
        this.total = d.total || 0;
        this.totalPages = d.totalPages || 0;
        this.page = d.page || 0;
      } catch (e) { toast(e.message, "err"); }
    },
    search() { this.page = 0; this.reload(); },
    go(p) { this.page = p; this.reload(); },
    open(m) { this.openId = m.id; },
    async reveal() {
      try {
        const body = await api.memorandumReveal(this.openId, "");
        const text = (body.data && (body.data.content || body.data.text || body.data.phrase)) || "";
        if (this.$refs.modal && this.$refs.modal.setResult) this.$refs.modal.setResult(text);
        else toast(text || "无内容");
      } catch (e) {
        if (this.$refs.modal && this.$refs.modal.setError) this.$refs.modal.setError(e.message);
        else toast(e.message, "err");
      }
    },
  },
};
</script>
