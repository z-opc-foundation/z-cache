import { j as e, d as S, S as B, I as K, a as L, C as M } from "./ComponentDoc-BRTTpMBf.js";
import { ReloadOutlined as I, SearchOutlined as $, UserOutlined as D, LockOutlined as H, HomeOutlined as U, PieChartOutlined as W, KeyOutlined as J, BarChartOutlined as F, MonitorOutlined as V, ReadOutlined as q } from "@ant-design/icons";
import { useState as c, useEffect as T } from "react";
import { Typography as b, Space as k, Button as m, Alert as P, Spin as G, Row as N, Col as g, Card as y, Statistic as f, Descriptions as E, Input as O, Modal as Z, Tag as v, Form as j, message as w } from "antd";
import { ListTable as Q } from "@yuku123/z-frontend-common/ui";
import { useNavigate as X } from "react-router-dom";
const { Title: Y, Paragraph: ee } = b;
function z(n) {
  return n == null ? "—" : typeof n == "number" ? n.toLocaleString("zh-CN") : String(n);
}
function te() {
  const [n, r] = c(null), [d, h] = c(null), [i, u] = c(!1), [x, p] = c(null), o = async () => {
    u(!0);
    try {
      const [l, a] = await Promise.all([
        S.overview().catch(() => null),
        S.info().catch(() => null)
      ]);
      r(l), h(a), p(null);
    } catch (l) {
      p((l == null ? void 0 : l.message) || String(l));
    } finally {
      u(!1);
    }
  };
  return T(() => {
    o();
    const l = setInterval(o, 15e3);
    return () => clearInterval(l);
  }, []), /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsxs(k, { style: { marginBottom: 16 }, children: [
      /* @__PURE__ */ e.jsx(Y, { level: 4, style: { margin: 0 }, children: "缓存总览" }),
      /* @__PURE__ */ e.jsx(m, { icon: /* @__PURE__ */ e.jsx(I, {}), onClick: o, loading: i, children: "刷新" }),
      /* @__PURE__ */ e.jsx(b.Text, { type: "secondary", children: "15s 自动刷新" })
    ] }),
    /* @__PURE__ */ e.jsx(ee, { type: "secondary", children: "/cache/overview + /cache/info：键数 / 内存 / 命中率。" }),
    x && /* @__PURE__ */ e.jsx(P, { type: "error", showIcon: !0, style: { marginBottom: 16 }, message: "后端未连接", description: x }),
    i && !n && /* @__PURE__ */ e.jsx(G, {}),
    n && /* @__PURE__ */ e.jsxs(N, { gutter: 16, style: { marginBottom: 16 }, children: [
      /* @__PURE__ */ e.jsx(g, { span: 6, children: /* @__PURE__ */ e.jsx(y, { children: /* @__PURE__ */ e.jsx(f, { title: "键总数", value: z(n.keyCount ?? n.totalKeys) }) }) }),
      /* @__PURE__ */ e.jsx(g, { span: 6, children: /* @__PURE__ */ e.jsx(y, { children: /* @__PURE__ */ e.jsx(f, { title: "内存使用", value: z(n.usedMemory ?? n.memoryUsed) }) }) }),
      /* @__PURE__ */ e.jsx(g, { span: 6, children: /* @__PURE__ */ e.jsx(y, { children: /* @__PURE__ */ e.jsx(f, { title: "命中率", value: n.hitRate != null ? `${(n.hitRate * 100).toFixed(1)}%` : "—" }) }) }),
      /* @__PURE__ */ e.jsx(g, { span: 6, children: /* @__PURE__ */ e.jsx(y, { children: /* @__PURE__ */ e.jsx(f, { title: "过期键", value: z(n.expiredKeys) }) }) })
    ] }),
    d && /* @__PURE__ */ e.jsx(y, { title: "Redis INFO", children: /* @__PURE__ */ e.jsx(E, { column: 2, bordered: !0, size: "small", children: Object.entries(d).slice(0, 20).map(([l, a]) => /* @__PURE__ */ e.jsx(E.Item, { label: l, children: typeof a == "object" ? JSON.stringify(a) : String(a) }, l)) }) })
  ] });
}
const { Title: ne, Paragraph: re, Text: se } = b;
function ie(n) {
  const r = { STRING: "blue", HASH: "green", LIST: "purple", SET: "orange", ZSET: "cyan", NONE: "default" };
  return /* @__PURE__ */ e.jsx(v, { color: r[n] || "default", children: n || "未采信" });
}
function le() {
  const [n, r] = c([]), [d, h] = c("*"), [i, u] = c(!1), [x, p] = c(null), [o, l] = c(null), a = async () => {
    u(!0);
    try {
      const t = await S.keys({ pattern: d }), s = Array.isArray(t) ? t : (t == null ? void 0 : t.keys) || [];
      r(s.map((C, R) => typeof C == "string" ? { key: R, name: C } : { key: R, ...C })), p(null);
    } catch (t) {
      p((t == null ? void 0 : t.message) || String(t));
    } finally {
      u(!1);
    }
  };
  T(() => {
    a();
  }, []);
  const A = async (t) => {
    try {
      const s = await S.key({ key: t.name || t.key });
      l({ ...t, ...s });
    } catch (s) {
      l({ ...t, error: (s == null ? void 0 : s.message) || String(s) });
    }
  }, _ = [
    {
      title: "键名",
      key: "name",
      ellipsis: !0,
      render: (t, s) => /* @__PURE__ */ e.jsx(se, { code: !0, style: { fontSize: 12 }, children: s.name || s.key })
    },
    {
      title: "类型",
      dataIndex: "type",
      key: "type",
      width: 100,
      // 「没测」时显示什么 —— 假绿列探针要靠这个声明把"没测"与"测过且正常"分开
      unmeasured: "未采信",
      render: (t, s) => s != null && s.desyncSuspect ? /* @__PURE__ */ e.jsx(v, { color: "red", children: "应答错位·未采信" }) : ie(t)
    },
    {
      title: "TTL",
      dataIndex: "ttlSeconds",
      key: "ttlSeconds",
      width: 100,
      // ⚠️ 旧版这一列的取键写的是 ttl（后端从来没给过 'ttl' 这个键，给的是 ttlSeconds）
      // ⇒ 这一列一直是空的（假绿列：键名对不上，列全是空）。TASK-20260925-041 由探针抓出并修掉。
      // （注：探针是按字面量扫 dataIndex 的，所以这里**不能**把旧写法原样写进注释 ——
      //  否则它会当成页面真有一个 ttl 列，造出一个查无此列的假红。）
      unmeasured: "未测",
      render: (t, s) => s != null && s.ttlSecondsError ? /* @__PURE__ */ e.jsx(v, { color: "red", children: "读数不可信" }) : t == null ? "未测" : t < 0 ? "永不过期" : `${t}s`
    },
    {
      title: "操作",
      key: "op",
      width: 100,
      render: (t, s) => /* @__PURE__ */ e.jsx(m, { size: "small", onClick: () => A(s), children: "详情" })
    }
  ];
  return /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsxs(k, { style: { marginBottom: 16 }, wrap: !0, children: [
      /* @__PURE__ */ e.jsx(ne, { level: 4, style: { margin: 0 }, children: "键浏览" }),
      /* @__PURE__ */ e.jsx(
        O,
        {
          value: d,
          onChange: (t) => h(t.target.value),
          onPressEnter: a,
          placeholder: "如 user:* ",
          style: { width: 240 },
          prefix: /* @__PURE__ */ e.jsx($, {})
        }
      ),
      /* @__PURE__ */ e.jsx(m, { type: "primary", onClick: a, loading: i, children: "搜索" }),
      /* @__PURE__ */ e.jsx(m, { icon: /* @__PURE__ */ e.jsx(I, {}), onClick: a, children: "刷新" })
    ] }),
    /* @__PURE__ */ e.jsx(re, { type: "secondary", children: "Redis 键空间浏览（/cache/keys?pattern=）+ 单键详情（/cache/key）。" }),
    x && /* @__PURE__ */ e.jsx(P, { type: "error", showIcon: !0, style: { marginBottom: 16 }, message: "后端未连接", description: x }),
    /* @__PURE__ */ e.jsx(
      Q,
      {
        rows: n,
        columns: _,
        rowKey: "key",
        loading: i,
        emptyTitle: "没有匹配的 key",
        emptyDescription: "换个 pattern 再查一次，或点「刷新」看全量键空间。",
        cardExtra: /* @__PURE__ */ e.jsx(m, { icon: /* @__PURE__ */ e.jsx(I, {}), onClick: a, children: "刷新" })
      }
    ),
    /* @__PURE__ */ e.jsx(
      Z,
      {
        title: o ? `键详情：${o.name || o.key}` : "",
        open: !!o,
        onCancel: () => l(null),
        footer: /* @__PURE__ */ e.jsx(m, { onClick: () => l(null), children: "关闭" }),
        width: 700,
        children: o && /* @__PURE__ */ e.jsx("pre", { style: {
          maxHeight: 400,
          overflow: "auto",
          background: "#1e1e1e",
          color: "#d4d4d4",
          padding: 12,
          fontSize: 12,
          borderRadius: 4
        }, children: o.error || (typeof o.value == "object" ? JSON.stringify(o.value, null, 2) : String(o.value ?? JSON.stringify(o, null, 2))) })
      }
    )
  ] });
}
const { Title: oe, Paragraph: ae } = b;
function ce() {
  const n = X(), [r, d] = c(null);
  T(() => {
    const i = localStorage.getItem("userInfo");
    if (i)
      try {
        d(JSON.parse(i));
      } catch {
        d({ name: i });
      }
  }, []);
  const h = de.filter((i) => i.key !== "/z-cache/home");
  return /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsx(y, { style: { marginBottom: 16, background: "linear-gradient(135deg, #7c3aed 0%, #6d28d9 100%)", border: "none" }, children: /* @__PURE__ */ e.jsxs(k, { direction: "vertical", size: 4, style: { color: "#fff" }, children: [
      /* @__PURE__ */ e.jsxs(oe, { level: 3, style: { color: "#fff", margin: 0 }, children: [
        "欢迎",
        r != null && r.name ? `，${r.name}` : ""
      ] }),
      /* @__PURE__ */ e.jsx(ae, { style: { color: "rgba(255,255,255,0.85)", margin: 0 }, children: "z-cache 缓存中心 管理台" }),
      (r == null ? void 0 : r.role) && /* @__PURE__ */ e.jsx(v, { style: { marginTop: 8, background: "rgba(255,255,255,0.2)", color: "#fff", border: "none" }, children: r.role })
    ] }) }),
    /* @__PURE__ */ e.jsx(N, { gutter: [16, 16], children: h.map((i) => /* @__PURE__ */ e.jsx(g, { xs: 24, sm: 12, md: 12, lg: 8, children: /* @__PURE__ */ e.jsx(y, { hoverable: !0, onClick: () => n(i.key), style: { borderTop: "3px solid #7c3aed" }, children: /* @__PURE__ */ e.jsxs(k, { align: "start", size: 12, children: [
      /* @__PURE__ */ e.jsx("div", { style: {
        width: 44,
        height: 44,
        borderRadius: 8,
        flexShrink: 0,
        background: "rgba(124,58,237,0.08)",
        color: "#7c3aed",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontSize: 20
      }, children: i.icon }),
      /* @__PURE__ */ e.jsxs("div", { style: { minWidth: 0 }, children: [
        /* @__PURE__ */ e.jsx("div", { style: { fontSize: 15, fontWeight: 600, color: "#0f172a" }, children: i.label }),
        i.desc && /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12, color: "#94a3b8", marginTop: 2 }, children: i.desc })
      ] })
    ] }) }) }, i.key)) })
  ] });
}
function ge({ mockAuth: n } = {}) {
  const r = n || {
    username: "",
    password: "",
    user: { name: "", role: "管理员" }
  }, [d, h] = c(!1), i = async (u) => {
    h(!0);
    try {
      if (!r.username || !r.password) {
        w.error("未配置 mock 账号（VITE_MOCK_USER / VITE_MOCK_PASSWORD）");
        return;
      }
      u.username === r.username && u.password === r.password ? (localStorage.setItem("token", "mock-" + r.username), localStorage.setItem("userInfo", JSON.stringify(r.user)), w.success("登录成功（mock 模式，待 CTC/SSO 接入）"), window.location.href = "/z-cache/home") : w.error("用户名或密码错误");
    } finally {
      h(!1);
    }
  };
  return /* @__PURE__ */ e.jsx("div", { style: {
    height: "100vh",
    display: "flex",
    justifyContent: "center",
    alignItems: "center",
    background: "linear-gradient(135deg, #eef2ff 0%, #f5f3ff 50%, #eff6ff 100%)"
  }, children: /* @__PURE__ */ e.jsxs(y, { style: { width: 400, boxShadow: "0 10px 40px rgba(124,58,237,0.12)" }, children: [
    /* @__PURE__ */ e.jsxs("div", { style: { textAlign: "center", marginBottom: 20 }, children: [
      /* @__PURE__ */ e.jsx("div", { style: {
        width: 48,
        height: 48,
        borderRadius: 12,
        margin: "0 auto 12px",
        background: "linear-gradient(135deg, #7c3aed, #6d28d9)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        color: "#fff",
        fontSize: 24,
        fontWeight: 700
      }, children: "z" }),
      /* @__PURE__ */ e.jsx("div", { style: { fontSize: 18, fontWeight: 600, color: "#0f172a" }, children: "z-cache 管理台" })
    ] }),
    /* @__PURE__ */ e.jsxs(j, { onFinish: i, size: "large", children: [
      /* @__PURE__ */ e.jsx(j.Item, { name: "username", rules: [{ required: !0, message: "请输入用户名" }], children: /* @__PURE__ */ e.jsx(O, { prefix: /* @__PURE__ */ e.jsx(D, {}), placeholder: "用户名" }) }),
      /* @__PURE__ */ e.jsx(j.Item, { name: "password", rules: [{ required: !0, message: "请输入密码" }], children: /* @__PURE__ */ e.jsx(O.Password, { prefix: /* @__PURE__ */ e.jsx(H, {}), placeholder: "密码" }) }),
      /* @__PURE__ */ e.jsx(j.Item, { style: { marginBottom: 8 }, children: /* @__PURE__ */ e.jsx(
        m,
        {
          type: "primary",
          htmlType: "submit",
          loading: d,
          block: !0,
          style: { background: "linear-gradient(135deg, #7c3aed 0%, #6d28d9 100%)", border: "none" },
          children: "登 录"
        }
      ) }),
      /* @__PURE__ */ e.jsx("div", { style: { textAlign: "center", color: "#94a3b8", fontSize: 12 }, children: "Mock 模式：账号由宿主注入（VITE_MOCK_USER / VITE_MOCK_PASSWORD）" })
    ] })
  ] }) });
}
const de = [
  { key: "/z-cache/home", label: "首页", icon: /* @__PURE__ */ e.jsx(U, {}) },
  { key: "/z-cache/overview", label: "总览", icon: /* @__PURE__ */ e.jsx(W, {}) },
  { key: "/z-cache/keys", label: "键浏览", icon: /* @__PURE__ */ e.jsx(J, {}) },
  { key: "/z-cache/stats", label: "统计与热 key", icon: /* @__PURE__ */ e.jsx(F, {}) },
  { key: "/z-cache/instance", label: "实例与三态", icon: /* @__PURE__ */ e.jsx(V, {}) },
  { key: "/z-cache/docs", label: "组件文档", icon: /* @__PURE__ */ e.jsx(q, {}) }
], fe = [
  { path: "/z-cache/home", Component: ce },
  { path: "/z-cache/overview", Component: te },
  { path: "/z-cache/keys", Component: le },
  { path: "/z-cache/stats", Component: B },
  { path: "/z-cache/instance", Component: K },
  { path: "/z-cache/docs", Component: L },
  { path: "/z-cache/:rest*", Component: M }
];
export {
  L as ComponentDocPage,
  ce as HomePage,
  le as Keys,
  ge as LoginPage,
  te as Overview,
  de as menuItems,
  fe as routes
};
