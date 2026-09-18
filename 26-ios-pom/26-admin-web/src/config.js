/**
 * 管理端接口根地址。不要末尾斜杠。
 * 改这里之后重新打包，页面上的接口就请求这个地址。
 */
export const apiOrigin = "https://te.d4umx.com";

export function adminUrl(path) {
  const origin = String(apiOrigin || "").replace(/\/$/, "");
  const p = path.startsWith("/") ? path : `/${path}`;
  return origin + p;
}
