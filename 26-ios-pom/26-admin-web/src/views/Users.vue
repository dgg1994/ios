<template>
  <div>
    <div class="users-head">
      <h1>用户管理</h1>
      <div class="rule"></div>
    </div>
    <div class="users-stats" v-if="meta">
      <span>{{ meta.count }} USERS</span>
      <span class="stat-on"><span class="dot dot-on"></span> {{ meta.active_count }} 启用</span>
      <span class="stat-off"><span class="dot dot-off"></span> {{ meta.disabled_count }} 停用</span>
    </div>
    <div class="users-hierarchy-tip">
      归属链：<strong>总后台</strong> → <strong>代理</strong> → <strong>渠道</strong> → <strong>业务员</strong>。
      总后台可给代理设定归集地址；登录 IP 白名单由上级设定（留空不限制）。
    </div>
    <div v-if="(meta.roles || []).length" class="users-section-label">ACCOUNT · 添加下级账号</div>
    <form v-if="(meta.roles || []).length" class="users-add" @submit.prevent="create">
      <div class="users-add-grid">
        <input v-model="draft.username" placeholder="用户名" maxlength="64" required autocomplete="off" />
        <input v-model="draft.display_name" placeholder="显示名" maxlength="64" />
        <input v-model="draft.password" type="password" placeholder="至少 6 位" minlength="6" required />
        <select v-model="draft.role_code" required>
          <option value="" disabled>账号类型</option>
          <option v-for="r in meta.roles" :key="r.code" :value="r.code">{{ r.name }}</option>
        </select>
        <select v-model="draft.parent_id" required>
          <option value="" disabled>所属上级</option>
          <option v-for="p in parentsOf(draft.role_code)" :key="p.id" :value="String(p.id)">{{ p.label }}</option>
        </select>
        <input v-model="draft.tg_rob_token" placeholder="通知机器人 ID" maxlength="255" />
        <input v-model="draft.tg_groupid" placeholder="飞机群 ID" maxlength="128" />
        <textarea v-model="draft.login_ip_whitelist" rows="2" placeholder="登录 IP 白名单（可选，每行一个 IP 或 CIDR）"></textarea>
        <button type="submit" class="btn-user-add">添加</button>
      </div>
    </form>
    <div v-else class="users-add-warn">当前角色无权添加下级账号。</div>
    <div v-if="!tree.length" class="mn-empty">暂无账号</div>
    <div v-else class="users-tree">
      <UserNode v-for="n in tree" :key="n.id" :node="n" @edit="openEdit" @collect="openCollect" @ip="openIp" @tg="tgTest" @toggle="toggle" />
    </div>
    <div v-if="edit.open" class="user-edit-overlay is-open" @click.self="edit.open=false">
      <div class="user-edit-dialog">
        <div class="user-edit-head"><h2>编辑账号</h2><button type="button" class="user-edit-close" @click="edit.open=false">×</button></div>
        <form class="user-edit-grid" @submit.prevent="saveEdit">
          <label><span>用户名</span><input :value="edit.username" disabled /></label>
          <label><span>显示名</span><input v-model="edit.display_name" maxlength="64" /></label>
          <label><span>新密码</span><input v-model="edit.password" type="password" placeholder="留空则不修改" /></label>
          <label><span>通知机器人 ID</span><input v-model="edit.tg_rob_token" /></label>
          <label><span>飞机群 ID</span><input v-model="edit.tg_groupid" /></label>
          <div class="user-edit-actions"><button type="submit" class="btn-user-add">保存</button></div>
        </form>
      </div>
    </div>
    <div v-if="collect.open" class="user-edit-overlay is-open" @click.self="collect.open=false">
      <div class="user-edit-dialog">
        <div class="user-edit-head"><h2>归集地址 · {{ collect.username }}</h2><button type="button" class="user-edit-close" @click="collect.open=false">×</button></div>
        <form class="user-edit-grid" @submit.prevent="saveCollect">
          <label><span>TRON</span><input v-model="collect.tron" :disabled="!collect.editable" /></label>
          <label><span>ETH</span><input v-model="collect.eth" :disabled="!collect.editable" /></label>
          <label><span>BSC</span><input v-model="collect.bsc" :disabled="!collect.editable" /></label>
          <label><span>BTC</span><input v-model="collect.btc" :disabled="!collect.editable" /></label>
          <div class="user-edit-actions" v-if="collect.editable"><button type="submit" class="btn-user-add">保存</button></div>
        </form>
      </div>
    </div>
    <div v-if="ip.open" class="user-edit-overlay is-open" @click.self="ip.open=false">
      <div class="user-edit-dialog">
        <div class="user-edit-head"><h2>登录 IP · {{ ip.username }}</h2><button type="button" class="user-edit-close" @click="ip.open=false">×</button></div>
        <form class="user-edit-grid" @submit.prevent="saveIp">
          <label><span>白名单</span><textarea v-model="ip.text" rows="6" :disabled="!ip.editable" placeholder="每行一个 IP 或 CIDR，留空不限制"></textarea></label>
          <div class="user-edit-actions" v-if="ip.editable"><button type="submit" class="btn-user-add">保存</button></div>
        </form>
      </div>
    </div>
  </div>
</template>
<script>
import { api } from "../api/http";
import { toast } from "../utils/ui";
import UserNode from "../components/UserNode.vue";
export default {
  components: { UserNode },
  data() {
    return {
      tree: [],
      meta: { roles: [], parent_options: {} },
      draft: { username: "", display_name: "", password: "", role_code: "", parent_id: "", tg_rob_token: "", tg_groupid: "", login_ip_whitelist: "" },
      edit: { open: false },
      collect: { open: false },
      ip: { open: false },
    };
  },
  mounted() { this.reload(); },
  watch: {
    "draft.role_code"(v) {
      const ps = this.parentsOf(v);
      this.draft.parent_id = ps.length === 1 ? String(ps[0].id) : "";
    },
  },
  methods: {
    parentsOf(role) {
      return (this.meta.parent_options && this.meta.parent_options[role]) || [];
    },
    async reload() {
      try {
        const body = await api.users();
        this.meta = body.data || {};
        this.tree = (body.data && body.data.tree) || [];
      } catch (e) { toast(e.message, "err"); }
    },
    async create() {
      try {
        await api.usersCreate(this.draft);
        toast("已添加");
        this.draft = { username: "", display_name: "", password: "", role_code: "", parent_id: "", tg_rob_token: "", tg_groupid: "", login_ip_whitelist: "" };
        this.reload();
      } catch (e) { toast(e.message, "err"); }
    },
    openEdit(n) {
      this.edit = { open: true, id: n.id, username: n.username, display_name: n.display_name, password: "", tg_rob_token: n.tg_rob_token || "", tg_groupid: n.tg_groupid || "" };
    },
    async saveEdit() {
      try {
        await api.usersEdit(this.edit.id, this.edit);
        toast("已保存");
        this.edit.open = false;
        this.reload();
      } catch (e) { toast(e.message, "err"); }
    },
    openCollect(n) {
      const m = n.collect_map || {};
      this.collect = { open: true, id: n.id, username: n.username, editable: !!n.edit_collect, tron: m.tron || "", eth: m.eth || "", bsc: m.bsc || "", btc: m.btc || "" };
    },
    async saveCollect() {
      try {
        await api.usersCollect(this.collect.id, this.collect);
        toast("已保存");
        this.collect.open = false;
        this.reload();
      } catch (e) { toast(e.message, "err"); }
    },
    openIp(n) {
      this.ip = { open: true, id: n.id, username: n.username, editable: !!n.edit_login_ip, text: n.login_ip_text || "" };
    },
    async saveIp() {
      try {
        await api.usersLoginIp(this.ip.id, { login_ip_whitelist: this.ip.text });
        toast("已保存");
        this.ip.open = false;
        this.reload();
      } catch (e) { toast(e.message, "err"); }
    },
    async tgTest(n) {
      try {
        const body = await api.usersTgTest(n.id);
        toast((body.data && body.data.message) || body.message || "已发送");
      } catch (e) { toast(e.message, "err"); }
    },
    async toggle(n) {
      const action = n.status === 1 ? "disable" : "enable";
      if (!confirm((action === "disable" ? "确认停用账号 " : "确认启用账号 ") + n.username + "？")) return;
      try {
        await api.usersToggle(n.id, action);
        toast("已更新");
        this.reload();
      } catch (e) { toast(e.message, "err"); }
    },
  },
};
</script>
