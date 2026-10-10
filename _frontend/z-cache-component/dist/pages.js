import ye, { useState as g, useEffect as M } from "react";
import { ReloadOutlined as Q, SearchOutlined as xe, HomeOutlined as ve, PieChartOutlined as ge, KeyOutlined as je } from "@ant-design/icons";
import { Typography as $, Space as I, Button as w, Alert as ee, Spin as be, Row as re, Col as O, Card as E, Statistic as C, Descriptions as H, Input as Ee, Table as Te, Modal as ke, Tag as te } from "antd";
import { c as z } from "./api-DfrsadMq.js";
import { a as He } from "./api-DfrsadMq.js";
import { useNavigate as _e } from "react-router-dom";
var N = { exports: {} }, R = {};
/**
 * @license React
 * react-jsx-runtime.production.js
 *
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
var X;
function Re() {
  if (X) return R;
  X = 1;
  var n = Symbol.for("react.transitional.element"), l = Symbol.for("react.fragment");
  function h(y, a, d) {
    var x = null;
    if (d !== void 0 && (x = "" + d), a.key !== void 0 && (x = "" + a.key), "key" in a) {
      d = {};
      for (var v in a)
        v !== "key" && (d[v] = a[v]);
    } else d = a;
    return a = d.ref, {
      $$typeof: n,
      type: y,
      key: x,
      ref: a !== void 0 ? a : null,
      props: d
    };
  }
  return R.Fragment = l, R.jsx = h, R.jsxs = h, R;
}
var S = {};
/**
 * @license React
 * react-jsx-runtime.development.js
 *
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
var K;
function Se() {
  return K || (K = 1, process.env.NODE_ENV !== "production" && (function() {
    function n(e) {
      if (e == null) return null;
      if (typeof e == "function")
        return e.$$typeof === me ? null : e.displayName || e.name || null;
      if (typeof e == "string") return e;
      switch (e) {
        case T:
          return "Fragment";
        case ae:
          return "Profiler";
        case ne:
          return "StrictMode";
        case ie:
          return "Suspense";
        case ce:
          return "SuspenseList";
        case de:
          return "Activity";
        case fe:
          return "ViewTransition";
      }
      if (typeof e == "object")
        switch (typeof e.tag == "number" && console.error(
          "Received an unexpected object in getComponentNameFromType(). This is likely a bug in React. Please file an issue."
        ), e.$$typeof) {
          case _:
            return "Portal";
          case se:
            return e.displayName || "Context";
          case oe:
            return (e._context.displayName || "Context") + ".Consumer";
          case le:
            var t = e.render;
            return e = e.displayName, e || (e = t.displayName || t.name || "", e = e !== "" ? "ForwardRef(" + e + ")" : "ForwardRef"), e;
          case ue:
            return t = e.displayName || null, t !== null ? t : n(e.type) || "Memo";
          case L:
            t = e._payload, e = e._init;
            try {
              return n(e(t));
            } catch {
            }
        }
      return null;
    }
    function l(e) {
      return "" + e;
    }
    function h(e) {
      try {
        l(e);
        var t = !1;
      } catch {
        t = !0;
      }
      if (t) {
        t = console;
        var s = t.error, i = typeof Symbol == "function" && Symbol.toStringTag && e[Symbol.toStringTag] || e.constructor.name || "Object";
        return s.call(
          t,
          "The provided key is an unsupported type %s. This value must be coerced to a string before using it here.",
          i
        ), l(e);
      }
    }
    function y(e) {
      if (e === T) return "<>";
      if (typeof e == "object" && e !== null && e.$$typeof === L)
        return "<...>";
      try {
        var t = n(e);
        return t ? "<" + t + ">" : "<...>";
      } catch {
        return "<...>";
      }
    }
    function a() {
      var e = Y.A;
      return e === null ? null : e.getOwner();
    }
    function d() {
      return Error("react-stack-top-frame");
    }
    function x(e) {
      if (J.call(e, "key")) {
        var t = Object.getOwnPropertyDescriptor(e, "key").get;
        if (t && t.isReactWarning) return !1;
      }
      return e.key !== void 0;
    }
    function v(e, t) {
      function s() {
        U || (U = !0, console.error(
          "%s: `key` is not a prop. Trying to access it will result in `undefined` being returned. If you need to access the same value within the child component, you should pass it as a different prop. (https://react.dev/link/special-props)",
          t
        ));
      }
      s.isReactWarning = !0, Object.defineProperty(e, "key", {
        get: s,
        configurable: !0
      });
    }
    function f() {
      var e = n(this.type);
      return B[e] || (B[e] = !0, console.error(
        "Accessing element.ref was removed in React 19. ref is now a regular prop. It will be removed from the JSX Element type in a future release."
      )), e = this.props.ref, e !== void 0 ? e : null;
    }
    function u(e, t, s, i, b, j) {
      var c = s.ref;
      return e = {
        $$typeof: m,
        type: e,
        key: t,
        props: s,
        _owner: i
      }, (c !== void 0 ? c : null) !== null ? Object.defineProperty(e, "ref", {
        enumerable: !1,
        get: f
      }) : Object.defineProperty(e, "ref", { enumerable: !1, value: null }), e._store = {}, Object.defineProperty(e._store, "validated", {
        configurable: !1,
        enumerable: !1,
        writable: !0,
        value: 0
      }), Object.defineProperty(e, "_debugInfo", {
        configurable: !1,
        enumerable: !1,
        writable: !0,
        value: null
      }), Object.defineProperty(e, "_debugStack", {
        configurable: !1,
        enumerable: !1,
        writable: !0,
        value: b
      }), Object.defineProperty(e, "_debugTask", {
        configurable: !1,
        enumerable: !1,
        writable: !0,
        value: j
      }), Object.freeze && (Object.freeze(e.props), Object.freeze(e)), e;
    }
    function p(e, t, s, i, b, j) {
      var c = t.children;
      if (c !== void 0)
        if (i)
          if (he(c)) {
            for (i = 0; i < c.length; i++)
              P(c[i]);
            Object.freeze && Object.freeze(c);
          } else
            console.error(
              "React.jsx: Static children should always be an array. You are likely explicitly calling React.jsxs or React.jsxDEV. Use the Babel transform instead."
            );
        else P(c);
      if (J.call(t, "key")) {
        c = n(e);
        var k = Object.keys(t).filter(function(pe) {
          return pe !== "key";
        });
        i = 0 < k.length ? "{key: someKey, " + k.join(": ..., ") + ": ...}" : "{key: someKey}", G[c + i] || (k = 0 < k.length ? "{" + k.join(": ..., ") + ": ...}" : "{}", console.error(
          `A props object containing a "key" prop is being spread into JSX:
  let props = %s;
  <%s {...props} />
React keys must be passed directly to JSX without using spread:
  let props = %s;
  <%s key={someKey} {...props} />`,
          i,
          c,
          k,
          c
        ), G[c + i] = !0);
      }
      if (c = null, s !== void 0 && (h(s), c = "" + s), x(t) && (h(t.key), c = "" + t.key), "key" in t) {
        s = {};
        for (var D in t)
          D !== "key" && (s[D] = t[D]);
      } else s = t;
      return c && v(
        s,
        typeof e == "function" ? e.displayName || e.name || "Unknown" : e
      ), u(
        e,
        c,
        s,
        a(),
        b,
        j
      );
    }
    function P(e) {
      A(e) ? e._store && (e._store.validated = 1) : typeof e == "object" && e !== null && e.$$typeof === L && (e._payload.status === "fulfilled" ? A(e._payload.value) && e._payload.value._store && (e._payload.value._store.validated = 1) : e._store && (e._store.validated = 1));
    }
    function A(e) {
      return typeof e == "object" && e !== null && e.$$typeof === m;
    }
    var o = ye, m = Symbol.for("react.transitional.element"), _ = Symbol.for("react.portal"), T = Symbol.for("react.fragment"), ne = Symbol.for("react.strict_mode"), ae = Symbol.for("react.profiler"), oe = Symbol.for("react.consumer"), se = Symbol.for("react.context"), le = Symbol.for("react.forward_ref"), ie = Symbol.for("react.suspense"), ce = Symbol.for("react.suspense_list"), ue = Symbol.for("react.memo"), L = Symbol.for("react.lazy"), de = Symbol.for("react.activity"), fe = Symbol.for("react.view_transition"), me = Symbol.for("react.client.reference"), Y = o.__CLIENT_INTERNALS_DO_NOT_USE_OR_WARN_USERS_THEY_CANNOT_UPGRADE, J = Object.prototype.hasOwnProperty, he = Array.isArray, F = console.createTask ? console.createTask : function() {
      return null;
    };
    o = {
      react_stack_bottom_frame: function(e) {
        return e();
      }
    };
    var U, B = {}, q = o.react_stack_bottom_frame.bind(
      o,
      d
    )(), V = F(y(d)), G = {};
    S.Fragment = T, S.jsx = function(e, t, s) {
      var i = 1e4 > Y.recentlyCreatedOwnerStacks++;
      if (i) {
        var b = Error.stackTraceLimit;
        Error.stackTraceLimit = 10;
        var j = Error("react-stack-top-frame");
        Error.stackTraceLimit = b;
      } else j = q;
      return p(
        e,
        t,
        s,
        !1,
        j,
        i ? F(y(e)) : V
      );
    }, S.jsxs = function(e, t, s) {
      var i = 1e4 > Y.recentlyCreatedOwnerStacks++;
      if (i) {
        var b = Error.stackTraceLimit;
        Error.stackTraceLimit = 10;
        var j = Error("react-stack-top-frame");
        Error.stackTraceLimit = b;
      } else j = q;
      return p(
        e,
        t,
        s,
        !0,
        j,
        i ? F(y(e)) : V
      );
    };
  })()), S;
}
var Z;
function we() {
  return Z || (Z = 1, process.env.NODE_ENV === "production" ? N.exports = Re() : N.exports = Se()), N.exports;
}
var r = we();
const { Title: Oe, Paragraph: Pe } = $;
function W(n) {
  return n == null ? "—" : typeof n == "number" ? n.toLocaleString("zh-CN") : String(n);
}
function Ae() {
  const [n, l] = g(null), [h, y] = g(null), [a, d] = g(!1), [x, v] = g(null), f = async () => {
    d(!0);
    try {
      const [u, p] = await Promise.all([
        z.overview().catch(() => null),
        z.info().catch(() => null)
      ]);
      l(u), y(p), v(null);
    } catch (u) {
      v((u == null ? void 0 : u.message) || String(u));
    } finally {
      d(!1);
    }
  };
  return M(() => {
    f();
    const u = setInterval(f, 15e3);
    return () => clearInterval(u);
  }, []), /* @__PURE__ */ r.jsxs("div", { children: [
    /* @__PURE__ */ r.jsxs(I, { style: { marginBottom: 16 }, children: [
      /* @__PURE__ */ r.jsx(Oe, { level: 4, style: { margin: 0 }, children: "缓存总览" }),
      /* @__PURE__ */ r.jsx(w, { icon: /* @__PURE__ */ r.jsx(Q, {}), onClick: f, loading: a, children: "刷新" }),
      /* @__PURE__ */ r.jsx($.Text, { type: "secondary", children: "15s 自动刷新" })
    ] }),
    /* @__PURE__ */ r.jsx(Pe, { type: "secondary", children: "/cache/overview + /cache/info：键数 / 内存 / 命中率。" }),
    x && /* @__PURE__ */ r.jsx(ee, { type: "error", showIcon: !0, style: { marginBottom: 16 }, message: "后端未连接", description: x }),
    a && !n && /* @__PURE__ */ r.jsx(be, {}),
    n && /* @__PURE__ */ r.jsxs(re, { gutter: 16, style: { marginBottom: 16 }, children: [
      /* @__PURE__ */ r.jsx(O, { span: 6, children: /* @__PURE__ */ r.jsx(E, { children: /* @__PURE__ */ r.jsx(C, { title: "键总数", value: W(n.keyCount ?? n.totalKeys) }) }) }),
      /* @__PURE__ */ r.jsx(O, { span: 6, children: /* @__PURE__ */ r.jsx(E, { children: /* @__PURE__ */ r.jsx(C, { title: "内存使用", value: W(n.usedMemory ?? n.memoryUsed) }) }) }),
      /* @__PURE__ */ r.jsx(O, { span: 6, children: /* @__PURE__ */ r.jsx(E, { children: /* @__PURE__ */ r.jsx(C, { title: "命中率", value: n.hitRate != null ? `${(n.hitRate * 100).toFixed(1)}%` : "—" }) }) }),
      /* @__PURE__ */ r.jsx(O, { span: 6, children: /* @__PURE__ */ r.jsx(E, { children: /* @__PURE__ */ r.jsx(C, { title: "过期键", value: W(n.expiredKeys) }) }) })
    ] }),
    h && /* @__PURE__ */ r.jsx(E, { title: "Redis INFO", children: /* @__PURE__ */ r.jsx(H, { column: 2, bordered: !0, size: "small", children: Object.entries(h).slice(0, 20).map(([u, p]) => /* @__PURE__ */ r.jsx(H.Item, { label: u, children: typeof p == "object" ? JSON.stringify(p) : String(p) }, u)) }) })
  ] });
}
const { Title: Ce, Paragraph: Ne, Text: Ie } = $;
function ze(n) {
  const l = { string: "blue", hash: "green", list: "purple", set: "orange", zset: "cyan" };
  return /* @__PURE__ */ r.jsx(te, { color: l[n] || "default", children: n || "—" });
}
function $e() {
  const [n, l] = g([]), [h, y] = g("*"), [a, d] = g(!1), [x, v] = g(null), [f, u] = g(null), p = async () => {
    d(!0);
    try {
      const o = await z.keys({ pattern: h }), m = Array.isArray(o) ? o : (o == null ? void 0 : o.keys) || [];
      l(m.map((_, T) => typeof _ == "string" ? { key: T, name: _ } : { key: T, ..._ })), v(null);
    } catch (o) {
      v((o == null ? void 0 : o.message) || String(o));
    } finally {
      d(!1);
    }
  };
  M(() => {
    p();
  }, []);
  const P = async (o) => {
    try {
      const m = await z.key({ key: o.name || o.key });
      u({ ...o, ...m });
    } catch (m) {
      u({ ...o, error: (m == null ? void 0 : m.message) || String(m) });
    }
  }, A = [
    {
      title: "键名",
      key: "name",
      ellipsis: !0,
      render: (o, m) => /* @__PURE__ */ r.jsx(Ie, { code: !0, style: { fontSize: 12 }, children: m.name || m.key })
    },
    { title: "类型", dataIndex: "type", key: "type", width: 100, render: ze },
    {
      title: "TTL",
      dataIndex: "ttl",
      key: "ttl",
      width: 100,
      render: (o) => o == null ? "—" : o < 0 ? "永不过期" : `${o}s`
    },
    {
      title: "操作",
      key: "op",
      width: 100,
      render: (o, m) => /* @__PURE__ */ r.jsx(w, { size: "small", onClick: () => P(m), children: "详情" })
    }
  ];
  return /* @__PURE__ */ r.jsxs("div", { children: [
    /* @__PURE__ */ r.jsxs(I, { style: { marginBottom: 16 }, wrap: !0, children: [
      /* @__PURE__ */ r.jsx(Ce, { level: 4, style: { margin: 0 }, children: "键浏览" }),
      /* @__PURE__ */ r.jsx(
        Ee,
        {
          value: h,
          onChange: (o) => y(o.target.value),
          onPressEnter: p,
          placeholder: "如 user:* ",
          style: { width: 240 },
          prefix: /* @__PURE__ */ r.jsx(xe, {})
        }
      ),
      /* @__PURE__ */ r.jsx(w, { type: "primary", onClick: p, loading: a, children: "搜索" }),
      /* @__PURE__ */ r.jsx(w, { icon: /* @__PURE__ */ r.jsx(Q, {}), onClick: p, children: "刷新" })
    ] }),
    /* @__PURE__ */ r.jsx(Ne, { type: "secondary", children: "Redis 键空间浏览（/cache/keys?pattern=）+ 单键详情（/cache/key）。" }),
    x && /* @__PURE__ */ r.jsx(ee, { type: "error", showIcon: !0, style: { marginBottom: 16 }, message: "后端未连接", description: x }),
    /* @__PURE__ */ r.jsx(E, { children: /* @__PURE__ */ r.jsx(
      Te,
      {
        rowKey: "key",
        dataSource: n,
        columns: A,
        loading: a,
        size: "small",
        pagination: { pageSize: 20 }
      }
    ) }),
    /* @__PURE__ */ r.jsx(
      ke,
      {
        title: f ? `键详情：${f.name || f.key}` : "",
        open: !!f,
        onCancel: () => u(null),
        footer: /* @__PURE__ */ r.jsx(w, { onClick: () => u(null), children: "关闭" }),
        width: 700,
        children: f && /* @__PURE__ */ r.jsx("pre", { style: {
          maxHeight: 400,
          overflow: "auto",
          background: "#1e1e1e",
          color: "#d4d4d4",
          padding: 12,
          fontSize: 12,
          borderRadius: 4
        }, children: f.error || (typeof f.value == "object" ? JSON.stringify(f.value, null, 2) : String(f.value ?? JSON.stringify(f, null, 2))) })
      }
    )
  ] });
}
const { Title: Le, Paragraph: Ye } = $;
function Fe() {
  const n = _e(), [l, h] = g(null);
  M(() => {
    const a = localStorage.getItem("userInfo");
    if (a)
      try {
        h(JSON.parse(a));
      } catch {
        h({ name: a });
      }
  }, []);
  const y = De.filter((a) => a.key !== "/z-cache/home");
  return /* @__PURE__ */ r.jsxs("div", { children: [
    /* @__PURE__ */ r.jsx(E, { style: { marginBottom: 16, background: "linear-gradient(135deg, #7c3aed 0%, #6d28d9 100%)", border: "none" }, children: /* @__PURE__ */ r.jsxs(I, { direction: "vertical", size: 4, style: { color: "#fff" }, children: [
      /* @__PURE__ */ r.jsxs(Le, { level: 3, style: { color: "#fff", margin: 0 }, children: [
        "欢迎",
        l != null && l.name ? `，${l.name}` : ""
      ] }),
      /* @__PURE__ */ r.jsx(Ye, { style: { color: "rgba(255,255,255,0.85)", margin: 0 }, children: "z-cache 缓存中心 管理台" }),
      (l == null ? void 0 : l.role) && /* @__PURE__ */ r.jsx(te, { style: { marginTop: 8, background: "rgba(255,255,255,0.2)", color: "#fff", border: "none" }, children: l.role })
    ] }) }),
    /* @__PURE__ */ r.jsx(re, { gutter: [16, 16], children: y.map((a) => /* @__PURE__ */ r.jsx(O, { xs: 24, sm: 12, md: 12, lg: 8, children: /* @__PURE__ */ r.jsx(E, { hoverable: !0, onClick: () => n(a.key), style: { borderTop: "3px solid #7c3aed" }, children: /* @__PURE__ */ r.jsxs(I, { align: "start", size: 12, children: [
      /* @__PURE__ */ r.jsx("div", { style: {
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
      }, children: a.icon }),
      /* @__PURE__ */ r.jsxs("div", { style: { minWidth: 0 }, children: [
        /* @__PURE__ */ r.jsx("div", { style: { fontSize: 15, fontWeight: 600, color: "#0f172a" }, children: a.label }),
        /* @__PURE__ */ r.jsx("div", { style: { fontSize: 12, color: "#94a3b8", marginTop: 2 }, children: a.key })
      ] })
    ] }) }) }, a.key)) })
  ] });
}
const De = [
  { key: "/z-cache/home", label: "首页", icon: /* @__PURE__ */ r.jsx(ve, {}) },
  { key: "/z-cache/overview", label: "总览", icon: /* @__PURE__ */ r.jsx(ge, {}) },
  { key: "/z-cache/keys", label: "键浏览", icon: /* @__PURE__ */ r.jsx(je, {}) }
], qe = [
  { path: "/z-cache/home", Component: Fe },
  { path: "/z-cache/overview", Component: Ae },
  { path: "/z-cache/keys", Component: $e }
];
export {
  $e as Keys,
  Ae as Overview,
  He as configureCache,
  De as menuItems,
  qe as routeTable
};
