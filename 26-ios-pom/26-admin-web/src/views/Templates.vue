<template>
  <div>
    <div class="tpl-head"><h1>消息模版</h1><div class="rule"></div></div>
    <p class="tpl-tip">
      使用 <code>{变量名}</code> 作为占位符。
      <code>model</code> 会自动填成「硬件型号/系统版本」。
      新设备注册、采集完成、助记词余额等推送都读这里。
    </p>
    <div v-if="!items.length" class="tpl-empty">暂无模版</div>
    <div v-else class="tpl-list">
      <form v-for="t in items" :key="t.id" class="tpl-card" @submit.prevent="save(t)">
        <div class="tpl-card-head">
          <div>
            <div class="tpl-code">{{ t.code }}</div>
            <input class="tpl-name" v-model="t.name" maxlength="64" required />
          </div>
          <label class="tpl-status">
            <span>状态</span>
            <select v-model="t.status">
              <option :value="1">启用</option>
              <option :value="0">停用</option>
            </select>
          </label>
        </div>
        <div v-if="t.variables" class="tpl-vars">可用变量：<code v-for="v in vars(t.variables)" :key="v">{{ "{" + v + "}" }}</code></div>
        <div v-if="t.remark" class="tpl-remark">{{ t.remark }}</div>
        <textarea v-model="t.body" rows="12" required></textarea>
        <div class="tpl-actions">
          <button type="submit" class="btn-tpl-save">保存模版</button>
        </div>
      </form>
    </div>
  </div>
</template>
<script>
import { api } from "../api/http";
import { toast } from "../utils/ui";
export default {
  data() { return { items: [] }; },
  mounted() { this.reload(); },
  methods: {
    vars(raw) {
      return String(raw || "").split(",").map((s) => s.trim()).filter(Boolean);
    },
    async reload() {
      try {
        const body = await api.templates();
        this.items = (body.data && body.data.items) || [];
      } catch (e) { toast(e.message, "err"); }
    },
    async save(t) {
      try {
        await api.templateSave(t.id, { name: t.name, body: t.body, status: t.status });
        toast("模版已保存");
        this.reload();
      } catch (e) { toast(e.message, "err"); }
    },
  },
};
</script>
