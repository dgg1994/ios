import { reactive } from "vue";

export const pageLoad = reactive({ visible: false });

let generation = 0;
let pending = 0;
let idleTimer = null;

/** 进入页面后显示加载，直到这次导航触发的接口全部返回。 */
export function armPageLoad() {
  generation += 1;
  pending = 0;
  pageLoad.visible = true;
  clearTimeout(idleTimer);
  const mine = generation;
  idleTimer = setTimeout(() => {
    if (mine === generation && pending === 0) {
      pageLoad.visible = false;
    }
  }, 1500);
}

export function cancelPageLoad() {
  generation += 1;
  pending = 0;
  clearTimeout(idleTimer);
  pageLoad.visible = false;
}

export function trackRequestStart() {
  if (!pageLoad.visible) {
    return 0;
  }
  clearTimeout(idleTimer);
  pending += 1;
  return generation;
}

export function trackRequestEnd(token) {
  if (!token || token !== generation) {
    return;
  }
  pending -= 1;
  if (pending <= 0) {
    pending = 0;
    pageLoad.visible = false;
  }
}
