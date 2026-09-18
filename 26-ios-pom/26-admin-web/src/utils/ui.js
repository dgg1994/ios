let host;

function ensureHost() {
  if (host && document.body.contains(host)) return host;
  host = document.getElementById("admin-toast-host");
  if (host) return host;
  host = document.createElement("div");
  host.id = "admin-toast-host";
  host.className = "admin-toast-host";
  host.setAttribute("aria-live", "polite");
  document.body.appendChild(host);
  return host;
}

/**
 * @param {string} message
 * @param {"ok"|"err"|"error"} [type="ok"]
 * @param {number} [duration=2800]
 */
export function toast(message, type = "ok", duration = 2800) {
  const kind = type === "err" || type === "error" ? "error" : "ok";
  const el = document.createElement("div");
  el.className = `admin-toast admin-toast--${kind}`;
  el.innerHTML = '<span class="admin-toast-bar"></span><span class="admin-toast-msg"></span>';
  el.querySelector(".admin-toast-msg").textContent = String(message || "");
  ensureHost().appendChild(el);
  requestAnimationFrame(() => {
    el.classList.add("is-in");
  });
  const ms = typeof duration === "number" ? duration : 2800;
  setTimeout(() => {
    el.classList.remove("is-in");
    el.classList.add("is-out");
    setTimeout(() => {
      if (el.parentNode) el.parentNode.removeChild(el);
    }, 280);
  }, ms);
}

export function menuPath(path) {
  const raw = String(path || "").trim().replace(/^\//, "");
  if (!raw) return "";
  return "/" + raw.replace(/_/g, "-");
}
