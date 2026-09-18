<template>
  <div>
    <div class="apps-head"><h1>APP管理</h1><div class="rule"></div></div>
    <div class="apps-tip">
      <strong>说明：</strong>每个 <strong>AppID</strong>（渠道码）在打 IPA 时写死进包。设备注册须带该 AppID；归属可指定<strong>代理 / 渠道 / 业务员</strong>任一层级。
      <p class="apps-tip-limit">系统最多可添加 {{ meta.maxApps || 10 }} 个 app。{{ ipaHint }}</p>
    </div>
    <div class="apps-toolbar">
      <form class="apps-filters" @submit.prevent="search">
        <input v-model="filters.name" placeholder="应用名称" maxlength="128" />
        <input v-model="filters.appid" placeholder="AppID" maxlength="64" class="apps-filter-appid" />
        <div class="apps-owner-cascade apps-filter-owner">
          <select v-model="filters.agent_id">
            <option value="">归属代理</option>
            <option v-for="a in agents" :key="a.id" :value="String(a.id)">{{ a.displayName }}</option>
          </select>
          <select v-model="filters.channel_id" :disabled="!filters.agent_id">
            <option value="">归属渠道</option>
            <option v-for="c in channelsOf(filters.agent_id)" :key="c.id" :value="String(c.id)">{{ c.displayName }}</option>
          </select>
          <select v-model="filters.sales_id" :disabled="!filters.channel_id">
            <option value="">归属业务</option>
            <option v-for="s in salesOf(filters.channel_id)" :key="s.id" :value="String(s.id)">{{ s.displayName }}</option>
          </select>
        </div>
        <button type="submit" class="btn-filter-search">查询</button>
        <button type="button" class="btn-filter-reset" @click="reset">重置</button>
      </form>
      <button type="button" class="btn-app-add" :disabled="meta.atLimit" @click="showCreate = !showCreate">{{ showCreate ? "收起" : "添加" }}</button>
    </div>
    <div v-if="showCreate" class="apps-create-wrap is-open">
      <div class="apps-section-label">CREATE · 新增 App</div>
      <form class="apps-add" @submit.prevent="create">
        <div class="apps-add-grid">
          <input v-model="draft.appname" placeholder="应用名称" maxlength="128" required />
          <div class="apps-owner-cascade">
            <select v-model="draft.agentId">
              <option value="">选择代理</option>
              <option v-for="a in agents" :key="a.id" :value="String(a.id)">{{ a.displayName }}</option>
            </select>
            <select v-model="draft.channelId" :disabled="!draft.agentId">
              <option value="">选择渠道（可选）</option>
              <option v-for="c in channelsOf(draft.agentId)" :key="c.id" :value="String(c.id)">{{ c.displayName }}</option>
            </select>
            <select v-model="draft.salesId" :disabled="!draft.channelId">
              <option value="">选择业务（可选）</option>
              <option v-for="s in salesOf(draft.channelId)" :key="s.id" :value="String(s.id)">{{ s.displayName }}</option>
            </select>
          </div>
          <div class="apps-appid-field">
            <input v-model="draft.appid" maxlength="64" required />
            <button type="button" class="btn-regen" @click="draft.appid = uuid()">生成</button>
          </div>
          <select v-model="draft.status">
            <option value="1">正常</option>
            <option value="0">冻结</option>
          </select>
          <button type="submit" class="btn-app-add" :disabled="meta.atLimit">确认添加</button>
        </div>
        <p class="apps-owner-hint">只选代理 → 归属代理；再选渠道 → 归属渠道；再选业务 → 归属业务员。</p>
      </form>
    </div>
    <div class="apps-stats">
      <span>{{ meta.count || 0 }} APPS</span>
      <span class="stat-on"><span class="dot dot-on"></span> {{ meta.activeCount || 0 }} 正常</span>
      <span class="stat-off"><span class="dot dot-off"></span> {{ meta.frozenCount || 0 }} 冻结</span>
    </div>
    <div class="apps-list">
      <div v-if="!items.length" class="apps-empty">暂无 App</div>
      <div v-for="a in items" :key="a.id" class="app-row">
        <div class="app-body">
          <div class="app-title-row">
            <span class="app-name">{{ a.appname }}</span>
            <span class="badge-app" :class="{ 'is-off': a.status !== 1 }"><span class="dot"></span>{{ a.status === 1 ? "正常" : "冻结" }}</span>
          </div>
          <div class="app-meta">
            <span><span class="k">APPID</span><span class="v mono">{{ a.appid }}</span></span>
            <span><span class="k">归属</span><span class="v">{{ a.roleLabel }} · {{ a.displayName }}（{{ a.username }}）</span></span>
            <span><span class="k">添加</span><span class="v">{{ a.addtimeLabel }}</span></span>
          </div>
          <form v-if="editing === a.id" class="apps-edit-form" @submit.prevent="save(a)">
            <div class="apps-edit-grid">
              <label><span>应用名称</span><input v-model="edit.appname" maxlength="128" required /></label>
              <label class="apps-edit-owner-label">
                <span>归属账号</span>
                <div class="apps-owner-cascade is-edit">
                  <select v-model="edit.agentId">
                    <option value="">选择代理</option>
                    <option v-for="x in agents" :key="x.id" :value="String(x.id)">{{ x.displayName }}</option>
                  </select>
                  <select v-model="edit.channelId">
                    <option value="">选择渠道（可选）</option>
                    <option v-for="x in channelsOf(edit.agentId)" :key="x.id" :value="String(x.id)">{{ x.displayName }}</option>
                  </select>
                  <select v-model="edit.salesId">
                    <option value="">选择业务（可选）</option>
                    <option v-for="x in salesOf(edit.channelId)" :key="x.id" :value="String(x.id)">{{ x.displayName }}</option>
                  </select>
                </div>
              </label>
              <label><span>AppID（不可改）</span><input :value="a.appid" readonly disabled class="is-readonly" /></label>
              <label>
                <span>状态</span>
                <select v-model="edit.status">
                  <option value="1">正常</option>
                  <option value="0">冻结</option>
                </select>
              </label>
            </div>
            <div class="apps-edit-actions">
              <button type="submit" class="btn-app-save">保存</button>
              <button type="button" class="btn-app-cancel" @click="editing = null">取消</button>
            </div>
          </form>
        </div>
        <div class="app-actions">
          <button v-if="ipaEnabled" type="button" class="btn-app-ipa" @click="openIpa(a)">打 IPA</button>
          <button type="button" class="btn-app-edit" @click="startEdit(a)">编辑</button>
        </div>
      </div>
    </div>
    <IpaModal
      :visible="ipa.open"
      :app="ipa.app"
      :generate-enabled="!!meta.ipaGenerateEnabled"
      :inject-enabled="!!meta.ipaInjectEnabled"
      @close="ipa.open = false"
    />
  </div>
</template>
<script>
import { api } from "../api/http";
import { toast } from "../utils/ui";
import IpaModal from "../components/IpaModal.vue";
export default {
  components: { IpaModal },
  data() {
    return {
      items: [],
      meta: {},
      hierarchy: { agents: [], channels: [], sales: [] },
      filters: { name: "", appid: "", agent_id: "", channel_id: "", sales_id: "" },
      showCreate: false,
      draft: { appname: "", appid: "", status: "1", agentId: "", channelId: "", salesId: "" },
      editing: null,
      edit: {},
      ipa: { open: false, app: {} },
    };
  },
  computed: {
    agents() { return this.hierarchy.agents || []; },
    ipaEnabled() { return !!(this.meta.ipaGenerateEnabled || this.meta.ipaInjectEnabled); },
    ipaHint() {
      if (this.meta.ipaGenerateEnabled && this.meta.ipaInjectEnabled) return "当前可 GENERATE / INJECT。";
      if (this.meta.ipaInjectEnabled) return "当前仅开放 INJECT（注入源 IPA）；GENERATE 需 macOS + Xcode。";
      if (this.meta.ipaGenerateEnabled) return "当前仅开放 GENERATE。";
      return "当前未开放 IPA 封装。";
    },
  },
  watch: {
    "filters.agent_id"() { this.filters.channel_id = ""; this.filters.sales_id = ""; },
    "filters.channel_id"() { this.filters.sales_id = ""; },
    "draft.agentId"() { this.draft.channelId = ""; this.draft.salesId = ""; },
    "draft.channelId"() { this.draft.salesId = ""; },
    "edit.agentId"() { if (this.edit) this.edit.channelId = this.edit.channelId; },
  },
  mounted() { this.reload(); },
  methods: {
    uuid() {
      return crypto.randomUUID ? crypto.randomUUID() : "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
        const r = Math.random() * 16 | 0;
        return (c === "x" ? r : (r & 0x3 | 0x8)).toString(16);
      });
    },
    channelsOf(agentId) {
      if (!agentId) return [];
      return (this.hierarchy.channels || []).filter((c) => String(c.parentId) === String(agentId));
    },
    salesOf(channelId) {
      if (!channelId) return [];
      return (this.hierarchy.sales || []).filter((s) => String(s.parentId) === String(channelId));
    },
    ownerId(agentId, channelId, salesId) {
      return Number(salesId || channelId || agentId || 0);
    },
    async reload() {
      try {
        const body = await api.apps(this.filters);
        const d = body.data || {};
        this.items = d.items || [];
        this.hierarchy = d.hierarchy || { agents: [], channels: [], sales: [] };
        this.meta = d;
        if (!this.draft.appid) this.draft.appid = d.defaultAppid || this.uuid();
      } catch (e) { toast(e.message, "err"); }
    },
    search() { this.reload(); },
    reset() {
      this.filters = { name: "", appid: "", agent_id: "", channel_id: "", sales_id: "" };
      this.reload();
    },
    async create() {
      try {
        await api.appsCreate({
          appname: this.draft.appname,
          userid: this.ownerId(this.draft.agentId, this.draft.channelId, this.draft.salesId),
          appid: this.draft.appid,
          status: this.draft.status,
        });
        toast("已添加");
        this.draft = { appname: "", appid: this.uuid(), status: "1", agentId: "", channelId: "", salesId: "" };
        this.showCreate = false;
        this.reload();
      } catch (e) { toast(e.message, "err"); }
    },
    openIpa(a) {
      this.ipa = { open: true, app: a };
    },
    startEdit(a) {
      const p = a.ownerPath || {};
      this.editing = a.id;
      this.edit = {
        appname: a.appname,
        status: String(a.status === 1 ? 1 : 0),
        agentId: p.agentId ? String(p.agentId) : "",
        channelId: p.channelId ? String(p.channelId) : "",
        salesId: p.salesId ? String(p.salesId) : "",
      };
    },
    async save(a) {
      try {
        await api.appsUpdate(a.id, {
          appname: this.edit.appname,
          userid: this.ownerId(this.edit.agentId, this.edit.channelId, this.edit.salesId),
          status: this.edit.status,
        });
        toast("已保存");
        this.editing = null;
        this.reload();
      } catch (e) { toast(e.message, "err"); }
    },
  },
};
</script>
