<template>
  <div class="user-tree-node" :class="['depth-' + (node.depth || 0), { 'is-collapsed': collapsed }]">
    <div
      class="user-row"
      :class="['role-' + (node.role_code || 'none'), { 'has-children': hasKids }]"
      :style="{ '--depth': node.depth || 0 }"
      @click="onRowClick"
    >
      <button
        v-if="hasKids"
        type="button"
        class="tree-toggle"
        :class="{ 'is-open': !collapsed }"
        :aria-expanded="!collapsed"
        title="折叠/展开"
        @click.stop="collapsed = !collapsed"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 6 15 12 9 18"></polyline></svg>
      </button>
      <span v-else class="tree-toggle is-leaf" aria-hidden="true"></span>
      <span class="user-name">{{ node.username }}</span>
      <span class="user-display-inline">{{ node.display_name }}</span>
      <span class="badge-user" :class="{ 'is-off': node.status !== 1 }"><span class="dot"></span>{{ node.status === 1 ? "启用" : "停用" }}</span>
      <span class="badge-role">{{ node.role_name }}</span>
      <span class="badge-sub">{{ node.descendant_count ? node.descendant_count + " 下级" : "" }}</span>
      <span class="user-parent">{{ node.is_superadmin ? "总后台" : (node.parent_username || "—") }}</span>
      <span class="user-tg">{{ node.tg_rob_token || "—" }}</span>
      <span class="user-tg-group">{{ node.tg_groupid || "—" }}</span>
      <span class="user-collect-col">
        <span v-if="node.role_code === 'agent'" class="badge-collect" :class="node.collect_set ? 'is-on' : 'is-off'">{{ node.collect_set ? "已设置" : "未设置" }}</span>
        <span v-else class="badge-collect is-empty">—</span>
      </span>
      <span class="user-ip-col">
        <span v-if="node.is_superadmin" class="badge-collect is-on">环境变量</span>
        <span v-else class="badge-collect" :class="node.login_ip_set ? 'is-on' : 'is-off'">{{ node.login_ip_set ? "已设置" : "不限制" }}</span>
      </span>
      <div class="user-actions" @click.stop>
        <button type="button" class="btn-user-edit" @click="$emit('edit', node)">编辑</button>
        <button v-if="node.show_collect" type="button" class="btn-user-collect" @click="$emit('collect', node)">归集地址</button>
        <button v-if="node.show_login_ip" type="button" class="btn-user-ip" @click="$emit('ip', node)">登录IP</button>
        <button v-if="node.tg_rob_token && node.tg_groupid" type="button" class="btn-user-tg-test" @click="$emit('tg', node)">测试通知</button>
        <button v-if="node.is_self" type="button" class="btn-user-toggle is-self" disabled>当前</button>
        <button v-else type="button" class="btn-user-toggle" @click="$emit('toggle', node)">{{ node.status === 1 ? "停用" : "启用" }}</button>
      </div>
    </div>
    <div v-if="hasKids" class="user-tree-children">
      <UserNode v-for="c in node.children" :key="c.id" :node="c" @edit="$emit('edit', $event)" @collect="$emit('collect', $event)" @ip="$emit('ip', $event)" @tg="$emit('tg', $event)" @toggle="$emit('toggle', $event)" />
    </div>
  </div>
</template>
<script>
export default {
  name: "UserNode",
  props: { node: { type: Object, required: true } },
  emits: ["edit", "collect", "ip", "tg", "toggle"],
  data() {
    return { collapsed: (this.node.depth || 0) >= 1 && !!(this.node.children && this.node.children.length) };
  },
  computed: {
    hasKids() {
      return !!(this.node.children && this.node.children.length);
    },
  },
  methods: {
    onRowClick() {
      if (this.hasKids) this.collapsed = !this.collapsed;
    },
  },
};
</script>
