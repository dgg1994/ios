<template>
  <div>
    <div class="settings-head"><h1>设置</h1><div class="rule"></div></div>
    <form @submit.prevent="save">
      <section v-for="group in groups" :key="group.name" class="settings-section">
        <div class="settings-section-title">{{ group.name }}</div>
        <div class="settings-card settings-card-stack" v-for="item in group.items" :key="item.configKey">
          <div class="settings-card-main">
            <div class="settings-kicker">{{ item.configKey }}</div>
            <div class="settings-title">{{ item.label || item.configKey }}</div>
            <div class="settings-desc">{{ item.description }}</div>
            <div class="settings-help">{{ item.helpText }}</div>
          </div>
          <label v-if="isBool(item)" class="toggle">
            <input type="checkbox" v-model="form[item.configKey]" true-value="1" false-value="0" />
            <span class="toggle-track"><span class="toggle-thumb"></span></span>
          </label>
          <label v-else class="settings-field">
            <span class="settings-field-label">值</span>
            <input v-model="form[item.configKey]" type="text" />
          </label>
        </div>
      </section>
      <button type="submit" class="btn-save">保存</button>
    </form>
  </div>
</template>
<script>
import { api } from "../api/http";
import { toast } from "../utils/ui";
export default {
  data() { return { items: [], form: {} }; },
  computed: {
    groups() {
      const map = {};
      for (const it of this.items) {
        const name = it.category || "其他";
        if (!map[name]) map[name] = [];
        map[name].push(it);
      }
      return Object.keys(map).map((name) => ({ name, items: map[name] }));
    },
  },
  mounted() { this.reload(); },
  methods: {
    isBool(item) {
      const t = String(item.valueType || "").toLowerCase();
      return t.includes("bool") || t.includes("switch") || t.includes("check");
    },
    async reload() {
      try {
        const body = await api.settings();
        this.items = (body.data && body.data.items) || [];
        const form = {};
        for (const it of this.items) {
          let v = it.configValue == null ? "" : String(it.configValue);
          if (this.isBool(it)) {
            v = ["1", "true", "on", "yes"].includes(v.toLowerCase()) ? "1" : "0";
          }
          form[it.configKey] = v;
        }
        this.form = form;
      } catch (e) { toast(e.message, "err"); }
    },
    async save() {
      try {
        await api.settingsSave(this.form);
        toast("已保存");
        this.reload();
      } catch (e) { toast(e.message, "err"); }
    },
  },
};
</script>
