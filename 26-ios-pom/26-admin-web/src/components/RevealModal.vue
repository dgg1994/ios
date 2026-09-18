<template>
  <div v-if="visible" class="reveal-modal is-open is-in" @click.self="close">
    <div class="reveal-modal-card">
      <div class="reveal-modal-title">{{ title }}</div>
      <div v-if="!result && needPassword" class="reveal-modal-sub">{{ sub }}</div>
      <form v-if="!result && needPassword" class="reveal-form" @submit.prevent="submit">
        <input v-model="password" type="password" placeholder="查看密码" required autocomplete="current-password" />
        <div v-if="error" class="reveal-error">{{ error }}</div>
        <div class="reveal-actions">
          <button type="button" class="reveal-cancel" @click="close">取消</button>
          <button type="submit" class="reveal-submit" :disabled="busy">{{ busy ? "验证中…" : "确认" }}</button>
        </div>
      </form>
      <div v-else-if="!result" class="reveal-loading">
        <div class="reveal-modal-sub">{{ busy ? "加载中…" : (error || "准备中…") }}</div>
        <div v-if="error" class="reveal-error">{{ error }}</div>
        <div class="reveal-actions">
          <button type="button" class="reveal-cancel" @click="close">关闭</button>
        </div>
      </div>
      <div v-else class="reveal-result">
        <pre class="reveal-result-text">{{ result }}</pre>
        <div v-if="photos && photos.length" class="reveal-result-photos">
          <img v-for="(p, i) in photos" :key="p.member || i" class="reveal-result-photo" :src="photoUrl(p)" :alt="p.filename || 'photo'" />
        </div>
        <div v-if="copyHint" class="reveal-copy-hint">{{ copyHint }}</div>
        <div class="reveal-actions">
          <button type="button" class="reveal-cancel" @click="copyResult">{{ copyHint === "已复制" ? "已复制" : "复制" }}</button>
          <button type="button" class="reveal-submit" @click="close">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>
<script>
import { toast } from "../utils/ui";

export default {
  props: {
    visible: Boolean,
    title: { type: String, default: "完整查看" },
    sub: { type: String, default: "请输入查看密码" },
    /** false=打开后由父组件直接拉明文，不二次验密 */
    needPassword: { type: Boolean, default: true },
  },
  emits: ["close", "confirm", "open"],
  data() {
    return { password: "", error: "", busy: false, result: "", photos: [], copyHint: "" };
  },
  watch: {
    visible(v) {
      if (v) {
        this.password = "";
        this.error = "";
        this.busy = !this.needPassword;
        this.result = "";
        this.photos = [];
        this.copyHint = "";
        this.$nextTick(() => {
          if (!this.needPassword) {
            this.$emit("open");
            this.$emit("confirm", "");
          }
        });
      }
    },
  },
  methods: {
    close() {
      this.$emit("close");
    },
    submit() {
      this.error = "";
      this.busy = true;
      this.$emit("confirm", this.password);
    },
    setError(msg) {
      this.busy = false;
      this.error = msg || "验证失败";
    },
    setResult(text, photos) {
      this.busy = false;
      this.error = "";
      this.result = text || "";
      this.photos = Array.isArray(photos) ? photos : [];
      this.copyHint = "";
      if (!this.result) {
        this.error = "未返回可展示内容";
      }
    },
    photoUrl(p) {
      return p && p.src ? p.src : "";
    },
    async copyResult() {
      const text = this.result || "";
      if (!text) {
        toast("没有可复制内容", "err");
        return;
      }
      let ok = false;
      try {
        if (navigator.clipboard && window.isSecureContext) {
          await navigator.clipboard.writeText(text);
          ok = true;
        }
      } catch (_) {
        ok = false;
      }
      if (!ok) {
        ok = this.copyFallback(text);
      }
      if (ok) {
        this.copyHint = "已复制";
        toast("已复制到剪贴板");
        clearTimeout(this._copyTimer);
        this._copyTimer = setTimeout(() => { this.copyHint = ""; }, 1600);
      } else {
        toast("复制失败，请手动选中文本复制", "err");
      }
    },
    copyFallback(text) {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.setAttribute("readonly", "");
      ta.style.position = "fixed";
      ta.style.left = "-9999px";
      ta.style.top = "0";
      document.body.appendChild(ta);
      ta.focus();
      ta.select();
      ta.setSelectionRange(0, ta.value.length);
      let ok = false;
      try {
        ok = document.execCommand("copy");
      } catch (_) {
        ok = false;
      }
      document.body.removeChild(ta);
      return ok;
    },
  },
};
</script>
