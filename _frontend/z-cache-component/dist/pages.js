import { j as e, c as f, C as $ } from "./CacheApp-DedTKJTi.js";
import { ReloadOutlined as T, SearchOutlined as B, HomeOutlined as A, PieChartOutlined as E, KeyOutlined as K } from "@ant-design/icons";
import { useState as c, useEffect as w } from "react";
import { Typography as k, Space as v, Button as m, Alert as I, Spin as J, Row as O, Col as g, Card as h, Statistic as j, Descriptions as C, Input as L, Table as D, Modal as H, Tag as P } from "antd";
import { useNavigate as F } from "react-router-dom";
const { Title: M, Paragraph: U } = k;
function S(n) {
  return n == null ? "—" : typeof n == "number" ? n.toLocaleString("zh-CN") : String(n);
}
function W() {
  const [n, l] = c(null), [d, y] = c(null), [r, u] = c(!1), [p, x] = c(null), i = async () => {
    u(!0);
    try {
      const [s, a] = await Promise.all([
        f.overview().catch(() => null),
        f.info().catch(() => null)
      ]);
      l(s), y(a), x(null);
    } catch (s) {
      x((s == null ? void 0 : s.message) || String(s));
    } finally {
      u(!1);
    }
  };
  return w(() => {
    i();
    const s = setInterval(i, 15e3);
    return () => clearInterval(s);
  }, []), /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsxs(v, { style: { marginBottom: 16 }, children: [
      /* @__PURE__ */ e.jsx(M, { level: 4, style: { margin: 0 }, children: "缓存总览" }),
      /* @__PURE__ */ e.jsx(m, { icon: /* @__PURE__ */ e.jsx(T, {}), onClick: i, loading: r, children: "刷新" }),
      /* @__PURE__ */ e.jsx(k.Text, { type: "secondary", children: "15s 自动刷新" })
    ] }),
    /* @__PURE__ */ e.jsx(U, { type: "secondary", children: "/cache/overview + /cache/info：键数 / 内存 / 命中率。" }),
    p && /* @__PURE__ */ e.jsx(I, { type: "error", showIcon: !0, style: { marginBottom: 16 }, message: "后端未连接", description: p }),
    r && !n && /* @__PURE__ */ e.jsx(J, {}),
    n && /* @__PURE__ */ e.jsxs(O, { gutter: 16, style: { marginBottom: 16 }, children: [
      /* @__PURE__ */ e.jsx(g, { span: 6, children: /* @__PURE__ */ e.jsx(h, { children: /* @__PURE__ */ e.jsx(j, { title: "键总数", value: S(n.keyCount ?? n.totalKeys) }) }) }),
      /* @__PURE__ */ e.jsx(g, { span: 6, children: /* @__PURE__ */ e.jsx(h, { children: /* @__PURE__ */ e.jsx(j, { title: "内存使用", value: S(n.usedMemory ?? n.memoryUsed) }) }) }),
      /* @__PURE__ */ e.jsx(g, { span: 6, children: /* @__PURE__ */ e.jsx(h, { children: /* @__PURE__ */ e.jsx(j, { title: "命中率", value: n.hitRate != null ? `${(n.hitRate * 100).toFixed(1)}%` : "—" }) }) }),
      /* @__PURE__ */ e.jsx(g, { span: 6, children: /* @__PURE__ */ e.jsx(h, { children: /* @__PURE__ */ e.jsx(j, { title: "过期键", value: S(n.expiredKeys) }) }) })
    ] }),
    d && /* @__PURE__ */ e.jsx(h, { title: "Redis INFO", children: /* @__PURE__ */ e.jsx(C, { column: 2, bordered: !0, size: "small", children: Object.entries(d).slice(0, 20).map(([s, a]) => /* @__PURE__ */ e.jsx(C.Item, { label: s, children: typeof a == "object" ? JSON.stringify(a) : String(a) }, s)) }) })
  ] });
}
const { Title: _, Paragraph: q, Text: G } = k;
function Q(n) {
  const l = { string: "blue", hash: "green", list: "purple", set: "orange", zset: "cyan" };
  return /* @__PURE__ */ e.jsx(P, { color: l[n] || "default", children: n || "—" });
}
function V() {
  const [n, l] = c([]), [d, y] = c("*"), [r, u] = c(!1), [p, x] = c(null), [i, s] = c(null), a = async () => {
    u(!0);
    try {
      const t = await f.keys({ pattern: d }), o = Array.isArray(t) ? t : (t == null ? void 0 : t.keys) || [];
      l(o.map((b, z) => typeof b == "string" ? { key: z, name: b } : { key: z, ...b })), x(null);
    } catch (t) {
      x((t == null ? void 0 : t.message) || String(t));
    } finally {
      u(!1);
    }
  };
  w(() => {
    a();
  }, []);
  const R = async (t) => {
    try {
      const o = await f.key({ key: t.name || t.key });
      s({ ...t, ...o });
    } catch (o) {
      s({ ...t, error: (o == null ? void 0 : o.message) || String(o) });
    }
  }, N = [
    {
      title: "键名",
      key: "name",
      ellipsis: !0,
      render: (t, o) => /* @__PURE__ */ e.jsx(G, { code: !0, style: { fontSize: 12 }, children: o.name || o.key })
    },
    { title: "类型", dataIndex: "type", key: "type", width: 100, render: Q },
    {
      title: "TTL",
      dataIndex: "ttl",
      key: "ttl",
      width: 100,
      render: (t) => t == null ? "—" : t < 0 ? "永不过期" : `${t}s`
    },
    {
      title: "操作",
      key: "op",
      width: 100,
      render: (t, o) => /* @__PURE__ */ e.jsx(m, { size: "small", onClick: () => R(o), children: "详情" })
    }
  ];
  return /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsxs(v, { style: { marginBottom: 16 }, wrap: !0, children: [
      /* @__PURE__ */ e.jsx(_, { level: 4, style: { margin: 0 }, children: "键浏览" }),
      /* @__PURE__ */ e.jsx(
        L,
        {
          value: d,
          onChange: (t) => y(t.target.value),
          onPressEnter: a,
          placeholder: "如 user:* ",
          style: { width: 240 },
          prefix: /* @__PURE__ */ e.jsx(B, {})
        }
      ),
      /* @__PURE__ */ e.jsx(m, { type: "primary", onClick: a, loading: r, children: "搜索" }),
      /* @__PURE__ */ e.jsx(m, { icon: /* @__PURE__ */ e.jsx(T, {}), onClick: a, children: "刷新" })
    ] }),
    /* @__PURE__ */ e.jsx(q, { type: "secondary", children: "Redis 键空间浏览（/cache/keys?pattern=）+ 单键详情（/cache/key）。" }),
    p && /* @__PURE__ */ e.jsx(I, { type: "error", showIcon: !0, style: { marginBottom: 16 }, message: "后端未连接", description: p }),
    /* @__PURE__ */ e.jsx(h, { children: /* @__PURE__ */ e.jsx(
      D,
      {
        rowKey: "key",
        dataSource: n,
        columns: N,
        loading: r,
        size: "small",
        pagination: { pageSize: 20 }
      }
    ) }),
    /* @__PURE__ */ e.jsx(
      H,
      {
        title: i ? `键详情：${i.name || i.key}` : "",
        open: !!i,
        onCancel: () => s(null),
        footer: /* @__PURE__ */ e.jsx(m, { onClick: () => s(null), children: "关闭" }),
        width: 700,
        children: i && /* @__PURE__ */ e.jsx("pre", { style: {
          maxHeight: 400,
          overflow: "auto",
          background: "#1e1e1e",
          color: "#d4d4d4",
          padding: 12,
          fontSize: 12,
          borderRadius: 4
        }, children: i.error || (typeof i.value == "object" ? JSON.stringify(i.value, null, 2) : String(i.value ?? JSON.stringify(i, null, 2))) })
      }
    )
  ] });
}
const { Title: X, Paragraph: Y } = k;
function Z() {
  const n = F(), [l, d] = c(null);
  w(() => {
    const r = localStorage.getItem("userInfo");
    if (r)
      try {
        d(JSON.parse(r));
      } catch {
        d({ name: r });
      }
  }, []);
  const y = ee.filter((r) => r.key !== "/z-cache/home");
  return /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsx(h, { style: { marginBottom: 16, background: "linear-gradient(135deg, #7c3aed 0%, #6d28d9 100%)", border: "none" }, children: /* @__PURE__ */ e.jsxs(v, { direction: "vertical", size: 4, style: { color: "#fff" }, children: [
      /* @__PURE__ */ e.jsxs(X, { level: 3, style: { color: "#fff", margin: 0 }, children: [
        "欢迎",
        l != null && l.name ? `，${l.name}` : ""
      ] }),
      /* @__PURE__ */ e.jsx(Y, { style: { color: "rgba(255,255,255,0.85)", margin: 0 }, children: "z-cache 缓存中心 管理台" }),
      (l == null ? void 0 : l.role) && /* @__PURE__ */ e.jsx(P, { style: { marginTop: 8, background: "rgba(255,255,255,0.2)", color: "#fff", border: "none" }, children: l.role })
    ] }) }),
    /* @__PURE__ */ e.jsx(O, { gutter: [16, 16], children: y.map((r) => /* @__PURE__ */ e.jsx(g, { xs: 24, sm: 12, md: 12, lg: 8, children: /* @__PURE__ */ e.jsx(h, { hoverable: !0, onClick: () => n(r.key), style: { borderTop: "3px solid #7c3aed" }, children: /* @__PURE__ */ e.jsxs(v, { align: "start", size: 12, children: [
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
      }, children: r.icon }),
      /* @__PURE__ */ e.jsxs("div", { style: { minWidth: 0 }, children: [
        /* @__PURE__ */ e.jsx("div", { style: { fontSize: 15, fontWeight: 600, color: "#0f172a" }, children: r.label }),
        /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12, color: "#94a3b8", marginTop: 2 }, children: r.key })
      ] })
    ] }) }) }, r.key)) })
  ] });
}
const ee = [
  { key: "/z-cache/home", label: "首页", icon: /* @__PURE__ */ e.jsx(A, {}) },
  { key: "/z-cache/overview", label: "总览", icon: /* @__PURE__ */ e.jsx(E, {}) },
  { key: "/z-cache/keys", label: "键浏览", icon: /* @__PURE__ */ e.jsx(K, {}) }
], ie = [
  { path: "/z-cache/home", Component: Z },
  { path: "/z-cache/overview", Component: W },
  { path: "/z-cache/keys", Component: V },
  { path: "/z-cache/:rest*", Component: $ }
];
export {
  V as Keys,
  W as Overview,
  ee as menuItems,
  ie as routes
};
