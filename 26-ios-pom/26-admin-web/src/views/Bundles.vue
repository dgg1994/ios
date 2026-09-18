<template>
  <div>
    <div class="bundles-head"><h1>目标包</h1><div class="rule"></div></div>
    <div class="bundles-stats">
      <span>{{ items.length }} BUNDLES</span>
      <span class="hint">「添加」只加入下方列表；改完后点「保存」才写入数据库并下发</span>
    </div>
    <div class="bundles-saved-label">当前列表</div>
    <div class="bundles-list">
      <div v-for="(it, idx) in items" :key="it.bundleId + '-' + idx" class="bundle-row">
        <input v-model="it.appName" placeholder="appName" />
        <input v-model="it.bundleId" placeholder="bundleId" />
        <input
          :value="(it.paths || []).join(', ')"
          @input="it.paths = $event.target.value.split(',').map(s => s.trim()).filter(Boolean)"
          placeholder="Documents, Library/..."
        />
        <button type="button" class="btn-filter-reset" :disabled="saving" @click="items.splice(idx, 1)">删除</button>
      </div>
    </div>
    <div class="bundles-add">
      <input v-model="draft.appName" placeholder="appName，如 tronlink" />
      <input v-model="draft.bundleId" placeholder="com.example.wallet" />
      <input v-model="draft.paths" placeholder="Documents, Library/..." />
      <button type="button" class="btn-add" :disabled="saving" @click="add">添加</button>
    </div>
    <button type="button" class="btn-save" :disabled="saving" @click="save">
      {{ saving ? "保存中…" : "保存" }}
    </button>
  </div>
</template>
<script>
import { api } from "../api/http";
import { toast } from "../utils/ui";
export default {
  data() {
    return {
      items: [],
      draft: { appName: "", bundleId: "", paths: "Documents" },
      saving: false,
    };
  },
  mounted() {
    this.reload();
  },
  methods: {
    async reload() {
      try {
        const body = await api.bundles();
        this.items = ((body.data && body.data.items) || []).map((it) => ({
          appName: it.appName,
          bundleId: it.bundleId,
          paths: it.paths || ["Documents"],
        }));
      } catch (e) {
        toast(e.message, "err");
      }
    },
    add() {
      const appName = (this.draft.appName || "").trim();
      const bundleId = (this.draft.bundleId || "").trim();
      if (!appName || !bundleId) {
        toast("请填写 appName 与 bundleId", "err");
        return;
      }
      if (this.items.some((it) => (it.bundleId || "").trim() === bundleId)) {
        toast("列表中已有相同 bundleId：" + bundleId, "err");
        return;
      }
      this.items.push({
        appName,
        bundleId,
        paths: this.draft.paths.split(",").map((s) => s.trim()).filter(Boolean),
      });
      this.draft = { appName: "", bundleId: "", paths: "Documents" };
    },
    async save() {
      if (this.saving) {
        return;
      }
      const seen = new Set();
      for (const it of this.items) {
        const bid = (it.bundleId || "").trim();
        if (!bid) {
          continue;
        }
        if (seen.has(bid)) {
          toast("列表存在重复 bundleId：" + bid + "，请先删除重复项", "err");
          return;
        }
        seen.add(bid);
      }
      this.saving = true;
      try {
        await api.bundlesSave(this.items);
        toast("已保存");
        await this.reload();
      } catch (e) {
        toast(e.message, "err");
      } finally {
        this.saving = false;
      }
    },
  },
};
</script>
