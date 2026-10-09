import { createRequest as a } from "@yuku123/z-frontend-common";
const c = a({ baseURL: "", tokenKey: "zcache_token" });
function s(e) {
  e && e.apiBase !== void 0 && (c.defaults.baseURL = e.apiBase);
}
const n = {
  instance: () => c.get("/cache/__instance"),
  overview: () => c.get("/cache/overview"),
  keys: (e) => c.get("/cache/keys", { params: e }),
  key: (e) => c.get("/cache/key", { params: e }),
  info: () => c.get("/cache/info"),
  hotkeys: (e) => c.get("/cache/hotkeys", { params: e })
};
export {
  s as a,
  n as c
};
