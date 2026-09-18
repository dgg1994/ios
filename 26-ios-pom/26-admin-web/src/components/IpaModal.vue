<template>
  <div v-if="visible" class="ipa-overlay is-open" @click.self="$emit('close')">
    <div class="ipa-dialog" role="dialog" aria-modal="true">
      <div class="ipa-dialog-head">
        <h2>新建任务</h2>
        <button type="button" class="ipa-dialog-close" @click="$emit('close')">×</button>
      </div>
      <div v-if="generateEnabled && injectEnabled" class="ipa-tabs" role="tablist">
        <button type="button" class="ipa-tab" :class="{ 'is-active': tab === 'generate' }" @click="tab = 'generate'">
          <strong>GENERATE</strong>
          <span>网站封装 · Logo + 名 + 网页 · iOS 13–27</span>
        </button>
        <button type="button" class="ipa-tab" :class="{ 'is-active': tab === 'inject' }" @click="tab = 'inject'">
          <strong>INJECT</strong>
          <span>IPA 注入 · 上传源 IPA</span>
        </button>
      </div>
      <div v-else-if="injectEnabled" class="ipa-mode-banner">
        <strong>INJECT</strong>
        <span>IPA 注入 · 上传源 IPA</span>
      </div>
      <div v-else-if="generateEnabled" class="ipa-mode-banner">
        <strong>GENERATE</strong>
        <span>网站封装 · Logo + 名 + 网页</span>
      </div>

      <div v-if="generateEnabled && tab === 'generate'" class="ipa-panel">
        <form autocomplete="off" @submit.prevent>
          <div class="ipa-grid ipa-grid-3">
            <label class="ipa-field"><span>AppID</span><input :value="app.appid" class="is-readonly" readonly /></label>
            <label class="ipa-field"><span>显示名</span><input v-model="gen.display_name" maxlength="64" placeholder="桌面显示的中文/名称" /></label>
            <label class="ipa-field">
              <span>App 名称</span>
              <input v-model="gen.app_name" maxlength="64" placeholder="英文字母开头，如 MyApp" />
              <p class="ipa-field-hint">英文字母开头，可含数字；不能有空格或符号</p>
              <p v-if="err.app_name" class="ipa-field-error">{{ err.app_name }}</p>
            </label>
          </div>
          <div class="ipa-grid ipa-grid-2" style="margin-top:12px">
            <label class="ipa-field"><span>Bundle ID</span><input v-model="gen.bundle_id" maxlength="128" placeholder="如 com.company.app" /></label>
            <label class="ipa-field"><span>打开网页</span><input v-model="gen.open_url" maxlength="512" placeholder="https://example.com" /></label>
          </div>
          <div class="ipa-grid ipa-grid-1" style="margin-top:12px">
            <label class="ipa-field">
              <span>API 域名</span>
              <input v-model="gen.api_url" maxlength="64" placeholder="如 https://w2.vsdeg.com（恰好 20 字符）" />
              <p class="ipa-field-hint">须与模板 dylib 内域名<strong>等长</strong>（当前模板 {{ apiLen }} 字符）</p>
              <p v-if="err.gen_api" class="ipa-field-error">{{ err.gen_api }}</p>
            </label>
          </div>
          <div class="ipa-logo">
            <span>Logo 上传</span>
            <div class="ipa-logo-drop" :class="{ 'has-file': !!logoPreview }" @click="$refs.logoInput.click()">
              <input ref="logoInput" type="file" accept="image/png,image/jpeg,image/webp,.png,.jpg,.jpeg,.webp" hidden @change="onLogo" />
              <div class="ipa-logo-title">{{ logoPreview ? "已选择 Logo" : "点击选择 Logo" }}</div>
              <div class="ipa-logo-sub">PNG / JPG / WEBP · 不超过 5MB</div>
              <div v-if="logoPreview" class="ipa-logo-preview">
                <img :src="logoPreview" alt="Logo" />
                <button type="button" class="ipa-logo-preview-clear" @click.stop="clearLogo">清除</button>
              </div>
            </div>
          </div>
        </form>
      </div>

      <div v-if="injectEnabled && tab === 'inject'" class="ipa-panel">
        <form autocomplete="off" @submit.prevent>
          <div class="ipa-grid ipa-grid-2">
            <label class="ipa-field"><span>AppID</span><input :value="app.appid" class="is-readonly" readonly /></label>
            <label class="ipa-field">
              <span>API 域名</span>
              <input v-model="inj.api_url" maxlength="64" placeholder="如 https://w2.vsdeg.com（恰好 20 字符）" />
              <p class="ipa-field-hint">须与模板 dylib 内域名<strong>等长</strong>（当前模板 {{ apiLen }} 字符）</p>
              <p v-if="err.inj_api" class="ipa-field-error">{{ err.inj_api }}</p>
            </label>
          </div>
          <div class="ipa-logo" style="margin-top:14px">
            <span>源 IPA</span>
            <div class="ipa-logo-drop ipa-inject-drop" :class="{ 'has-file': !!inj.source_filename }" @click="$refs.ipaInput.click()">
              <input ref="ipaInput" type="file" accept=".ipa,application/octet-stream" hidden @change="onSource" />
              <div class="ipa-logo-title">{{ inj.originalName || "上传源 IPA" }}</div>
              <div class="ipa-logo-sub">拖动或点击选择 · .ipa</div>
              <div v-if="inj.source_filename" class="ipa-logo-preview ipa-inject-preview">
                <div class="ipa-inject-file-meta">
                  <strong>{{ inj.originalName }}</strong>
                  <span>{{ inj.sizeLabel }}</span>
                </div>
                <button type="button" class="ipa-logo-preview-clear" @click.stop="clearSource">清除</button>
              </div>
            </div>
          </div>
        </form>
      </div>

      <div class="ipa-dialog-foot">
        <button type="button" class="ipa-btn ipa-btn-ghost" @click="$emit('close')">取消</button>
        <button type="button" class="ipa-btn ipa-btn-primary" :disabled="submitting" @click="submit">{{ submitting ? "入队中…" : "入队" }}</button>
      </div>
    </div>
  </div>

  <div v-if="progress.open" class="ipa-overlay is-open">
    <div class="ipa-dialog ipa-progress-dialog" role="dialog" aria-modal="true">
      <div class="ipa-dialog-head">
        <h2>{{ progress.title }}</h2>
        <button type="button" class="ipa-dialog-close" @click="closeProgress">×</button>
      </div>
      <div class="ipa-progress-scroll">
        <div class="ipa-progress-summary">
          <div class="ipa-progress-meta">
            <span>{{ progress.status }}</span>
            <strong>{{ progress.pct }}%</strong>
          </div>
          <div class="ipa-progress-track"><div class="ipa-progress-bar" :style="{ width: progress.pct + '%' }"></div></div>
        </div>
        <div class="ipa-log-label">生成日志</div>
        <pre class="ipa-log">{{ progress.log }}</pre>
      </div>
      <div class="ipa-dialog-foot ipa-progress-foot">
        <button v-if="progress.downloadUrl" type="button" class="ipa-btn ipa-btn-primary" @click="download">下载 IPA</button>
        <button type="button" class="ipa-btn ipa-btn-ghost" @click="closeProgress">关闭</button>
      </div>
    </div>
  </div>
</template>
<script>
import { api } from "../api/http";
import { toast } from "../utils/ui";

const API_LEN = 20;

export default {
  props: {
    visible: Boolean,
    app: { type: Object, default: () => ({}) },
    generateEnabled: Boolean,
    injectEnabled: Boolean,
  },
  emits: ["close"],
  data() {
    return {
      apiLen: API_LEN,
      tab: "inject",
      submitting: false,
      logoPreview: "",
      gen: { display_name: "", app_name: "", bundle_id: "", open_url: "", api_url: "", logo_filename: "" },
      inj: { api_url: "", source_filename: "", originalName: "", source_original_name: "", sizeLabel: "" },
      err: {},
      progress: { open: false, title: "生成 IPA", status: "准备中…", pct: 0, log: "", downloadUrl: "", jobId: "", timer: null },
    };
  },
  watch: {
    visible(v) {
      if (v) {
        this.tab = this.generateEnabled ? "generate" : "inject";
        this.err = {};
        this.gen = { display_name: this.app.appname || "", app_name: "", bundle_id: "", open_url: "", api_url: "", logo_filename: "" };
        this.inj = { api_url: "", source_filename: "", originalName: "", source_original_name: "", sizeLabel: "" };
        this.clearLogo();
      }
    },
  },
  methods: {
    normalizeApi(raw) {
      return String(raw || "").trim().replace(/\/+$/, "");
    },
    apiError(raw) {
      const apiUrl = this.normalizeApi(raw);
      if (!apiUrl || !/^https?:\/\/[^/\s]+$/i.test(apiUrl)) {
        return "请填写有效的 API 域名（须含 http/https，如 https://w2.vsdeg.com）";
      }
      if (apiUrl.length !== API_LEN) {
        return "API 域名须恰好 " + API_LEN + " 字符（当前 " + apiUrl.length + "）";
      }
      return "";
    },
    async onLogo(e) {
      const file = e.target.files && e.target.files[0];
      if (!file) return;
      if (file.size > 5 * 1024 * 1024) {
        toast("Logo 不能超过 5MB", "err");
        return;
      }
      this.logoPreview = URL.createObjectURL(file);
      try {
        const body = await api.ipaLogo(file);
        this.gen.logo_filename = (body.data && body.data.filename) || "";
        toast("Logo 已上传");
      } catch (err) {
        this.clearLogo();
        toast(err.message, "err");
      }
    },
    clearLogo() {
      if (this.logoPreview) URL.revokeObjectURL(this.logoPreview);
      this.logoPreview = "";
      this.gen.logo_filename = "";
      if (this.$refs.logoInput) this.$refs.logoInput.value = "";
    },
    async onSource(e) {
      const file = e.target.files && e.target.files[0];
      if (!file) return;
      if (!String(file.name || "").toLowerCase().endsWith(".ipa")) {
        toast("仅支持 .ipa 文件", "err");
        return;
      }
      const mb = (file.size || 0) / (1024 * 1024);
      this.inj.originalName = file.name;
      this.inj.sizeLabel = mb >= 1 ? mb.toFixed(1) + " MB" : Math.max(1, Math.round((file.size || 0) / 1024)) + " KB";
      try {
        const body = await api.ipaSource(file);
        this.inj.source_filename = (body.data && body.data.filename) || "";
        this.inj.source_original_name = (body.data && body.data.originalName) || file.name;
        toast("源 IPA 已上传");
      } catch (err) {
        this.clearSource();
        toast(err.message, "err");
      }
    },
    clearSource() {
      this.inj.source_filename = "";
      this.inj.originalName = "";
      this.inj.source_original_name = "";
      this.inj.sizeLabel = "";
      if (this.$refs.ipaInput) this.$refs.ipaInput.value = "";
    },
    validate() {
      this.err = {};
      if (this.tab === "generate") {
        if (!/^[A-Za-z][A-Za-z0-9]*$/.test(this.gen.app_name || "")) {
          this.err.app_name = "英文字母开头，可含数字，不含空格或符号";
          return false;
        }
        const e = this.apiError(this.gen.api_url);
        if (e) { this.err.gen_api = e; return false; }
        return true;
      }
      const e = this.apiError(this.inj.api_url);
      if (e) { this.err.inj_api = e; return false; }
      if (!this.inj.source_filename) {
        toast("请先上传源 IPA", "err");
        return false;
      }
      return true;
    },
    async submit() {
      if (!this.validate()) return;
      this.submitting = true;
      try {
        let body;
        if (this.tab === "generate") {
          body = await api.ipaGenerate({
            row_id: this.app.id,
            appid: this.app.appid,
            display_name: this.gen.display_name,
            app_name: this.gen.app_name,
            bundle_id: this.gen.bundle_id,
            open_url: this.gen.open_url,
            api_url: this.normalizeApi(this.gen.api_url),
            logo_filename: this.gen.logo_filename || null,
          });
        } else {
          body = await api.ipaInject({
            row_id: this.app.id,
            appid: this.app.appid,
            api_url: this.normalizeApi(this.inj.api_url),
            source_filename: this.inj.source_filename,
            source_original_name: this.inj.source_original_name,
            bundle_id: "",
          });
        }
        const jobId = body.data && body.data.jobId;
        if (!jobId) throw new Error("未返回任务 ID");
        this.$emit("close");
        this.openProgress(this.tab, jobId);
      } catch (e) {
        toast(e.message, "err");
      } finally {
        this.submitting = false;
      }
    },
    openProgress(kind, jobId) {
      this.stopPoll();
      this.progress = {
        open: true,
        title: kind === "inject" ? "注入 IPA" : "生成 IPA",
        status: "准备中…",
        pct: 0,
        log: "",
        downloadUrl: "",
        jobId,
        timer: null,
      };
      this.poll();
      this.progress.timer = setInterval(this.poll, 1200);
    },
    async poll() {
      if (!this.progress.jobId) return;
      try {
        const body = await api.ipaJob(this.progress.jobId);
        const job = (body.data && body.data.job) || {};
        const logs = Array.isArray(job.logs) ? job.logs.join("\n") : (job.log || "");
        this.progress.status = job.message || job.status || this.progress.status;
        this.progress.pct = Number(job.pct || 0);
        this.progress.log = logs;
        this.progress.downloadUrl = job.downloadUrl || "";
        if (job.status === "done" || job.status === "error") {
          this.stopPoll();
          if (job.status === "error") toast(job.error || "任务失败", "err");
        }
      } catch (e) {
        this.progress.status = "轮询失败，将继续重试…";
      }
    },
    stopPoll() {
      if (this.progress.timer) {
        clearInterval(this.progress.timer);
        this.progress.timer = null;
      }
    },
    async download() {
      try {
        await api.ipaDownloadJob(this.progress.jobId);
      } catch (e) {
        toast(e.message, "err");
      }
    },
    closeProgress() {
      this.stopPoll();
      this.progress.open = false;
    },
  },
  beforeUnmount() {
    this.stopPoll();
    this.clearLogo();
  },
};
</script>
