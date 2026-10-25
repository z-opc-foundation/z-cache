import { j as e, d as S, S as $, I as H, a as K, C as D } from "./ComponentDoc-CWdSziKP.js";
import { ReloadOutlined as I, SearchOutlined as J, UserOutlined as M, LockOutlined as F, HomeOutlined as U, PieChartOutlined as W, KeyOutlined as _, BarChartOutlined as q, MonitorOutlined as G, ReadOutlined as Z } from "@ant-design/icons";
import { useState as c, useEffect as O } from "react";
import { Typography as w, Space as b, Button as m, Alert as B, Spin as Q, Row as E, Col as f, Card as u, Statistic as j, Descriptions as R, Input as T, Modal as V, Tag as v, Form as k, message as N } from "antd";
import { ListTable as X } from "@yuku123/z-frontend-common/ui";
import { useNavigate as Y } from "react-router-dom";
const { Title: ee, Paragraph: te } = w;
function C(n) {
  return n == null ? "—" : typeof n == "number" ? n.toLocaleString("zh-CN") : String(n);
}
function ne() {
  const [n, s] = c(null), [d, h] = c(null), [i, x] = c(!1), [p, g] = c(null), o = async () => {
    x(!0);
    try {
      const [l, a] = await Promise.all([
        S.overview().catch(() => null),
        S.info().catch(() => null)
      ]);
      s(l), h(a), g(null);
    } catch (l) {
      g((l == null ? void 0 : l.message) || String(l));
    } finally {
      x(!1);
    }
  };
  return O(() => {
    o();
    const l = setInterval(o, 15e3);
    return () => clearInterval(l);
  }, []), /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsxs(b, { style: { marginBottom: 16 }, children: [
      /* @__PURE__ */ e.jsx(ee, { level: 4, style: { margin: 0 }, children: "缓存总览" }),
      /* @__PURE__ */ e.jsx(m, { icon: /* @__PURE__ */ e.jsx(I, {}), onClick: o, loading: i, children: "刷新" }),
      /* @__PURE__ */ e.jsx(w.Text, { type: "secondary", children: "15s 自动刷新" })
    ] }),
    /* @__PURE__ */ e.jsx(te, { type: "secondary", children: "/cache/overview + /cache/info：键数 / 内存 / 命中率。" }),
    p && /* @__PURE__ */ e.jsx(B, { type: "error", showIcon: !0, style: { marginBottom: 16 }, message: "后端未连接", description: p }),
    i && !n && /* @__PURE__ */ e.jsx(Q, {}),
    n && /* @__PURE__ */ e.jsxs(E, { gutter: 16, style: { marginBottom: 16 }, children: [
      /* @__PURE__ */ e.jsx(f, { span: 6, children: /* @__PURE__ */ e.jsx(u, { children: /* @__PURE__ */ e.jsx(j, { title: "键总数", value: C(n.keyCount ?? n.totalKeys) }) }) }),
      /* @__PURE__ */ e.jsx(f, { span: 6, children: /* @__PURE__ */ e.jsx(u, { children: /* @__PURE__ */ e.jsx(j, { title: "内存使用", value: C(n.usedMemory ?? n.memoryUsed) }) }) }),
      /* @__PURE__ */ e.jsx(f, { span: 6, children: /* @__PURE__ */ e.jsx(u, { children: /* @__PURE__ */ e.jsx(j, { title: "命中率", value: n.hitRate != null ? `${(n.hitRate * 100).toFixed(1)}%` : "—" }) }) }),
      /* @__PURE__ */ e.jsx(f, { span: 6, children: /* @__PURE__ */ e.jsx(u, { children: /* @__PURE__ */ e.jsx(j, { title: "过期键", value: C(n.expiredKeys) }) }) })
    ] }),
    d && /* @__PURE__ */ e.jsx(u, { title: "Redis INFO", children: /* @__PURE__ */ e.jsx(R, { column: 2, bordered: !0, size: "small", children: Object.entries(d).slice(0, 20).map(([l, a]) => /* @__PURE__ */ e.jsx(R.Item, { label: l, children: typeof a == "object" ? JSON.stringify(a) : String(a) }, l)) }) })
  ] });
}
const { Title: re, Paragraph: se, Text: ie } = w;
function le(n) {
  const s = { STRING: "blue", HASH: "green", LIST: "purple", SET: "orange", ZSET: "cyan", NONE: "default" };
  return /* @__PURE__ */ e.jsx(v, { color: s[n] || "default", children: n || "未采信" });
}
function oe() {
  const [n, s] = c([]), [d, h] = c("*"), [i, x] = c(!1), [p, g] = c(null), [o, l] = c(null), a = async () => {
    x(!0);
    try {
      const t = await S.keys({ pattern: d }), r = Array.isArray(t) ? t : (t == null ? void 0 : t.keys) || [];
      s(r.map((z, P) => typeof z == "string" ? { key: P, name: z } : { key: P, ...z })), g(null);
    } catch (t) {
      g((t == null ? void 0 : t.message) || String(t));
    } finally {
      x(!1);
    }
  };
  O(() => {
    a();
  }, []);
  const A = async (t) => {
    try {
      const r = await S.key({ key: t.name || t.key });
      l({ ...t, ...r });
    } catch (r) {
      l({ ...t, error: (r == null ? void 0 : r.message) || String(r) });
    }
  }, L = [
    {
      title: "键名",
      key: "name",
      ellipsis: !0,
      render: (t, r) => /* @__PURE__ */ e.jsx(ie, { code: !0, style: { fontSize: 12 }, children: r.name || r.key })
    },
    {
      title: "类型",
      dataIndex: "type",
      key: "type",
      width: 100,
      // 「没测」时显示什么 —— 假绿列探针要靠这个声明把"没测"与"测过且正常"分开
      unmeasured: "未采信",
      render: (t, r) => r != null && r.desyncSuspect ? /* @__PURE__ */ e.jsx(v, { color: "red", children: "应答错位·未采信" }) : le(t)
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
      render: (t, r) => r != null && r.ttlSecondsError ? /* @__PURE__ */ e.jsx(v, { color: "red", children: "读数不可信" }) : t == null ? "未测" : t < 0 ? "永不过期" : `${t}s`
    },
    {
      title: "操作",
      key: "op",
      width: 100,
      render: (t, r) => /* @__PURE__ */ e.jsx(m, { size: "small", onClick: () => A(r), children: "详情" })
    }
  ];
  return /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsxs(b, { style: { marginBottom: 16 }, wrap: !0, children: [
      /* @__PURE__ */ e.jsx(re, { level: 4, style: { margin: 0 }, children: "键浏览" }),
      /* @__PURE__ */ e.jsx(
        T,
        {
          value: d,
          onChange: (t) => h(t.target.value),
          onPressEnter: a,
          placeholder: "如 user:* ",
          style: { width: 240 },
          prefix: /* @__PURE__ */ e.jsx(J, {})
        }
      ),
      /* @__PURE__ */ e.jsx(m, { type: "primary", onClick: a, loading: i, children: "搜索" }),
      /* @__PURE__ */ e.jsx(m, { icon: /* @__PURE__ */ e.jsx(I, {}), onClick: a, children: "刷新" })
    ] }),
    /* @__PURE__ */ e.jsx(se, { type: "secondary", children: "Redis 键空间浏览（/cache/keys?pattern=）+ 单键详情（/cache/key）。" }),
    p && /* @__PURE__ */ e.jsx(B, { type: "error", showIcon: !0, style: { marginBottom: 16 }, message: "后端未连接", description: p }),
    /* @__PURE__ */ e.jsx(
      X,
      {
        rows: n,
        columns: L,
        rowKey: "key",
        loading: i,
        emptyTitle: "没有匹配的 key",
        emptyDescription: "换个 pattern 再查一次，或点「刷新」看全量键空间。",
        cardExtra: /* @__PURE__ */ e.jsx(m, { icon: /* @__PURE__ */ e.jsx(I, {}), onClick: a, children: "刷新" })
      }
    ),
    /* @__PURE__ */ e.jsx(
      V,
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
const { Title: ae, Paragraph: ce } = w;
function de() {
  const n = Y(), [s, d] = c(null);
  O(() => {
    const i = localStorage.getItem("userInfo");
    if (i)
      try {
        d(JSON.parse(i));
      } catch {
        d({ name: i });
      }
  }, []);
  const h = he.filter((i) => i.key !== "/z-cache/home");
  return /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsx(u, { style: { marginBottom: 16, background: "linear-gradient(135deg, #7c3aed 0%, #6d28d9 100%)", border: "none" }, children: /* @__PURE__ */ e.jsxs(b, { direction: "vertical", size: 4, style: { color: "#fff" }, children: [
      /* @__PURE__ */ e.jsxs(ae, { level: 3, style: { color: "#fff", margin: 0 }, children: [
        "欢迎",
        s != null && s.name ? `，${s.name}` : ""
      ] }),
      /* @__PURE__ */ e.jsx(ce, { style: { color: "rgba(255,255,255,0.85)", margin: 0 }, children: "z-cache 缓存中心 管理台" }),
      (s == null ? void 0 : s.role) && /* @__PURE__ */ e.jsx(v, { style: { marginTop: 8, background: "rgba(255,255,255,0.2)", color: "#fff", border: "none" }, children: s.role })
    ] }) }),
    /* @__PURE__ */ e.jsx(E, { gutter: [16, 16], children: h.map((i) => /* @__PURE__ */ e.jsx(f, { xs: 24, sm: 12, md: 12, lg: 8, children: /* @__PURE__ */ e.jsx(u, { hoverable: !0, onClick: () => n(i.key), style: { borderTop: "3px solid #7c3aed" }, children: /* @__PURE__ */ e.jsxs(b, { align: "start", size: 12, children: [
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
        /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12, color: "#94a3b8", marginTop: 2 }, children: i.key })
      ] })
    ] }) }) }, i.key)) })
  ] });
}
const y = { username: "admin", password: "123456", user: { name: "admin", role: "管理员" } };
function fe() {
  const [n, s] = c(!1), d = async (h) => {
    s(!0);
    try {
      h.username === y.username && h.password === y.password ? (localStorage.setItem("token", "mock-" + y.username), localStorage.setItem("userInfo", JSON.stringify(y.user)), N.success("登录成功（mock 模式，待 CTC/SSO 接入）"), window.location.href = "/z-cache/home") : N.error("用户名或密码错误（默认 admin/123456）");
    } finally {
      s(!1);
    }
  };
  return /* @__PURE__ */ e.jsx("div", { style: {
    height: "100vh",
    display: "flex",
    justifyContent: "center",
    alignItems: "center",
    background: "linear-gradient(135deg, #eef2ff 0%, #f5f3ff 50%, #eff6ff 100%)"
  }, children: /* @__PURE__ */ e.jsxs(u, { style: { width: 400, boxShadow: "0 10px 40px rgba(124,58,237,0.12)" }, children: [
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
    /* @__PURE__ */ e.jsxs(k, { onFinish: d, size: "large", children: [
      /* @__PURE__ */ e.jsx(k.Item, { name: "username", rules: [{ required: !0, message: "请输入用户名" }], children: /* @__PURE__ */ e.jsx(T, { prefix: /* @__PURE__ */ e.jsx(M, {}), placeholder: "用户名" }) }),
      /* @__PURE__ */ e.jsx(k.Item, { name: "password", rules: [{ required: !0, message: "请输入密码" }], children: /* @__PURE__ */ e.jsx(T.Password, { prefix: /* @__PURE__ */ e.jsx(F, {}), placeholder: "密码" }) }),
      /* @__PURE__ */ e.jsx(k.Item, { style: { marginBottom: 8 }, children: /* @__PURE__ */ e.jsx(
        m,
        {
          type: "primary",
          htmlType: "submit",
          loading: n,
          block: !0,
          style: { background: "linear-gradient(135deg, #7c3aed 0%, #6d28d9 100%)", border: "none" },
          children: "登 录"
        }
      ) }),
      /* @__PURE__ */ e.jsxs("div", { style: { textAlign: "center", color: "#94a3b8", fontSize: 12 }, children: [
        "Mock 模式：用户名 ",
        /* @__PURE__ */ e.jsx("b", { children: y.username }),
        " / 密码 ",
        /* @__PURE__ */ e.jsx("b", { children: y.password })
      ] })
    ] })
  ] }) });
}
const he = [
  { key: "/z-cache/home", label: "首页", icon: /* @__PURE__ */ e.jsx(U, {}) },
  { key: "/z-cache/overview", label: "总览", icon: /* @__PURE__ */ e.jsx(W, {}) },
  { key: "/z-cache/keys", label: "键浏览", icon: /* @__PURE__ */ e.jsx(_, {}) },
  { key: "/z-cache/stats", label: "统计与热 key", icon: /* @__PURE__ */ e.jsx(q, {}) },
  { key: "/z-cache/instance", label: "实例与三态", icon: /* @__PURE__ */ e.jsx(G, {}) },
  { key: "/z-cache/docs", label: "组件文档", icon: /* @__PURE__ */ e.jsx(Z, {}) }
], je = [
  { path: "/z-cache/home", Component: de },
  { path: "/z-cache/overview", Component: ne },
  { path: "/z-cache/keys", Component: oe },
  { path: "/z-cache/stats", Component: $ },
  { path: "/z-cache/instance", Component: H },
  { path: "/z-cache/docs", Component: K },
  { path: "/z-cache/:rest*", Component: D }
];
export {
  K as ComponentDocPage,
  de as HomePage,
  oe as Keys,
  fe as LoginPage,
  ne as Overview,
  he as menuItems,
  je as routes
};
