import pe, { useState as j, useEffect as K } from "react";
import { ReloadOutlined as Z, SearchOutlined as ye, PieChartOutlined as he, KeyOutlined as ve } from "@ant-design/icons";
import { Typography as D, Space as Q, Button as S, Alert as ee, Spin as xe, Row as Ee, Col as P, Card as g, Statistic as C, Descriptions as V, Input as je, Table as _e, Modal as Te, Tag as Re } from "antd";
import { c as I } from "./api-DfrsadMq.js";
import { a as Je } from "./api-DfrsadMq.js";
var N = { exports: {} }, k = {};
/**
 * @license React
 * react-jsx-runtime.production.js
 *
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
var G;
function ge() {
  if (G) return k;
  G = 1;
  var a = Symbol.for("react.transitional.element"), v = Symbol.for("react.fragment");
  function p(x, c, u) {
    var y = null;
    if (u !== void 0 && (y = "" + u), c.key !== void 0 && (y = "" + c.key), "key" in c) {
      u = {};
      for (var h in c)
        h !== "key" && (u[h] = c[h]);
    } else u = c;
    return c = u.ref, {
      $$typeof: a,
      type: x,
      key: y,
      ref: c !== void 0 ? c : null,
      props: u
    };
  }
  return k.Fragment = v, k.jsx = p, k.jsxs = p, k;
}
var w = {};
/**
 * @license React
 * react-jsx-runtime.development.js
 *
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
var X;
function be() {
  return X || (X = 1, process.env.NODE_ENV !== "production" && (function() {
    function a(e) {
      if (e == null) return null;
      if (typeof e == "function")
        return e.$$typeof === fe ? null : e.displayName || e.name || null;
      if (typeof e == "string") return e;
      switch (e) {
        case T:
          return "Fragment";
        case te:
          return "Profiler";
        case re:
          return "StrictMode";
        case se:
          return "Suspense";
        case le:
          return "SuspenseList";
        case ce:
          return "Activity";
        case ue:
          return "ViewTransition";
      }
      if (typeof e == "object")
        switch (typeof e.tag == "number" && console.error(
          "Received an unexpected object in getComponentNameFromType(). This is likely a bug in React. Please file an issue."
        ), e.$$typeof) {
          case b:
            return "Portal";
          case ae:
            return e.displayName || "Context";
          case ne:
            return (e._context.displayName || "Context") + ".Consumer";
          case oe:
            var t = e.render;
            return e = e.displayName, e || (e = t.displayName || t.name || "", e = e !== "" ? "ForwardRef(" + e + ")" : "ForwardRef"), e;
          case ie:
            return t = e.displayName || null, t !== null ? t : a(e.type) || "Memo";
          case $:
            t = e._payload, e = e._init;
            try {
              return a(e(t));
            } catch {
            }
        }
      return null;
    }
    function v(e) {
      return "" + e;
    }
    function p(e) {
      try {
        v(e);
        var t = !1;
      } catch {
        t = !0;
      }
      if (t) {
        t = console;
        var o = t.error, s = typeof Symbol == "function" && Symbol.toStringTag && e[Symbol.toStringTag] || e.constructor.name || "Object";
        return o.call(
          t,
          "The provided key is an unsupported type %s. This value must be coerced to a string before using it here.",
          s
        ), v(e);
      }
    }
    function x(e) {
      if (e === T) return "<>";
      if (typeof e == "object" && e !== null && e.$$typeof === $)
        return "<...>";
      try {
        var t = a(e);
        return t ? "<" + t + ">" : "<...>";
      } catch {
        return "<...>";
      }
    }
    function c() {
      var e = L.A;
      return e === null ? null : e.getOwner();
    }
    function u() {
      return Error("react-stack-top-frame");
    }
    function y(e) {
      if (M.call(e, "key")) {
        var t = Object.getOwnPropertyDescriptor(e, "key").get;
        if (t && t.isReactWarning) return !1;
      }
      return e.key !== void 0;
    }
    function h(e, t) {
      function o() {
        W || (W = !0, console.error(
          "%s: `key` is not a prop. Trying to access it will result in `undefined` being returned. If you need to access the same value within the child component, you should pass it as a different prop. (https://react.dev/link/special-props)",
          t
        ));
      }
      o.isReactWarning = !0, Object.defineProperty(e, "key", {
        get: o,
        configurable: !0
      });
    }
    function f() {
      var e = a(this.type);
      return J[e] || (J[e] = !0, console.error(
        "Accessing element.ref was removed in React 19. ref is now a regular prop. It will be removed from the JSX Element type in a future release."
      )), e = this.props.ref, e !== void 0 ? e : null;
    }
    function i(e, t, o, s, _, E) {
      var l = o.ref;
      return e = {
        $$typeof: d,
        type: e,
        key: t,
        props: o,
        _owner: s
      }, (l !== void 0 ? l : null) !== null ? Object.defineProperty(e, "ref", {
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
        value: _
      }), Object.defineProperty(e, "_debugTask", {
        configurable: !1,
        enumerable: !1,
        writable: !0,
        value: E
      }), Object.freeze && (Object.freeze(e.props), Object.freeze(e)), e;
    }
    function m(e, t, o, s, _, E) {
      var l = t.children;
      if (l !== void 0)
        if (s)
          if (de(l)) {
            for (s = 0; s < l.length; s++)
              O(l[s]);
            Object.freeze && Object.freeze(l);
          } else
            console.error(
              "React.jsx: Static children should always be an array. You are likely explicitly calling React.jsxs or React.jsxDEV. Use the Babel transform instead."
            );
        else O(l);
      if (M.call(t, "key")) {
        l = a(e);
        var R = Object.keys(t).filter(function(me) {
          return me !== "key";
        });
        s = 0 < R.length ? "{key: someKey, " + R.join(": ..., ") + ": ...}" : "{key: someKey}", B[l + s] || (R = 0 < R.length ? "{" + R.join(": ..., ") + ": ...}" : "{}", console.error(
          `A props object containing a "key" prop is being spread into JSX:
  let props = %s;
  <%s {...props} />
React keys must be passed directly to JSX without using spread:
  let props = %s;
  <%s key={someKey} {...props} />`,
          s,
          l,
          R,
          l
        ), B[l + s] = !0);
      }
      if (l = null, o !== void 0 && (p(o), l = "" + o), y(t) && (p(t.key), l = "" + t.key), "key" in t) {
        o = {};
        for (var F in t)
          F !== "key" && (o[F] = t[F]);
      } else o = t;
      return l && h(
        o,
        typeof e == "function" ? e.displayName || e.name || "Unknown" : e
      ), i(
        e,
        l,
        o,
        c(),
        _,
        E
      );
    }
    function O(e) {
      A(e) ? e._store && (e._store.validated = 1) : typeof e == "object" && e !== null && e.$$typeof === $ && (e._payload.status === "fulfilled" ? A(e._payload.value) && e._payload.value._store && (e._payload.value._store.validated = 1) : e._store && (e._store.validated = 1));
    }
    function A(e) {
      return typeof e == "object" && e !== null && e.$$typeof === d;
    }
    var n = pe, d = Symbol.for("react.transitional.element"), b = Symbol.for("react.portal"), T = Symbol.for("react.fragment"), re = Symbol.for("react.strict_mode"), te = Symbol.for("react.profiler"), ne = Symbol.for("react.consumer"), ae = Symbol.for("react.context"), oe = Symbol.for("react.forward_ref"), se = Symbol.for("react.suspense"), le = Symbol.for("react.suspense_list"), ie = Symbol.for("react.memo"), $ = Symbol.for("react.lazy"), ce = Symbol.for("react.activity"), ue = Symbol.for("react.view_transition"), fe = Symbol.for("react.client.reference"), L = n.__CLIENT_INTERNALS_DO_NOT_USE_OR_WARN_USERS_THEY_CANNOT_UPGRADE, M = Object.prototype.hasOwnProperty, de = Array.isArray, Y = console.createTask ? console.createTask : function() {
      return null;
    };
    n = {
      react_stack_bottom_frame: function(e) {
        return e();
      }
    };
    var W, J = {}, U = n.react_stack_bottom_frame.bind(
      n,
      u
    )(), q = Y(x(u)), B = {};
    w.Fragment = T, w.jsx = function(e, t, o) {
      var s = 1e4 > L.recentlyCreatedOwnerStacks++;
      if (s) {
        var _ = Error.stackTraceLimit;
        Error.stackTraceLimit = 10;
        var E = Error("react-stack-top-frame");
        Error.stackTraceLimit = _;
      } else E = U;
      return m(
        e,
        t,
        o,
        !1,
        E,
        s ? Y(x(e)) : q
      );
    }, w.jsxs = function(e, t, o) {
      var s = 1e4 > L.recentlyCreatedOwnerStacks++;
      if (s) {
        var _ = Error.stackTraceLimit;
        Error.stackTraceLimit = 10;
        var E = Error("react-stack-top-frame");
        Error.stackTraceLimit = _;
      } else E = U;
      return m(
        e,
        t,
        o,
        !0,
        E,
        s ? Y(x(e)) : q
      );
    };
  })()), w;
}
var H;
function ke() {
  return H || (H = 1, process.env.NODE_ENV === "production" ? N.exports = ge() : N.exports = be()), N.exports;
}
var r = ke();
const { Title: we, Paragraph: Se } = D;
function z(a) {
  return a == null ? "—" : typeof a == "number" ? a.toLocaleString("zh-CN") : String(a);
}
function Oe() {
  const [a, v] = j(null), [p, x] = j(null), [c, u] = j(!1), [y, h] = j(null), f = async () => {
    u(!0);
    try {
      const [i, m] = await Promise.all([
        I.overview().catch(() => null),
        I.info().catch(() => null)
      ]);
      v(i), x(m), h(null);
    } catch (i) {
      h((i == null ? void 0 : i.message) || String(i));
    } finally {
      u(!1);
    }
  };
  return K(() => {
    f();
    const i = setInterval(f, 15e3);
    return () => clearInterval(i);
  }, []), /* @__PURE__ */ r.jsxs("div", { children: [
    /* @__PURE__ */ r.jsxs(Q, { style: { marginBottom: 16 }, children: [
      /* @__PURE__ */ r.jsx(we, { level: 4, style: { margin: 0 }, children: "缓存总览" }),
      /* @__PURE__ */ r.jsx(S, { icon: /* @__PURE__ */ r.jsx(Z, {}), onClick: f, loading: c, children: "刷新" }),
      /* @__PURE__ */ r.jsx(D.Text, { type: "secondary", children: "15s 自动刷新" })
    ] }),
    /* @__PURE__ */ r.jsx(Se, { type: "secondary", children: "/cache/overview + /cache/info：键数 / 内存 / 命中率。" }),
    y && /* @__PURE__ */ r.jsx(ee, { type: "error", showIcon: !0, style: { marginBottom: 16 }, message: "后端未连接", description: y }),
    c && !a && /* @__PURE__ */ r.jsx(xe, {}),
    a && /* @__PURE__ */ r.jsxs(Ee, { gutter: 16, style: { marginBottom: 16 }, children: [
      /* @__PURE__ */ r.jsx(P, { span: 6, children: /* @__PURE__ */ r.jsx(g, { children: /* @__PURE__ */ r.jsx(C, { title: "键总数", value: z(a.keyCount ?? a.totalKeys) }) }) }),
      /* @__PURE__ */ r.jsx(P, { span: 6, children: /* @__PURE__ */ r.jsx(g, { children: /* @__PURE__ */ r.jsx(C, { title: "内存使用", value: z(a.usedMemory ?? a.memoryUsed) }) }) }),
      /* @__PURE__ */ r.jsx(P, { span: 6, children: /* @__PURE__ */ r.jsx(g, { children: /* @__PURE__ */ r.jsx(C, { title: "命中率", value: a.hitRate != null ? `${(a.hitRate * 100).toFixed(1)}%` : "—" }) }) }),
      /* @__PURE__ */ r.jsx(P, { span: 6, children: /* @__PURE__ */ r.jsx(g, { children: /* @__PURE__ */ r.jsx(C, { title: "过期键", value: z(a.expiredKeys) }) }) })
    ] }),
    p && /* @__PURE__ */ r.jsx(g, { title: "Redis INFO", children: /* @__PURE__ */ r.jsx(V, { column: 2, bordered: !0, size: "small", children: Object.entries(p).slice(0, 20).map(([i, m]) => /* @__PURE__ */ r.jsx(V.Item, { label: i, children: typeof m == "object" ? JSON.stringify(m) : String(m) }, i)) }) })
  ] });
}
const { Title: Ae, Paragraph: Pe, Text: Ce } = D;
function Ne(a) {
  const v = { string: "blue", hash: "green", list: "purple", set: "orange", zset: "cyan" };
  return /* @__PURE__ */ r.jsx(Re, { color: v[a] || "default", children: a || "—" });
}
function Ie() {
  const [a, v] = j([]), [p, x] = j("*"), [c, u] = j(!1), [y, h] = j(null), [f, i] = j(null), m = async () => {
    u(!0);
    try {
      const n = await I.keys({ pattern: p }), d = Array.isArray(n) ? n : (n == null ? void 0 : n.keys) || [];
      v(d.map((b, T) => typeof b == "string" ? { key: T, name: b } : { key: T, ...b })), h(null);
    } catch (n) {
      h((n == null ? void 0 : n.message) || String(n));
    } finally {
      u(!1);
    }
  };
  K(() => {
    m();
  }, []);
  const O = async (n) => {
    try {
      const d = await I.key({ key: n.name || n.key });
      i({ ...n, ...d });
    } catch (d) {
      i({ ...n, error: (d == null ? void 0 : d.message) || String(d) });
    }
  }, A = [
    {
      title: "键名",
      key: "name",
      ellipsis: !0,
      render: (n, d) => /* @__PURE__ */ r.jsx(Ce, { code: !0, style: { fontSize: 12 }, children: d.name || d.key })
    },
    { title: "类型", dataIndex: "type", key: "type", width: 100, render: Ne },
    {
      title: "TTL",
      dataIndex: "ttl",
      key: "ttl",
      width: 100,
      render: (n) => n == null ? "—" : n < 0 ? "永不过期" : `${n}s`
    },
    {
      title: "操作",
      key: "op",
      width: 100,
      render: (n, d) => /* @__PURE__ */ r.jsx(S, { size: "small", onClick: () => O(d), children: "详情" })
    }
  ];
  return /* @__PURE__ */ r.jsxs("div", { children: [
    /* @__PURE__ */ r.jsxs(Q, { style: { marginBottom: 16 }, wrap: !0, children: [
      /* @__PURE__ */ r.jsx(Ae, { level: 4, style: { margin: 0 }, children: "键浏览" }),
      /* @__PURE__ */ r.jsx(
        je,
        {
          value: p,
          onChange: (n) => x(n.target.value),
          onPressEnter: m,
          placeholder: "如 user:* ",
          style: { width: 240 },
          prefix: /* @__PURE__ */ r.jsx(ye, {})
        }
      ),
      /* @__PURE__ */ r.jsx(S, { type: "primary", onClick: m, loading: c, children: "搜索" }),
      /* @__PURE__ */ r.jsx(S, { icon: /* @__PURE__ */ r.jsx(Z, {}), onClick: m, children: "刷新" })
    ] }),
    /* @__PURE__ */ r.jsx(Pe, { type: "secondary", children: "Redis 键空间浏览（/cache/keys?pattern=）+ 单键详情（/cache/key）。" }),
    y && /* @__PURE__ */ r.jsx(ee, { type: "error", showIcon: !0, style: { marginBottom: 16 }, message: "后端未连接", description: y }),
    /* @__PURE__ */ r.jsx(g, { children: /* @__PURE__ */ r.jsx(
      _e,
      {
        rowKey: "key",
        dataSource: a,
        columns: A,
        loading: c,
        size: "small",
        pagination: { pageSize: 20 }
      }
    ) }),
    /* @__PURE__ */ r.jsx(
      Te,
      {
        title: f ? `键详情：${f.name || f.key}` : "",
        open: !!f,
        onCancel: () => i(null),
        footer: /* @__PURE__ */ r.jsx(S, { onClick: () => i(null), children: "关闭" }),
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
const ze = [
  { key: "/overview", icon: /* @__PURE__ */ r.jsx(he, {}), label: "总览" },
  { key: "/keys", icon: /* @__PURE__ */ r.jsx(ve, {}), label: "键浏览" }
], De = [
  { path: "overview", Component: Oe },
  { path: "keys", Component: Ie }
];
export {
  Ie as Keys,
  Oe as Overview,
  Je as configureCache,
  ze as menuItems,
  De as routeTable
};
