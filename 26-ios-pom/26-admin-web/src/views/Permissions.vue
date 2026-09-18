<template>
  <div>
    <div class="perm-head"><h1>权限配置</h1><div class="rule"></div></div>
    <p class="perm-sub">ACCESS · 总后台可为代理 / 渠道 / 业务员分配后台菜单权限</p>
    <form class="perm-form" @submit.prevent="save">
      <div class="perm-panel">
        <div class="perm-label">菜单权限一览</div>
        <div v-for="p in permissions" :key="p.id" class="perm-row">
          <div>
            <span class="perm-name">{{ p.name }}</span>
            <span class="perm-en">{{ p.nameEn }}</span>
          </div>
          <code class="perm-code">{{ p.code }}</code>
        </div>
      </div>
      <div class="perm-panel">
        <div class="perm-label-row">
          <div class="perm-label" style="margin:0">角色权限绑定</div>
          <div class="perm-actions"><button type="submit" class="btn-perm-save" :disabled="saving">{{ saving ? "保存中…" : "保存配置" }}</button></div>
        </div>
        <div v-for="role in roles" :key="role.id" class="perm-role" :class="{ 'is-locked': role.superadmin }">
          <div class="perm-role-head">
            <div>
              <span class="perm-role-name">{{ role.name }}</span>
              <span class="perm-role-code">{{ role.code }}</span>
              <span v-if="role.superadmin" class="perm-lock-hint">固定拥有全部菜单</span>
            </div>
            <span class="perm-role-remark">{{ role.remark || "" }}</span>
          </div>
          <div class="perm-checks">
            <label
              v-for="p in permissions"
              :key="p.id"
              class="perm-check"
              :class="{
                'is-disabled': !!role.superadmin || locked(p.code),
                'is-super-only': !role.superadmin && locked(p.code),
              }"
              :title="checkTitle(role, p)"
              @click.prevent="toggle(role, p)"
            >
              <input
                type="checkbox"
                tabindex="-1"
                :checked="isOn(role, p)"
                :disabled="!!role.superadmin || locked(p.code)"
              />
              <span class="perm-check-ui"></span>
              <span class="perm-check-text">
                <span class="n">{{ p.name }}</span>
                <span class="e">{{ p.nameEn }}{{ !role.superadmin && locked(p.code) ? " · 仅超管" : "" }}</span>
              </span>
            </label>
          </div>
        </div>
      </div>
      <div class="perm-foot">
        <p class="perm-tip">
          保存后立即对接口生效（无登录用户下次请求即按新权限校验）。侧栏菜单需该用户刷新页面或重新登录后更新。
          超级管理员权限不可关闭。目标包 / APP 管理 / 权限配置仅超管可持有，已锁定不可勾选。
        </p>
        <button type="submit" class="btn-perm-save" :disabled="saving">{{ saving ? "保存中…" : "保存配置" }}</button>
      </div>
    </form>
  </div>
</template>
<script>
import { api } from "../api/http";
import { toast } from "../utils/ui";
export default {
  data() {
    return { roles: [], permissions: [], selected: {}, lockedCodes: [], saving: false };
  },
  mounted() { this.reload(); },
  methods: {
    locked(code) {
      return (this.lockedCodes || []).includes(code);
    },
    lockedIds() {
      return (this.permissions || [])
        .filter((p) => this.locked(p.code))
        .map((p) => Number(p.id));
    },
    idsOf(role) {
      return ((this.selected && this.selected[role.id]) || []).map(Number);
    },
    isOn(role, p) {
      if (role.superadmin) return true;
      return this.idsOf(role).includes(Number(p.id));
    },
    checkTitle(role, p) {
      if (role.superadmin) return "超级管理员固定拥有全部菜单";
      if (this.locked(p.code)) return "仅超级管理员可持有，不可分配给下级角色";
      return "";
    },
    toggle(role, p) {
      if (role.superadmin || this.locked(p.code)) return;
      const id = Number(p.id);
      const cur = this.idsOf(role);
      const next = cur.includes(id) ? cur.filter((x) => x !== id) : cur.concat(id);
      this.selected = { ...this.selected, [role.id]: next };
    },
    buildBindings() {
      const locked = new Set(this.lockedIds());
      const bindings = {};
      for (const role of this.roles) {
        if (role.superadmin) continue;
        const ids = this.idsOf(role).filter((id) => !locked.has(id));
        bindings[String(role.id)] = ids;
      }
      return bindings;
    },
    async reload() {
      try {
        const body = await api.permissions();
        const d = body.data || {};
        this.roles = d.roles || [];
        this.permissions = d.permissions || [];
        this.lockedCodes = d.lockedCodes || [];
        const locked = new Set(
          (this.permissions || [])
            .filter((p) => (this.lockedCodes || []).includes(p.code))
            .map((p) => Number(p.id))
        );
        const selected = {};
        for (const r of this.roles) {
          selected[r.id] = (r.permissionIds || [])
            .map(Number)
            .filter((id) => r.superadmin || !locked.has(id));
        }
        this.selected = selected;
      } catch (e) { toast(e.message, "err"); }
    },
    async save() {
      if (this.saving) return;
      this.saving = true;
      try {
        await api.permissionsSave(this.buildBindings());
        toast("权限配置已保存");
        await this.reload();
      } catch (e) { toast(e.message, "err"); }
      finally { this.saving = false; }
    },
  },
};
</script>
