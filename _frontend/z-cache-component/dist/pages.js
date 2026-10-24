import { j as e, d as S, a as $, C as H } from "./ComponentDoc-DSy1Db0x.js";
import { ReloadOutlined as N, SearchOutlined as K, UserOutlined as J, LockOutlined as D, HomeOutlined as F, PieChartOutlined as M, KeyOutlined as U, ReadOutlined as W } from "@ant-design/icons";
import { useState as a, useEffect as T } from "react";
import { Typography as w, Space as v, Button as y, Alert as A, Spin as _, Row as B, Col as f, Card as h, Statistic as j, Descriptions as P, Input as I, Table as q, Modal as G, Tag as b, Form as k, message as R } from "antd";
import { useNavigate as Z } from "react-router-dom";
const { Title: Q, Paragraph: V } = w;
function C(n) {
  return n == null ? "—" : typeof n == "number" ? n.toLocaleString("zh-CN") : String(n);
}
function X() {
  const [n, s] = a(null), [c, u] = a(null), [l, x] = a(!1), [g, p] = a(null), o = async () => {
    x(!0);
    try {
      const [i, d] = await Promise.all([
        S.overview().catch(() => null),
        S.info().catch(() => null)
      ]);
      s(i), u(d), p(null);
    } catch (i) {
      p((i == null ? void 0 : i.message) || String(i));
    } finally {
      x(!1);
    }
  };
  return T(() => {
    o();
    const i = setInterval(o, 15e3);
    return () => clearInterval(i);
  }, []), /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsxs(v, { style: { marginBottom: 16 }, children: [
      /* @__PURE__ */ e.jsx(Q, { level: 4, style: { margin: 0 }, children: "缓存总览" }),
      /* @__PURE__ */ e.jsx(y, { icon: /* @__PURE__ */ e.jsx(N, {}), onClick: o, loading: l, children: "刷新" }),
      /* @__PURE__ */ e.jsx(w.Text, { type: "secondary", children: "15s 自动刷新" })
    ] }),
    /* @__PURE__ */ e.jsx(V, { type: "secondary", children: "/cache/overview + /cache/info：键数 / 内存 / 命中率。" }),
    g && /* @__PURE__ */ e.jsx(A, { type: "error", showIcon: !0, style: { marginBottom: 16 }, message: "后端未连接", description: g }),
    l && !n && /* @__PURE__ */ e.jsx(_, {}),
    n && /* @__PURE__ */ e.jsxs(B, { gutter: 16, style: { marginBottom: 16 }, children: [
      /* @__PURE__ */ e.jsx(f, { span: 6, children: /* @__PURE__ */ e.jsx(h, { children: /* @__PURE__ */ e.jsx(j, { title: "键总数", value: C(n.keyCount ?? n.totalKeys) }) }) }),
      /* @__PURE__ */ e.jsx(f, { span: 6, children: /* @__PURE__ */ e.jsx(h, { children: /* @__PURE__ */ e.jsx(j, { title: "内存使用", value: C(n.usedMemory ?? n.memoryUsed) }) }) }),
      /* @__PURE__ */ e.jsx(f, { span: 6, children: /* @__PURE__ */ e.jsx(h, { children: /* @__PURE__ */ e.jsx(j, { title: "命中率", value: n.hitRate != null ? `${(n.hitRate * 100).toFixed(1)}%` : "—" }) }) }),
      /* @__PURE__ */ e.jsx(f, { span: 6, children: /* @__PURE__ */ e.jsx(h, { children: /* @__PURE__ */ e.jsx(j, { title: "过期键", value: C(n.expiredKeys) }) }) })
    ] }),
    c && /* @__PURE__ */ e.jsx(h, { title: "Redis INFO", children: /* @__PURE__ */ e.jsx(P, { column: 2, bordered: !0, size: "small", children: Object.entries(c).slice(0, 20).map(([i, d]) => /* @__PURE__ */ e.jsx(P.Item, { label: i, children: typeof d == "object" ? JSON.stringify(d) : String(d) }, i)) }) })
  ] });
}
const { Title: Y, Paragraph: ee, Text: te } = w;
function ne(n) {
  const s = { STRING: "blue", HASH: "green", LIST: "purple", SET: "orange", ZSET: "cyan", NONE: "default" };
  return /* @__PURE__ */ e.jsx(b, { color: s[n] || "default", children: n || "未采信" });
}
function re() {
  const [n, s] = a([]), [c, u] = a("*"), [l, x] = a(!1), [g, p] = a(null), [o, i] = a(null), d = async () => {
    x(!0);
    try {
      const t = await S.keys({ pattern: c }), r = Array.isArray(t) ? t : (t == null ? void 0 : t.keys) || [];
      s(r.map((z, O) => typeof z == "string" ? { key: O, name: z } : { key: O, ...z })), p(null);
    } catch (t) {
      p((t == null ? void 0 : t.message) || String(t));
    } finally {
      x(!1);
    }
  };
  T(() => {
    d();
  }, []);
  const E = async (t) => {
    try {
      const r = await S.key({ key: t.name || t.key });
      i({ ...t, ...r });
    } catch (r) {
      i({ ...t, error: (r == null ? void 0 : r.message) || String(r) });
    }
  }, L = [
    {
      title: "键名",
      key: "name",
      ellipsis: !0,
      render: (t, r) => /* @__PURE__ */ e.jsx(te, { code: !0, style: { fontSize: 12 }, children: r.name || r.key })
    },
    {
      title: "类型",
      dataIndex: "type",
      key: "type",
      width: 100,
      // 「没测」时显示什么 —— 假绿列探针要靠这个声明把"没测"与"测过且正常"分开
      unmeasured: "未采信",
      render: (t, r) => r != null && r.desyncSuspect ? /* @__PURE__ */ e.jsx(b, { color: "red", children: "应答错位·未采信" }) : ne(t)
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
      render: (t, r) => r != null && r.ttlSecondsError ? /* @__PURE__ */ e.jsx(b, { color: "red", children: "读数不可信" }) : t == null ? "未测" : t < 0 ? "永不过期" : `${t}s`
    },
    {
      title: "操作",
      key: "op",
      width: 100,
      render: (t, r) => /* @__PURE__ */ e.jsx(y, { size: "small", onClick: () => E(r), children: "详情" })
    }
  ];
  return /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsxs(v, { style: { marginBottom: 16 }, wrap: !0, children: [
      /* @__PURE__ */ e.jsx(Y, { level: 4, style: { margin: 0 }, children: "键浏览" }),
      /* @__PURE__ */ e.jsx(
        I,
        {
          value: c,
          onChange: (t) => u(t.target.value),
          onPressEnter: d,
          placeholder: "如 user:* ",
          style: { width: 240 },
          prefix: /* @__PURE__ */ e.jsx(K, {})
        }
      ),
      /* @__PURE__ */ e.jsx(y, { type: "primary", onClick: d, loading: l, children: "搜索" }),
      /* @__PURE__ */ e.jsx(y, { icon: /* @__PURE__ */ e.jsx(N, {}), onClick: d, children: "刷新" })
    ] }),
    /* @__PURE__ */ e.jsx(ee, { type: "secondary", children: "Redis 键空间浏览（/cache/keys?pattern=）+ 单键详情（/cache/key）。" }),
    g && /* @__PURE__ */ e.jsx(A, { type: "error", showIcon: !0, style: { marginBottom: 16 }, message: "后端未连接", description: g }),
    /* @__PURE__ */ e.jsx(h, { children: /* @__PURE__ */ e.jsx(
      q,
      {
        rowKey: "key",
        dataSource: n,
        columns: L,
        loading: l,
        size: "small",
        pagination: { pageSize: 20 }
      }
    ) }),
    /* @__PURE__ */ e.jsx(
      G,
      {
        title: o ? `键详情：${o.name || o.key}` : "",
        open: !!o,
        onCancel: () => i(null),
        footer: /* @__PURE__ */ e.jsx(y, { onClick: () => i(null), children: "关闭" }),
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
const { Title: se, Paragraph: le } = w;
function ie() {
  const n = Z(), [s, c] = a(null);
  T(() => {
    const l = localStorage.getItem("userInfo");
    if (l)
      try {
        c(JSON.parse(l));
      } catch {
        c({ name: l });
      }
  }, []);
  const u = oe.filter((l) => l.key !== "/z-cache/home");
  return /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsx(h, { style: { marginBottom: 16, background: "linear-gradient(135deg, #7c3aed 0%, #6d28d9 100%)", border: "none" }, children: /* @__PURE__ */ e.jsxs(v, { direction: "vertical", size: 4, style: { color: "#fff" }, children: [
      /* @__PURE__ */ e.jsxs(se, { level: 3, style: { color: "#fff", margin: 0 }, children: [
        "欢迎",
        s != null && s.name ? `，${s.name}` : ""
      ] }),
      /* @__PURE__ */ e.jsx(le, { style: { color: "rgba(255,255,255,0.85)", margin: 0 }, children: "z-cache 缓存中心 管理台" }),
      (s == null ? void 0 : s.role) && /* @__PURE__ */ e.jsx(b, { style: { marginTop: 8, background: "rgba(255,255,255,0.2)", color: "#fff", border: "none" }, children: s.role })
    ] }) }),
    /* @__PURE__ */ e.jsx(B, { gutter: [16, 16], children: u.map((l) => /* @__PURE__ */ e.jsx(f, { xs: 24, sm: 12, md: 12, lg: 8, children: /* @__PURE__ */ e.jsx(h, { hoverable: !0, onClick: () => n(l.key), style: { borderTop: "3px solid #7c3aed" }, children: /* @__PURE__ */ e.jsxs(v, { align: "start", size: 12, children: [
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
      }, children: l.icon }),
      /* @__PURE__ */ e.jsxs("div", { style: { minWidth: 0 }, children: [
        /* @__PURE__ */ e.jsx("div", { style: { fontSize: 15, fontWeight: 600, color: "#0f172a" }, children: l.label }),
        /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12, color: "#94a3b8", marginTop: 2 }, children: l.key })
      ] })
    ] }) }) }, l.key)) })
  ] });
}
const m = { username: "admin", password: "123456", user: { name: "admin", role: "管理员" } };
function me() {
  const [n, s] = a(!1), c = async (u) => {
    s(!0);
    try {
      u.username === m.username && u.password === m.password ? (localStorage.setItem("token", "mock-" + m.username), localStorage.setItem("userInfo", JSON.stringify(m.user)), R.success("登录成功（mock 模式，待 CTC/SSO 接入）"), window.location.href = "/z-cache/home") : R.error("用户名或密码错误（默认 admin/123456）");
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
  }, children: /* @__PURE__ */ e.jsxs(h, { style: { width: 400, boxShadow: "0 10px 40px rgba(124,58,237,0.12)" }, children: [
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
    /* @__PURE__ */ e.jsxs(k, { onFinish: c, size: "large", children: [
      /* @__PURE__ */ e.jsx(k.Item, { name: "username", rules: [{ required: !0, message: "请输入用户名" }], children: /* @__PURE__ */ e.jsx(I, { prefix: /* @__PURE__ */ e.jsx(J, {}), placeholder: "用户名" }) }),
      /* @__PURE__ */ e.jsx(k.Item, { name: "password", rules: [{ required: !0, message: "请输入密码" }], children: /* @__PURE__ */ e.jsx(I.Password, { prefix: /* @__PURE__ */ e.jsx(D, {}), placeholder: "密码" }) }),
      /* @__PURE__ */ e.jsx(k.Item, { style: { marginBottom: 8 }, children: /* @__PURE__ */ e.jsx(
        y,
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
        /* @__PURE__ */ e.jsx("b", { children: m.username }),
        " / 密码 ",
        /* @__PURE__ */ e.jsx("b", { children: m.password })
      ] })
    ] })
  ] }) });
}
const oe = [
  { key: "/z-cache/home", label: "首页", icon: /* @__PURE__ */ e.jsx(F, {}) },
  { key: "/z-cache/overview", label: "总览", icon: /* @__PURE__ */ e.jsx(M, {}) },
  { key: "/z-cache/keys", label: "键浏览", icon: /* @__PURE__ */ e.jsx(U, {}) },
  { key: "/z-cache/docs", label: "组件文档", icon: /* @__PURE__ */ e.jsx(W, {}) }
], ye = [
  { path: "/z-cache/home", Component: ie },
  { path: "/z-cache/overview", Component: X },
  { path: "/z-cache/keys", Component: re },
  { path: "/z-cache/docs", Component: $ },
  { path: "/z-cache/:rest*", Component: H }
];
export {
  $ as ComponentDocPage,
  ie as HomePage,
  re as Keys,
  me as LoginPage,
  X as Overview,
  oe as menuItems,
  ye as routes
};
