import Ie, { useState as j, useCallback as X, useEffect as ce, useMemo as Se } from "react";
import { useNavigate as ze, Routes as Pe, Route as W, Navigate as Ne } from "react-router-dom";
import { Breadcrumb as Oe, Button as ae, Alert as g, Row as q, Col as A, Card as w, Statistic as F, Descriptions as J, Tag as l, Table as O, Tooltip as le, Space as oe, Input as Be, Select as Ce, InputNumber as $e, Radio as te, Empty as ne, Badge as Le, Drawer as Me } from "antd";
import { ReloadOutlined as de, SearchOutlined as De } from "@ant-design/icons";
import D from "@yuku123/z-frontend-common";
const B = {
  instance: () => D.get("/cache/__instance"),
  overview: () => D.get("/cache/overview"),
  keys: (r) => D.get("/cache/keys", { params: r }),
  key: (r) => D.get("/cache/key", { params: r }),
  info: () => D.get("/cache/info"),
  hotkeys: (r) => D.get("/cache/hotkeys", { params: r }),
  commandCatalog: () => D.get("/cache/command-catalog")
}, Fe = {
  THIS_JVM_IN_PROCESS: { color: "success", text: "本 JVM 进程内对象（不可能跨进程）" },
  THIS_JVM_EMBEDDED: { color: "success", text: "本 JVM 内嵌 server 应答" },
  EXTERNAL_PROCESS: {
    color: "error",
    text: "外部进程应答 —— 6379 的属主不是本 JVM，这一路读到的是别的进程的缓存"
  },
  NO_ANSWER: { color: "warning", text: "没有任何 server 在应答" },
  UNKNOWN: { color: "warning", text: "连上了，但无法判定连的是谁" }
};
function L(r) {
  return Fe[r] || { color: "default", text: r || "-" };
}
function Y(r) {
  var s;
  const n = (s = r == null ? void 0 : r.response) == null ? void 0 : s.data;
  if (n && typeof n == "object") {
    const h = n.message || n.error, o = n.hint ? `｜${n.hint}` : "", p = n.source ? ` (${n.source})` : "";
    if (h) return `${h}${p}${o}`;
  }
  return typeof n == "string" && n.trim() ? n.slice(0, 240) : (r == null ? void 0 : r.message) || String(r);
}
function ar(r) {
  var h, o, p;
  const n = (h = r == null ? void 0 : r.response) == null ? void 0 : h.status, s = (p = (o = r == null ? void 0 : r.response) == null ? void 0 : o.data) == null ? void 0 : p.source;
  return n === 503 && s === "z-opc-cache-read-api";
}
function ie(r) {
  if (r == null) return "-";
  const n = Number(r);
  return isFinite(n) ? n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(1)} KiB` : n < 1024 * 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MiB` : `${(n / 1024 / 1024 / 1024).toFixed(2)} GiB` : "-";
}
function ve(r) {
  if (r == null) return "-";
  const n = Number(r);
  return n === -2 ? "不存在（-2）" : n === -1 ? "常驻（无过期）" : n < 10 ? `${n} 秒` : n < 600 ? `${Math.floor(n / 60)} 分 ${n % 60} 秒` : `${Math.floor(n / 86400)} 天 ${Math.floor(n % 86400 / 3600)} 时`;
}
function Je(r) {
  if (r == null) return "-";
  const n = Number(r), s = Math.floor(n / 86400), h = Math.floor(n % 86400 / 3600), o = Math.floor(n % 3600 / 60);
  return s > 0 ? `${s} 天 ${h} 时 ${o} 分` : h > 0 ? `${h} 时 ${o} 分 ${n % 60} 秒` : `${o} 分 ${n % 60} 秒`;
}
function Ue(r) {
  if (r == null) return null;
  if (typeof r == "string") return r;
  if (typeof r == "number" || typeof r == "boolean") return String(r);
  if (Array.isArray(r)) return r.map(Ue).filter((n) => n != null).join(", ");
  if (typeof r.error == "string") return `命令失败：${r.command ?? "?"} → ${r.error}`;
  try {
    return JSON.stringify(r);
  } catch {
    return String(r);
  }
}
var se = { exports: {} }, K = {};
/**
 * @license React
 * react-jsx-runtime.production.js
 *
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
var ke;
function Ve() {
  if (ke) return K;
  ke = 1;
  var r = Symbol.for("react.transitional.element"), n = Symbol.for("react.fragment");
  function s(h, o, p) {
    var b = null;
    if (p !== void 0 && (b = "" + p), o.key !== void 0 && (b = "" + o.key), "key" in o) {
      p = {};
      for (var m in o)
        m !== "key" && (p[m] = o[m]);
    } else p = o;
    return o = p.ref, {
      $$typeof: r,
      type: h,
      key: b,
      ref: o !== void 0 ? o : null,
      props: p
    };
  }
  return K.Fragment = n, K.jsx = s, K.jsxs = s, K;
}
var Z = {};
/**
 * @license React
 * react-jsx-runtime.development.js
 *
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
var Ee;
function He() {
  return Ee || (Ee = 1, process.env.NODE_ENV !== "production" && (function() {
    function r(t) {
      if (t == null) return null;
      if (typeof t == "function")
        return t.$$typeof === re ? null : t.displayName || t.name || null;
      if (typeof t == "string") return t;
      switch (t) {
        case I:
          return "Fragment";
        case y:
          return "Profiler";
        case M:
          return "StrictMode";
        case G:
          return "Suspense";
        case ee:
          return "SuspenseList";
        case he:
          return "Activity";
        case xe:
          return "ViewTransition";
      }
      if (typeof t == "object")
        switch (typeof t.tag == "number" && console.error(
          "Received an unexpected object in getComponentNameFromType(). This is likely a bug in React. Please file an issue."
        ), t.$$typeof) {
          case R:
            return "Portal";
          case z:
            return t.displayName || "Context";
          case S:
            return (t._context.displayName || "Context") + ".Consumer";
          case Q:
            var d = t.render;
            return t = t.displayName, t || (t = d.displayName || d.name || "", t = t !== "" ? "ForwardRef(" + t + ")" : "ForwardRef"), t;
          case U:
            return d = t.displayName || null, d !== null ? d : r(t.type) || "Memo";
          case V:
            d = t._payload, t = t._init;
            try {
              return r(t(d));
            } catch {
            }
        }
      return null;
    }
    function n(t) {
      return "" + t;
    }
    function s(t) {
      try {
        n(t);
        var d = !1;
      } catch {
        d = !0;
      }
      if (d) {
        d = console;
        var f = d.error, v = typeof Symbol == "function" && Symbol.toStringTag && t[Symbol.toStringTag] || t.constructor.name || "Object";
        return f.call(
          d,
          "The provided key is an unsupported type %s. This value must be coerced to a string before using it here.",
          v
        ), n(t);
      }
    }
    function h(t) {
      if (t === I) return "<>";
      if (typeof t == "object" && t !== null && t.$$typeof === V)
        return "<...>";
      try {
        var d = r(t);
        return d ? "<" + d + ">" : "<...>";
      } catch {
        return "<...>";
      }
    }
    function o() {
      var t = i.A;
      return t === null ? null : t.getOwner();
    }
    function p() {
      return Error("react-stack-top-frame");
    }
    function b(t) {
      if (u.call(t, "key")) {
        var d = Object.getOwnPropertyDescriptor(t, "key").get;
        if (d && d.isReactWarning) return !1;
      }
      return t.key !== void 0;
    }
    function m(t, d) {
      function f() {
        je || (je = !0, console.error(
          "%s: `key` is not a prop. Trying to access it will result in `undefined` being returned. If you need to access the same value within the child component, you should pass it as a different prop. (https://react.dev/link/special-props)",
          d
        ));
      }
      f.isReactWarning = !0, Object.defineProperty(t, "key", {
        get: f,
        configurable: !0
      });
    }
    function E() {
      var t = r(this.type);
      return be[t] || (be[t] = !0, console.error(
        "Accessing element.ref was removed in React 19. ref is now a regular prop. It will be removed from the JSX Element type in a future release."
      )), t = this.props.ref, t !== void 0 ? t : null;
    }
    function c(t, d, f, v, $, P) {
      var k = f.ref;
      return t = {
        $$typeof: _,
        type: t,
        key: d,
        props: f,
        _owner: v
      }, (k !== void 0 ? k : null) !== null ? Object.defineProperty(t, "ref", {
        enumerable: !1,
        get: E
      }) : Object.defineProperty(t, "ref", { enumerable: !1, value: null }), t._store = {}, Object.defineProperty(t._store, "validated", {
        configurable: !1,
        enumerable: !1,
        writable: !0,
        value: 0
      }), Object.defineProperty(t, "_debugInfo", {
        configurable: !1,
        enumerable: !1,
        writable: !0,
        value: null
      }), Object.defineProperty(t, "_debugStack", {
        configurable: !1,
        enumerable: !1,
        writable: !0,
        value: $
      }), Object.defineProperty(t, "_debugTask", {
        configurable: !1,
        enumerable: !1,
        writable: !0,
        value: P
      }), Object.freeze && (Object.freeze(t.props), Object.freeze(t)), t;
    }
    function x(t, d, f, v, $, P) {
      var k = d.children;
      if (k !== void 0)
        if (v)
          if (_e(k)) {
            for (v = 0; v < k.length; v++)
              T(k[v]);
            Object.freeze && Object.freeze(k);
          } else
            console.error(
              "React.jsx: Static children should always be an array. You are likely explicitly calling React.jsxs or React.jsxDEV. Use the Babel transform instead."
            );
        else T(k);
      if (u.call(d, "key")) {
        k = r(t);
        var H = Object.keys(d).filter(function(Ae) {
          return Ae !== "key";
        });
        v = 0 < H.length ? "{key: someKey, " + H.join(": ..., ") + ": ...}" : "{key: someKey}", Te[k + v] || (H = 0 < H.length ? "{" + H.join(": ..., ") + ": ...}" : "{}", console.error(
          `A props object containing a "key" prop is being spread into JSX:
  let props = %s;
  <%s {...props} />
React keys must be passed directly to JSX without using spread:
  let props = %s;
  <%s key={someKey} {...props} />`,
          v,
          k,
          H,
          k
        ), Te[k + v] = !0);
      }
      if (k = null, f !== void 0 && (s(f), k = "" + f), b(d) && (s(d.key), k = "" + d.key), "key" in d) {
        f = {};
        for (var me in d)
          me !== "key" && (f[me] = d[me]);
      } else f = d;
      return k && m(
        f,
        typeof t == "function" ? t.displayName || t.name || "Unknown" : t
      ), c(
        t,
        k,
        f,
        o(),
        $,
        P
      );
    }
    function T(t) {
      a(t) ? t._store && (t._store.validated = 1) : typeof t == "object" && t !== null && t.$$typeof === V && (t._payload.status === "fulfilled" ? a(t._payload.value) && t._payload.value._store && (t._payload.value._store.validated = 1) : t._store && (t._store.validated = 1));
    }
    function a(t) {
      return typeof t == "object" && t !== null && t.$$typeof === _;
    }
    var C = Ie, _ = Symbol.for("react.transitional.element"), R = Symbol.for("react.portal"), I = Symbol.for("react.fragment"), M = Symbol.for("react.strict_mode"), y = Symbol.for("react.profiler"), S = Symbol.for("react.consumer"), z = Symbol.for("react.context"), Q = Symbol.for("react.forward_ref"), G = Symbol.for("react.suspense"), ee = Symbol.for("react.suspense_list"), U = Symbol.for("react.memo"), V = Symbol.for("react.lazy"), he = Symbol.for("react.activity"), xe = Symbol.for("react.view_transition"), re = Symbol.for("react.client.reference"), i = C.__CLIENT_INTERNALS_DO_NOT_USE_OR_WARN_USERS_THEY_CANNOT_UPGRADE, u = Object.prototype.hasOwnProperty, _e = Array.isArray, ye = console.createTask ? console.createTask : function() {
      return null;
    };
    C = {
      react_stack_bottom_frame: function(t) {
        return t();
      }
    };
    var je, be = {}, fe = C.react_stack_bottom_frame.bind(
      C,
      p
    )(), ge = ye(h(p)), Te = {};
    Z.Fragment = I, Z.jsx = function(t, d, f) {
      var v = 1e4 > i.recentlyCreatedOwnerStacks++;
      if (v) {
        var $ = Error.stackTraceLimit;
        Error.stackTraceLimit = 10;
        var P = Error("react-stack-top-frame");
        Error.stackTraceLimit = $;
      } else P = fe;
      return x(
        t,
        d,
        f,
        !1,
        P,
        v ? ye(h(t)) : ge
      );
    }, Z.jsxs = function(t, d, f) {
      var v = 1e4 > i.recentlyCreatedOwnerStacks++;
      if (v) {
        var $ = Error.stackTraceLimit;
        Error.stackTraceLimit = 10;
        var P = Error("react-stack-top-frame");
        Error.stackTraceLimit = $;
      } else P = fe;
      return x(
        t,
        d,
        f,
        !0,
        P,
        v ? ye(h(t)) : ge
      );
    };
  })()), Z;
}
var Re;
function Ye() {
  return Re || (Re = 1, process.env.NODE_ENV === "production" ? se.exports = Ve() : se.exports = He()), se.exports;
}
var e = Ye();
function ue({
  title: r,
  subtitle: n,
  breadcrumb: s,
  extra: h,
  back: o,
  onBack: p
}) {
  const b = ze();
  return /* @__PURE__ */ e.jsx("div", { style: {
    marginBottom: 12,
    paddingBottom: 10,
    borderBottom: "1px solid #f1f5f9"
  }, children: /* @__PURE__ */ e.jsxs("div", { style: {
    display: "flex",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 12,
    flexWrap: "wrap"
  }, children: [
    /* @__PURE__ */ e.jsxs("div", { style: {
      display: "flex",
      alignItems: "center",
      gap: 10,
      minWidth: 0,
      flexWrap: "wrap"
    }, children: [
      o && /* @__PURE__ */ e.jsx(
        "button",
        {
          onClick: () => {
            p ? p() : b(-1);
          },
          "aria-label": "返回上一页",
          style: {
            width: 28,
            height: 28,
            borderRadius: 6,
            border: "1px solid #e5e7eb",
            background: "#ffffff",
            color: "#6b7280",
            cursor: "pointer",
            display: "inline-flex",
            alignItems: "center",
            justifyContent: "center",
            fontSize: 13,
            transition: "all 150ms ease",
            flexShrink: 0
          },
          onMouseEnter: (m) => {
            m.currentTarget.style.background = "#f8fafc", m.currentTarget.style.color = "#111827", m.currentTarget.style.borderColor = "#7c3aed";
          },
          onMouseLeave: (m) => {
            m.currentTarget.style.background = "#ffffff", m.currentTarget.style.color = "#6b7280", m.currentTarget.style.borderColor = "#e5e7eb";
          },
          children: "←"
        }
      ),
      s && s.length > 0 ? /* @__PURE__ */ e.jsx(
        Oe,
        {
          items: s.map((m, E) => {
            const c = E === s.length - 1;
            return {
              title: m.href && !c ? /* @__PURE__ */ e.jsx("a", { onClick: (x) => {
                x.preventDefault(), b(m.href);
              }, children: m.label }) : /* @__PURE__ */ e.jsx("span", { style: c ? {
                fontWeight: 600,
                color: "#111827"
              } : void 0, children: m.label })
            };
          })
        }
      ) : r && /* @__PURE__ */ e.jsx("span", { role: "heading", "aria-level": 1, style: {
        fontSize: 15,
        fontWeight: 600,
        color: "#111827",
        lineHeight: 1.4
      }, children: r }),
      n && /* @__PURE__ */ e.jsx("span", { title: n, style: {
        fontSize: 12,
        color: "#94a3b8",
        lineHeight: 1.4,
        whiteSpace: "nowrap",
        overflow: "hidden",
        textOverflow: "ellipsis",
        maxWidth: 360
      }, children: n })
    ] }),
    h && /* @__PURE__ */ e.jsx("div", { style: {
      display: "flex",
      gap: 8,
      flexShrink: 0
    }, children: h })
  ] }) });
}
const Ge = [
  { title: "库", dataIndex: "db", key: "db", width: 70, render: (r) => `db${r}` },
  {
    title: "key 数",
    dataIndex: "keys",
    key: "keys",
    width: 120,
    render: (r) => r > 0 ? /* @__PURE__ */ e.jsx(l, { color: "blue", children: r }) : /* @__PURE__ */ e.jsx("span", { style: { color: "#94a3b8" }, children: "0" })
  }
];
function We() {
  const [r, n] = j(null), [s, h] = j(null), [o, p] = j(null), [b, m] = j(!1), E = X(async () => {
    m(!0);
    try {
      const [C, _] = await Promise.all([B.overview(), B.instance()]);
      n(C), h(_), p(null);
    } catch (C) {
      n(null), p(C);
    } finally {
      m(!1);
    }
  }, []);
  ce(() => {
    E();
  }, [E]);
  const c = (r == null ? void 0 : r.embedded) ?? {}, x = (r == null ? void 0 : r.client) ?? {}, T = !!(r != null && r.foreignListenerSuspected || s != null && s.foreignListenerSuspected), a = !!(r != null && r.authorityDiverges);
  return /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsx(
      ue,
      {
        title: "缓存总览",
        subtitle: "双路对账：进程内 MemoryStore（路 A） vs zCacheClient 走 RESP（路 B）— GET /api/cache/overview",
        extra: /* @__PURE__ */ e.jsx(ae, { icon: /* @__PURE__ */ e.jsx(de, {}), loading: b, onClick: E, children: "刷新" })
      }
    ),
    o && /* @__PURE__ */ e.jsx(
      g,
      {
        type: "error",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: `读不到总览：${Y(o)}`,
        description: "overview 与 __instance 一起失败 ⇒ 是 z-opc 侧路由/鉴权问题；只有 overview 失败而「实例与三态」能开，则是取数那一层的问题。"
      }
    ),
    T && /* @__PURE__ */ e.jsx(
      g,
      {
        type: "error",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: "下面 client 一栏的数字来自外部进程，不是本 JVM",
        description: "本 JVM 的 RedisServer 没 bind 上配置端口，而该端口 TCP 可连 ⇒ 应答者是别的进程（实测是 5 天前的另一个 z-opc boot jar）。embedded 一栏才是本 JVM 的真相，而它现在必然是空的。"
      }
    ),
    /* @__PURE__ */ e.jsxs(q, { gutter: 16, children: [
      /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsxs(
        w,
        {
          title: /* @__PURE__ */ e.jsxs("span", { children: [
            "路 A · 进程内 MemoryStore ",
            /* @__PURE__ */ e.jsx(l, { color: "blue", children: "不可能跨进程" })
          ] }),
          size: "small",
          loading: b,
          children: [
            /* @__PURE__ */ e.jsxs(q, { gutter: 8, children: [
              /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsx(F, { title: "dbsize（本 JVM 全部库）", value: c.dbsizeTotal ?? "-" }) }),
              /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsx(
                F,
                {
                  title: "内嵌 server 在跑",
                  value: c.running ? "是" : "否",
                  valueStyle: { color: c.running ? "#16a34a" : "#dc2626", fontSize: 20 }
                }
              ) })
            ] }),
            /* @__PURE__ */ e.jsx(
              J,
              {
                size: "small",
                column: 1,
                style: { marginTop: 12 },
                items: [
                  { key: "reflected", label: "反射取到 RedisServer", children: c.serverReflected ? /* @__PURE__ */ e.jsx(l, { color: "success", children: "是" }) : /* @__PURE__ */ e.jsx(l, { color: "warning", children: "否" }) },
                  { key: "reflectErr", label: "反射失败原因", children: c.reflectError ? /* @__PURE__ */ e.jsx("code", { children: c.reflectError }) : "-" },
                  { key: "port", label: "内嵌 RedisServer.getPort()", children: c.port ?? "-" },
                  { key: "dbCount", label: "库数量（DEFAULT_DB_COUNT）", children: c.dbCount ?? "-" },
                  { key: "hits", label: "命中 / 未命中", children: `${c.hits ?? "-"} / ${c.misses ?? "-"}` },
                  { key: "hitRate", label: "命中率", children: c.hitRate != null ? `${(c.hitRate * 100).toFixed(2)}%` : "-" },
                  { key: "evict", label: "被淘汰 key", children: c.evictions ?? "-" },
                  { key: "cmds", label: "累计命令数", children: c.totalCommands ?? "-" },
                  { key: "conns", label: "累计连接 / 当前连接", children: `${c.totalConnections ?? "-"} / ${c.connectedClients ?? "-"}` },
                  { key: "max", label: "maxEntries（0=不限）", children: c.maxEntries ?? "-" },
                  {
                    key: "uptime",
                    label: "store 存在时长",
                    children: /* @__PURE__ */ e.jsxs("span", { children: [
                      Je(c.storeUptimeSeconds),
                      /* @__PURE__ */ e.jsx("span", { style: { color: "#b45309" }, children: "（自构造起算，不代表在线时长）" })
                    ] })
                  },
                  { key: "unavailable", label: "不可用原因", children: c.unavailableReason ?? /* @__PURE__ */ e.jsx(l, { color: "success", children: "可用" }) }
                ]
              }
            )
          ]
        }
      ) }),
      /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsxs(
        w,
        {
          title: /* @__PURE__ */ e.jsxs("span", { children: [
            "路 B · zCacheClient（RESP DBSIZE） ",
            /* @__PURE__ */ e.jsx(l, { color: "purple", children: "业务看到的数" })
          ] }),
          size: "small",
          loading: b,
          children: [
            /* @__PURE__ */ e.jsxs(q, { gutter: 8, children: [
              /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsx(F, { title: "dbsize", value: x.dbsize ?? "-" }) }),
              /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsx(
                F,
                {
                  title: "RESP 已连接",
                  value: x.transportConnected ? "是" : "否",
                  valueStyle: { color: x.transportConnected ? "#16a34a" : "#d97706", fontSize: 20 }
                }
              ) })
            ] }),
            /* @__PURE__ */ e.jsx(
              J,
              {
                size: "small",
                column: 1,
                style: { marginTop: 12 },
                items: [
                  { key: "present", label: "bean 存在", children: x.present ? /* @__PURE__ */ e.jsx(l, { color: "success", children: "是" }) : /* @__PURE__ */ e.jsx(l, { color: "warning", children: "否" }) },
                  { key: "target", label: "连接目标 host:port/db", children: x.target ? /* @__PURE__ */ e.jsx("code", { children: x.target }) : "-" },
                  {
                    key: "authority",
                    label: "应答者归属",
                    children: /* @__PURE__ */ e.jsxs("span", { children: [
                      /* @__PURE__ */ e.jsx(l, { color: L(x.authority).color, children: x.authority ?? "-" }),
                      /* @__PURE__ */ e.jsx("span", { style: { fontSize: 12, color: "#64748b" }, children: L(x.authority).text })
                    ] })
                  },
                  { key: "dbsizeErr", label: "DBSIZE 报错", children: x.dbsizeError ? /* @__PURE__ */ e.jsx("code", { children: x.dbsizeError }) : "-" },
                  { key: "probeErr", label: "连接探测报错", children: x.probeError ? /* @__PURE__ */ e.jsx("code", { children: x.probeError }) : "-" }
                ]
              }
            )
          ]
        }
      ) })
    ] }),
    /* @__PURE__ */ e.jsx(
      w,
      {
        style: { marginTop: 16 },
        title: "双路对账结论（这一格就是本卡的保真探针）",
        extra: a ? /* @__PURE__ */ e.jsx(l, { color: "error", children: "authorityDiverges = true" }) : /* @__PURE__ */ e.jsx(l, { color: "success", children: "authorityDiverges = false" }),
        children: /* @__PURE__ */ e.jsx(
          g,
          {
            showIcon: !0,
            type: a ? "error" : c.running ? "success" : "warning",
            message: a ? `路 A = ${c.dbsizeTotal ?? "-"}，路 B = ${x.dbsize ?? "-"} —— 两路不一致` : c.running ? `两路一致（都是 ${c.dbsizeTotal ?? "-"}）：内嵌 server 真在跑，且业务连的就是它` : '路 A 无数据可比（内嵌没 bind），所以现在还不能下"一致/不一致"的结论',
            description: /* @__PURE__ */ e.jsxs("span", { children: [
              "判定式：",
              /* @__PURE__ */ e.jsx("code", { children: "路 A 可用(running) 且 路 B 有应答 且 A.dbsize != B.dbsize" }),
              "。 内嵌 bind 成功之后，A 与 B 是同一个 store 的两种数法，",
              /* @__PURE__ */ e.jsx("b", { children: "必须恒等" }),
              "； 一旦不等，就等于证明这个 controller 在自造数据。 当前不等的原因是 B1（6379 被另一个进程占着）而不是数据造假 —— 区分方式就是 ",
              /* @__PURE__ */ e.jsx("code", { children: "foreignListenerSuspected" }),
              " 这一格。"
            ] })
          }
        )
      }
    ),
    /* @__PURE__ */ e.jsx(
      w,
      {
        title: "每库 key 数（只有路 A 能按库统计；路 B 不能发 SELECT）",
        style: { marginTop: 16 },
        extra: /* @__PURE__ */ e.jsx(l, { color: "blue", children: "embedded 侧，16 库" }),
        children: !c.perDb || !c.perDb.length ? /* @__PURE__ */ e.jsx(
          g,
          {
            type: "info",
            showIcon: !0,
            message: "路 A 的 store 现在拿不到 ⇒ 没有按库数据",
            description: "原因见上面「不可用原因」/「反射失败原因」两格。这不是「缓存被清空了」。"
          }
        ) : /* @__PURE__ */ e.jsx(
          O,
          {
            size: "small",
            rowKey: "db",
            columns: Ge,
            dataSource: c.perDb,
            pagination: !1,
            summary: () => /* @__PURE__ */ e.jsxs(O.Summary.Row, { children: [
              /* @__PURE__ */ e.jsx(O.Summary.Cell, { index: 0, children: "合计" }),
              /* @__PURE__ */ e.jsx(O.Summary.Cell, { index: 1, children: c.dbsizeTotal })
            ] })
          }
        )
      }
    ),
    /* @__PURE__ */ e.jsx(w, { title: "口径说明", style: { marginTop: 16 }, children: /* @__PURE__ */ e.jsxs("div", { style: { fontSize: 12, color: "#64748b", lineHeight: 1.9 }, children: [
      "· 本页所有数字都无前端缓存、每次刷新重新读；后端不做任何聚合、补零或估算。",
      /* @__PURE__ */ e.jsx("br", {}),
      "· 内存 / 命中率 / 累计命令等运行指标在「统计与命令」页（路 A 走 Runtime+MemoryStore，路 B 走 INFO）。",
      /* @__PURE__ */ e.jsx("br", {}),
      "· 按 key 浏览在「键浏览」页；那里同样只有读操作，没有任何设值 / 删除 / 清空入口。",
      /* @__PURE__ */ e.jsx("br", {}),
      "· 若 ",
      /* @__PURE__ */ e.jsx("code", { children: "embedded.running=false" }),
      '，本页"路 A"一栏就是空的 —— 含义是"本 JVM 没在服务"，',
      /* @__PURE__ */ e.jsx("b", { children: "不是" }),
      '"缓存里没有数据"。这两件事的区别只由「实例与三态」页那四格决定。'
    ] }) })
  ] });
}
const we = {
  STRING: "blue",
  HASH: "purple",
  LIST: "cyan",
  SET: "orange",
  ZSET: "magenta",
  NONE: "default"
}, Ke = [
  { value: "STRING", label: "STRING" },
  { value: "HASH", label: "HASH" },
  { value: "LIST", label: "LIST" },
  { value: "SET", label: "SET" },
  { value: "ZSET", label: "ZSET" },
  { value: "NONE", label: "NONE" }
];
function Ze(r) {
  return r == null ? /* @__PURE__ */ e.jsx("span", { style: { color: "#94a3b8" }, children: "nil" }) : typeof r == "object" ? r.notUtf8 ? /* @__PURE__ */ e.jsxs(le, { title: "非 UTF-8 字节（多半是序列化后的对象），这里给 base64", children: [
    /* @__PURE__ */ e.jsx(l, { color: "volcano", children: "binary" }),
    /* @__PURE__ */ e.jsxs("code", { style: { fontSize: 11 }, children: [
      String(r.base64).slice(0, 60),
      "…"
    ] })
  ] }) : /* @__PURE__ */ e.jsx("pre", { style: { margin: 0, fontSize: 11, whiteSpace: "pre-wrap" }, children: JSON.stringify(r, null, 2) }) : /* @__PURE__ */ e.jsx("span", { style: { wordBreak: "break-all" }, children: String(r) });
}
function qe() {
  const [r, n] = j("auto"), [s, h] = j("*"), [o, p] = j(0), [b, m] = j(200), [E, c] = j(null), [x, T] = j([]), [a, C] = j(null), [_, R] = j(!1), [I, M] = j(null), [y, S] = j(null), [z, Q] = j(!1), [G, ee] = j(null), U = X(async () => {
    R(!0);
    try {
      const i = await B.keys({ pattern: s || "*", db: o, limit: b, source: r });
      T(i.keys ?? []), C(i), M(null);
    } catch (i) {
      T([]), C(null), M(i);
    } finally {
      R(!1);
    }
  }, [s, o, b, r]);
  ce(() => {
    U();
  }, [U]);
  const V = X(async (i) => {
    S({ key: i }), Q(!0), ee(null);
    try {
      const u = await B.key({ key: i, db: o, source: r, maxElements: 50 });
      S(u);
    } catch (u) {
      S(null), ee(u);
    } finally {
      Q(!1);
    }
  }, [o, r]), he = Se(() => E ? x.filter((i) => i.type === E) : x, [x, E]), xe = Se(() => [
    {
      title: "key",
      dataIndex: "key",
      key: "key",
      ellipsis: !0,
      render: (i) => /* @__PURE__ */ e.jsx("a", { onClick: () => V(i), children: /* @__PURE__ */ e.jsx("code", { children: i }) })
    },
    {
      title: "类型",
      dataIndex: "type",
      key: "type",
      width: 150,
      unmeasured: "未采信",
      // 路 B 的 type 现在是后端归一后的**字符串**（大写枚举名）或 null（D2）。
      // null 有两种来路，必须分开渲染：
      //   desyncSuspect=true  ⇒ 这一行的应答与所发命令对不上（串台），**不采信**（D3）
      //   其它 null            ⇒ 后端没给出类型
      // 旧版这里用 asText() 兜底，把 {value:"string"} 这种 bean 兜成一段 JSON 原文。
      render: (i, u) => u != null && u.desyncSuspect ? /* @__PURE__ */ e.jsx(le, { title: u.desyncReason || "这一行的应答与所发命令对不上，不采信", children: /* @__PURE__ */ e.jsx(l, { color: "red", children: "应答错位·未采信" }) }) : i == null ? /* @__PURE__ */ e.jsx("span", { style: { color: "#94a3b8" }, children: "未采信" }) : /* @__PURE__ */ e.jsx(l, { color: we[i] || "default", children: i }),
      filters: Ke.map((i) => ({ text: i.label, value: i.value })),
      onFilter: (i, u) => u.type === i
    },
    {
      title: "TTL",
      dataIndex: "ttlSeconds",
      key: "ttl",
      width: 170,
      unmeasured: "未测",
      render: (i, u) => u != null && u.ttlSecondsError ? /* @__PURE__ */ e.jsx(le, { title: u.ttlSecondsError, children: /* @__PURE__ */ e.jsx(l, { color: "red", children: "读数不可信" }) }) : i == null ? /* @__PURE__ */ e.jsx("span", { style: { color: "#94a3b8" }, title: u == null ? void 0 : u.ttlSecondsNote, children: "未测" }) : ve(i)
    },
    {
      title: "字节",
      dataIndex: "byteLength",
      key: "byteLength",
      width: 130,
      unmeasured: "未测",
      // ⚠️ 三个取值必须分开渲染（D4）：
      //   null  ⇒ 后端**没测**这一列（路 B 不发 STRLEN）⇒ 显示"未测"
      //   -1    ⇒ **测过**的结论：这个 key 不是 string
      //   其它   ⇒ 真的字节数
      // 旧版把 null 和 -1 合成一句"非 string" ⇒ 把"没测"渲染成了正面断言（最像假绿的一格）。
      render: (i, u) => i == null ? /* @__PURE__ */ e.jsx("span", { style: { color: "#94a3b8" }, title: u == null ? void 0 : u.byteLengthNote, children: "未测" }) : i < 0 ? /* @__PURE__ */ e.jsx("span", { style: { color: "#94a3b8" }, children: "非 string" }) : ie(i)
    },
    {
      title: "数据源",
      dataIndex: "authority",
      key: "authority",
      width: 190,
      unmeasured: "未采信",
      render: (i, u) => /* @__PURE__ */ e.jsx(l, { color: L((u == null ? void 0 : u.authority) ?? i).color, children: (u == null ? void 0 : u.authority) ?? i ?? "-" })
    }
  ], [V]), re = (a == null ? void 0 : a.sourceUsed) === "client";
  return /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsx(
      ue,
      {
        title: "键浏览",
        subtitle: "只读：GET /api/cache/keys + /api/cache/key —— 无设值 / 无删除 / 无清空"
      }
    ),
    /* @__PURE__ */ e.jsxs(w, { size: "small", style: { marginBottom: 12 }, children: [
      /* @__PURE__ */ e.jsxs(oe, { wrap: !0, size: 8, children: [
        /* @__PURE__ */ e.jsx("span", { children: "pattern" }),
        /* @__PURE__ */ e.jsx(
          Be,
          {
            style: { width: 240 },
            value: s,
            allowClear: !0,
            prefix: /* @__PURE__ */ e.jsx(De, {}),
            placeholder: "glob：* ? [] （默认 * = 全部）",
            onChange: (i) => h(i.target.value),
            onPressEnter: U
          }
        ),
        /* @__PURE__ */ e.jsx("span", { children: "db" }),
        /* @__PURE__ */ e.jsx(
          Ce,
          {
            style: { width: 100 },
            value: o,
            onChange: p,
            disabled: re,
            options: Array.from({ length: 16 }, (i, u) => ({ value: u, label: `db${u}` }))
          }
        ),
        /* @__PURE__ */ e.jsx("span", { children: "limit" }),
        /* @__PURE__ */ e.jsx($e, { style: { width: 90 }, min: 1, max: 1e3, value: b, onChange: m }),
        /* @__PURE__ */ e.jsx("span", { children: "source" }),
        /* @__PURE__ */ e.jsxs(te.Group, { value: r, onChange: (i) => n(i.target.value), children: [
          /* @__PURE__ */ e.jsx(te.Button, { value: "auto", children: "auto" }),
          /* @__PURE__ */ e.jsx(te.Button, { value: "embedded", children: "路 A 进程内" }),
          /* @__PURE__ */ e.jsx(te.Button, { value: "client", children: "路 B RESP" })
        ] }),
        /* @__PURE__ */ e.jsx(ae, { icon: /* @__PURE__ */ e.jsx(de, {}), loading: _, onClick: U, children: "查询" })
      ] }),
      re && /* @__PURE__ */ e.jsxs("div", { style: { marginTop: 8, fontSize: 12, color: "#b45309" }, children: [
        "当前走路 B（RESP），",
        /* @__PURE__ */ e.jsx("b", { children: "db 选择器已被忽略" }),
        "：",
        (a == null ? void 0 : a.dbIgnoreReason) || "SELECT 会永久改掉业务共用连接的活动库，所以路 B 不切库"
      ] })
    ] }),
    I && /* @__PURE__ */ e.jsx(
      g,
      {
        type: "error",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: `读不到 key 列表：${Y(I)}`,
        description: /* @__PURE__ */ e.jsxs("span", { children: [
          '后端 503 的含义是"这条路取不到数"，不是"缓存是空的"。',
          r === "embedded" && " 你强制了路 A，而本 JVM 的内嵌 server 没 bind ⇒ 必然 503；",
          "换 source=client 或直接看「实例与三态」页确认归属。"
        ] })
      }
    ),
    /* @__PURE__ */ e.jsxs(
      w,
      {
        title: /* @__PURE__ */ e.jsxs("span", { children: [
          "key 列表 ",
          a && /* @__PURE__ */ e.jsxs(l, { color: "blue", children: [
            "sourceUsed = ",
            a.sourceUsed
          ] }),
          (a == null ? void 0 : a.authority) && /* @__PURE__ */ e.jsx(l, { color: L(a.authority).color, children: a.authority })
        ] }),
        extra: a && /* @__PURE__ */ e.jsx(oe, { size: 8, children: /* @__PURE__ */ e.jsx(
          Le,
          {
            status: a.truncated ? "warning" : "default",
            text: a.truncated ? `后端 KEYS 命中 ${a.matchedTotal} 条，只取了前 ${a.limit} 条（KEYS 是 O(N)，硬上限 1000）` : `命中 ${a.matchedTotal ?? x.length} 条`
          }
        ) }),
        children: [
          (a == null ? void 0 : a.desyncRows) > 0 && /* @__PURE__ */ e.jsx(
            g,
            {
              type: "warning",
              showIcon: !0,
              style: { marginBottom: 8 },
              message: `路 B 本次有 ${a.desyncRows}/${a.totalRows} 行的应答对不上，这些行的类型/TTL 不参与展示`,
              description: a.desyncNote
            }
          ),
          /* @__PURE__ */ e.jsx(
            O,
            {
              size: "small",
              rowKey: (i) => `${i.key}`,
              loading: _,
              columns: xe,
              dataSource: he,
              locale: {
                emptyText: I ? /* @__PURE__ */ e.jsx(ne, { description: "读取失败，见上方告警" }) : /* @__PURE__ */ e.jsx(ne, { description: a ? a.sourceUsed === "embedded" ? "路 A 的进程内 store 里这个 pattern 没有匹配项（本 JVM 自己那份，确定没跨进程）" : "路 B 无应答或该库确实为空 —— 先看「实例与三态」确认应答者是不是本 JVM" : "还没取到数据" })
              },
              pagination: { size: "small", pageSize: 20, showSizeChanger: !0 }
            }
          )
        ]
      }
    ),
    /* @__PURE__ */ e.jsxs(
      Me,
      {
        width: 720,
        open: !!y,
        title: y ? `key: ${y.key}` : "key 详情",
        onClose: () => S(null),
        destroyOnHidden: !0,
        children: [
          G && /* @__PURE__ */ e.jsx(g, { type: "error", showIcon: !0, message: `读详情失败：${Y(G)}` }),
          !y && !G && /* @__PURE__ */ e.jsx(ne, { description: "载入中…" }),
          y && /* @__PURE__ */ e.jsxs(e.Fragment, { children: [
            /* @__PURE__ */ e.jsx(
              J,
              {
                bordered: !0,
                size: "small",
                column: 2,
                items: [
                  { key: "src", label: "取数路", children: /* @__PURE__ */ e.jsx(l, { children: y.sourceUsed }) },
                  { key: "auth", label: "归属", children: /* @__PURE__ */ e.jsx(l, { color: L(y.authority).color, children: y.authority ?? "-" }) },
                  { key: "db", label: "库", children: y.dbIgnoredForClientPath ? /* @__PURE__ */ e.jsxs("span", { children: [
                    "db",
                    y.db,
                    "（",
                    /* @__PURE__ */ e.jsx("b", { children: "已忽略" }),
                    "，路 B 不切库）"
                  ] }) : `db${y.db ?? "-"}` },
                  { key: "type", label: "类型", children: y.desyncSuspect ? /* @__PURE__ */ e.jsx(le, { title: y.desyncReason || "应答与所发命令对不上", children: /* @__PURE__ */ e.jsx(l, { color: "red", children: "应答错位·未采信" }) }) : /* @__PURE__ */ e.jsx(l, { color: we[y.type] || "default", children: y.type ?? "未采信" }) },
                  { key: "exists", label: "存在", children: y.exists == null ? "-" : y.exists ? /* @__PURE__ */ e.jsx(l, { color: "success", children: "是" }) : /* @__PURE__ */ e.jsx(l, { children: "否" }) },
                  { key: "ttl", label: "TTL", children: ve(y.ttlSeconds) },
                  { key: "pttl", label: "PTTL(ms)", children: y.pttlMillis ?? "-" },
                  { key: "ver", label: "keyVersion（路 A）", children: y.keyVersion ?? "-" },
                  y.error && { key: "err", label: "读取错误", span: 2, children: /* @__PURE__ */ e.jsx("code", { children: y.error }) }
                ].filter(Boolean)
              }
            ),
            /* @__PURE__ */ e.jsx(w, { size: "small", title: "值（按类型取，非 UTF-8 会降级成 base64）", style: { marginTop: 12 }, children: !y.value || !Object.keys(y.value).length ? /* @__PURE__ */ e.jsx(ne, { description: "该类型没有可读的值，或路 A/B 都取不到" }) : /* @__PURE__ */ e.jsx(
              J,
              {
                size: "small",
                column: 1,
                bordered: !0,
                items: Object.entries(y.value).map(([i, u]) => ({
                  key: i,
                  label: i,
                  children: Ze(u)
                }))
              }
            ) }),
            /* @__PURE__ */ e.jsx("div", { style: { marginTop: 12, fontSize: 12, color: "#64748b" }, children: "值渲染不改变任何后端状态：路 A 只调 MemoryStore 的读方法， 路 B 只发 TYPE/TTL/PTTL/GET/HGETALL/LRANGE/LLEN/SMEMBERS/SCARD/ZCARD/ZRANGE， 全部在 Java 侧白名单里，HTTP 无法指定命令名。" })
          ] })
        ]
      }
    )
  ] });
}
const Xe = [
  { title: "key", dataIndex: "key", key: "key", ellipsis: !0, render: (r) => /* @__PURE__ */ e.jsx("code", { children: r }) },
  {
    title: "访问次数",
    dataIndex: "accessCount",
    key: "accessCount",
    width: 110,
    sorter: (r, n) => r.accessCount - n.accessCount,
    render: (r) => /* @__PURE__ */ e.jsx(l, { color: "geekblue", children: r })
  },
  { title: "LFU 计数", dataIndex: "lfuCounter", key: "lfuCounter", width: 100 },
  {
    title: "已过期",
    dataIndex: "expired",
    key: "expired",
    width: 90,
    render: (r) => r ? /* @__PURE__ */ e.jsx(l, { color: "warning", children: "是" }) : /* @__PURE__ */ e.jsx(l, { color: "success", children: "否" })
  },
  { title: "TTL", dataIndex: "ttlSeconds", key: "ttlSeconds", width: 110, render: (r) => r === -1 ? "常驻" : `${r}s` },
  {
    title: "字节",
    dataIndex: "byteLength",
    key: "byteLength",
    width: 100,
    render: (r) => ie(r)
  },
  {
    title: "最后访问",
    dataIndex: "lastAccessTime",
    key: "lastAccessTime",
    width: 180,
    render: (r) => r ? new Date(r).toLocaleString() : "-"
  }
];
function Qe({ name: r, fields: n }) {
  return n ? /* @__PURE__ */ e.jsx(w, { size: "small", title: /* @__PURE__ */ e.jsxs("span", { children: [
    "# ",
    r
  ] }), style: { marginBottom: 12 }, children: /* @__PURE__ */ e.jsx(
    J,
    {
      size: "small",
      column: 2,
      bordered: !0,
      items: Object.entries(n).map(([s, h]) => ({ key: s, label: s, children: /* @__PURE__ */ e.jsx("code", { children: h }) }))
    }
  ) }) : null;
}
function er({ fp: r }) {
  var h, o;
  const n = r.verdict, s = n === "DIFFERENT_BUILD" ? "error" : n === "SAME_BUILD_CANDIDATE" ? "warning" : "info";
  return /* @__PURE__ */ e.jsx(
    g,
    {
      type: s,
      showIcon: !0,
      style: { marginTop: 4 },
      message: `构建指纹：${n === "DIFFERENT_BUILD" ? "应答者不是本次构建的产物" : n === "SAME_BUILD_CANDIDATE" ? "版本号一致，但不足以定身份" : "指纹不可用（一边取不到值，页面不猜）"}`,
      description: /* @__PURE__ */ e.jsxs(e.Fragment, { children: [
        /* @__PURE__ */ e.jsxs("div", { style: { fontSize: 12 }, children: [
          "本次构建会印 ",
          /* @__PURE__ */ e.jsxs("code", { children: [
            "z-cache_version:",
            ((h = r.ownBuildConstants) == null ? void 0 : h["z-cache_version"]) ?? "?"
          ] }),
          "／ 对面印 ",
          /* @__PURE__ */ e.jsxs("code", { children: [
            "z-cache_version:",
            r.responderVersion ?? "?"
          ] }),
          "；",
          /* @__PURE__ */ e.jsx("code", { children: "tcp_port" }),
          "：本次 ",
          /* @__PURE__ */ e.jsx("code", { children: ((o = r.ownBuildConstants) == null ? void 0 : o.tcp_port) ?? "?" }),
          "／ 对面 ",
          /* @__PURE__ */ e.jsx("code", { children: r.responderTcpPort ?? "?" })
        ] }),
        /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12, color: "#64748b", marginTop: 4 }, children: r.note }),
        /* @__PURE__ */ e.jsx("div", { style: { fontSize: 11, color: "#94a3b8", marginTop: 2 }, children: r.ownConstantsSource })
      ] })
    }
  );
}
function rr() {
  const [r, n] = j(null), [s, h] = j(null), [o, p] = j(null), [b, m] = j(null), [E, c] = j(null), [x, T] = j(0), [a, C] = j(!1), _ = X(async () => {
    C(!0);
    try {
      const [S, z] = await Promise.all([B.info(), B.instance()]);
      n(S), p(z), m(null);
    } catch (S) {
      n(null), m(S);
    }
    try {
      h(await B.hotkeys({ db: x, limit: 50 })), c(null);
    } catch (S) {
      h(null), c(S);
    } finally {
      C(!1);
    }
  }, [x]);
  ce(() => {
    _();
  }, [_]);
  const R = r == null ? void 0 : r.embedded, I = !!(o != null && o.foreignListenerSuspected), M = (r == null ? void 0 : r.sections) ?? {}, y = (r == null ? void 0 : r.responderVsOwnBuild) ?? null;
  return /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsx(
      ue,
      {
        title: "统计与热 key",
        subtitle: "GET /api/cache/info（路 B 的 INFO 原文 + 分节）与 /api/cache/hotkeys（路 A 的 LFU 埋点）"
      }
    ),
    b && /* @__PURE__ */ e.jsx(
      g,
      {
        type: "error",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: `读不到统计：${Y(b)}`
      }
    ),
    I && /* @__PURE__ */ e.jsx(
      g,
      {
        type: "error",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: "下面 INFO 一栏是外部进程自报的，不是本 JVM 的运行时指标",
        description: "authorityOfClient = EXTERNAL_PROCESS。要看本 JVM 的数，只有路 A 那一栏（它现在可能是空的，因为内嵌没 bind）。"
      }
    ),
    /* @__PURE__ */ e.jsxs(q, { gutter: 16, children: [
      /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsx(
        w,
        {
          title: /* @__PURE__ */ e.jsxs("span", { children: [
            "路 A · 进程内计数 ",
            /* @__PURE__ */ e.jsx(l, { color: "blue", children: "本 JVM 独有" })
          ] }),
          size: "small",
          loading: a,
          children: R ? /* @__PURE__ */ e.jsxs(e.Fragment, { children: [
            /* @__PURE__ */ e.jsxs(q, { gutter: 8, children: [
              /* @__PURE__ */ e.jsx(A, { span: 8, children: /* @__PURE__ */ e.jsx(F, { title: "dbsize", value: R.dbsizeTotal }) }),
              /* @__PURE__ */ e.jsx(A, { span: 8, children: /* @__PURE__ */ e.jsx(
                F,
                {
                  title: "命中率",
                  value: `${(R.hitRate * 100).toFixed(2)}%`
                }
              ) }),
              /* @__PURE__ */ e.jsx(A, { span: 8, children: /* @__PURE__ */ e.jsx(F, { title: "淘汰", value: R.evictions }) })
            ] }),
            /* @__PURE__ */ e.jsx(
              J,
              {
                size: "small",
                column: 1,
                style: { marginTop: 12 },
                bordered: !0,
                items: [
                  { key: "hits", label: "hits / misses", children: `${R.hits} / ${R.misses}` },
                  { key: "cmds", label: "累计命令", children: R.totalCommands },
                  { key: "clients", label: "当前连接", children: R.connectedClients },
                  { key: "max", label: "maxEntries", children: R.maxEntries },
                  { key: "heap", label: "JVM 堆（已用/上限）", children: `${ie(R.jvmHeapUsedBytes)} / ${ie(R.jvmHeapMaxBytes)}` },
                  { key: "cpu", label: "可用核数", children: R.jvmAvailableProcessors }
                ]
              }
            )
          ] }) : /* @__PURE__ */ e.jsx(
            g,
            {
              type: "warning",
              showIcon: !0,
              message: "路 A 的 store 拿不到 ⇒ 没有进程内运行指标",
              description: "内嵌 RedisServer 没 bind 成功（或反射失败）。这里显示为空是对的，显示 0 才是错的。"
            }
          )
        }
      ) }),
      /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsxs(
        w,
        {
          title: /* @__PURE__ */ e.jsxs("span", { children: [
            "路 B · 上游 INFO ",
            /* @__PURE__ */ e.jsx(l, { color: L(r == null ? void 0 : r.authorityOfClient).color, children: (r == null ? void 0 : r.authorityOfClient) ?? "-" })
          ] }),
          size: "small",
          loading: a,
          children: [
            (r == null ? void 0 : r.clientError) && /* @__PURE__ */ e.jsx(g, { type: "error", showIcon: !0, style: { marginBottom: 8 }, message: r.clientError }),
            (r == null ? void 0 : r.clientNote) && /* @__PURE__ */ e.jsx(g, { type: "warning", showIcon: !0, style: { marginBottom: 8 }, message: r.clientNote }),
            Object.keys(M).length === 0 && !(r != null && r.clientError) && !(r != null && r.clientNote) && /* @__PURE__ */ e.jsx(g, { type: "info", showIcon: !0, message: "INFO 为空 —— 应答者没返回任何分节" }),
            Object.entries(M).map(([S, z]) => /* @__PURE__ */ e.jsx(Qe, { name: S, fields: z }, S)),
            (r == null ? void 0 : r.hardcodedInUpstream) && /* @__PURE__ */ e.jsx(
              g,
              {
                type: "error",
                showIcon: !0,
                style: { marginTop: 4 },
                message: "INFO 里这两个字段是写死的字面量，不要拿去对照 jar 版本或端口",
                description: /* @__PURE__ */ e.jsx("ul", { style: { margin: 0, paddingLeft: 18 }, children: r.hardcodedInUpstream.map((S) => /* @__PURE__ */ e.jsx("li", { style: { fontSize: 12 }, children: S }, S)) })
              }
            ),
            y && /* @__PURE__ */ e.jsx(er, { fp: y })
          ]
        }
      ) })
    ] }),
    (r == null ? void 0 : r.raw) && /* @__PURE__ */ e.jsx(w, { title: "INFO 原文（未加工，逐字符来自上游）", size: "small", style: { marginTop: 16 }, children: /* @__PURE__ */ e.jsx("pre", { style: { margin: 0, maxHeight: 260, overflow: "auto", fontSize: 12 }, children: r.raw }) }),
    /* @__PURE__ */ e.jsxs(
      w,
      {
        title: "热 key（路 A 的 LFU 埋点，只覆盖 string 类型）",
        style: { marginTop: 16 },
        size: "small",
        extra: /* @__PURE__ */ e.jsxs(oe, { children: [
          /* @__PURE__ */ e.jsx("span", { style: { fontSize: 12, color: "#64748b" }, children: "库" }),
          /* @__PURE__ */ e.jsx(
            Ce,
            {
              size: "small",
              style: { width: 96 },
              value: x,
              onChange: T,
              options: Array.from({ length: 16 }, (S, z) => ({ value: z, label: `db${z}` }))
            }
          ),
          /* @__PURE__ */ e.jsx(ae, { icon: /* @__PURE__ */ e.jsx(de, {}), loading: a, onClick: _, children: "刷新" })
        ] }),
        children: [
          E && /* @__PURE__ */ e.jsx(
            g,
            {
              type: "warning",
              showIcon: !0,
              style: { marginBottom: 8 },
              message: `读不到热 key：${Y(E)}`,
              description: "后端只有路 A 能出这个视图，所以「反射不到内嵌 RedisServer」= 503。这不是「没有热 key」。"
            }
          ),
          s && /* @__PURE__ */ e.jsxs(e.Fragment, { children: [
            /* @__PURE__ */ e.jsxs("div", { style: { fontSize: 12, color: "#64748b", marginBottom: 8 }, children: [
              "db",
              s.db,
              " · string key 共 ",
              s.stringKeyTotal,
              " 个",
              s.truncated ? "（只显示前 50，已截断）" : "",
              "· ",
              /* @__PURE__ */ e.jsx(l, { color: L(s.authority).color, children: s.authority })
            ] }),
            /* @__PURE__ */ e.jsx(
              O,
              {
                size: "small",
                rowKey: "key",
                columns: Xe,
                dataSource: s.rows ?? [],
                pagination: { size: "small", pageSize: 20 },
                locale: { emptyText: `db${x} 的 string store 里一个 key 都没有（路 A 口径，确定没跨进程）` }
              }
            ),
            /* @__PURE__ */ e.jsxs("div", { style: { marginTop: 8, fontSize: 12, color: "#94a3b8" }, children: [
              s.scopeNote,
              " ／ ",
              s.sourceNote
            ] })
          ] })
        ]
      }
    )
  ] });
}
const pe = [
  { title: "RESP 命令", dataIndex: "command", key: "command", width: 140, render: (r) => /* @__PURE__ */ e.jsx("code", { children: r }) },
  { title: "说明", dataIndex: "note", key: "note", ellipsis: !0 }
];
function N({ value: r, okText: n = "是", badText: s = "否", badColor: h = "error", unknown: o = "未探测" }) {
  return r == null ? /* @__PURE__ */ e.jsx(l, { children: o }) : r ? /* @__PURE__ */ e.jsx(l, { color: "success", children: n }) : /* @__PURE__ */ e.jsx(l, { color: h, children: s });
}
function tr({ value: r }) {
  const n = L(r);
  return /* @__PURE__ */ e.jsx(l, { color: n.color, children: r || "-" });
}
function nr() {
  const [r, n] = j(null), [s, h] = j(null), [o, p] = j(null), [b, m] = j(!1), E = X(async () => {
    m(!0);
    try {
      const T = await B.instance();
      n(T), p(null);
      try {
        h(await B.commandCatalog());
      } catch {
        h(null);
      }
    } catch (T) {
      n(null), p(T);
    } finally {
      m(!1);
    }
  }, []);
  ce(() => {
    E();
  }, [E]);
  const c = !o && r && r.foreignListenerSuspected, x = !o && r && !r.embeddedRunning;
  return /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsx(
      ue,
      {
        title: "实例与三态",
        subtitle: "只读 controller 自省接口 GET /api/cache/__instance —— 先证明应答者是谁，再谈数据"
      }
    ),
    o && /* @__PURE__ */ e.jsx(
      g,
      {
        type: "error",
        showIcon: !0,
        message: `自省接口调用失败：${Y(o)}`,
        description: "/api/cache/__instance 由 z-opc 自己的 controller 直接应答，不经过任何 RESP 连接；它都不通说明是 z-opc 侧的路由/鉴权问题（302 到登录页 = 没带会话 Cookie）。"
      }
    ),
    x && !c && /* @__PURE__ */ e.jsx(
      g,
      {
        type: "warning",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: "内嵌 RedisServer 没有 bind 成功 —— 其余三页的空表是「没起」，不是「缓存里没有 key」",
        description: `configuredPort=${r.configuredPort} 上没有任何监听者。blockedReason：${r.blockedReason || "-"}`
      }
    ),
    c && /* @__PURE__ */ e.jsx(
      g,
      {
        type: "error",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: `${r.configuredPort} 端口上有人在应答，但它不是本 JVM —— 这一路读到的是外部进程的缓存，不能算孵化结果`,
        description: `embeddedRunning=false（本 JVM 的 RedisServer 没 bind 上）而 ${r.configuredPort} TCP 可连 ⇒ 应答者必然不是本进程。请用 lsof -nP -iTCP:${r.configuredPort} -sTCP:LISTEN 取属主 PID，与本页「应答的 JVM」= ${r.jvm} 比对。实测本仓库当前状态：6379 属主是 5 天前的另一个 z-opc boot jar（交接文件 §二 E3/E5），本 JVM 的 stdout 里有 java.net.BindException: Address already in use。解法只有两种（让出端口，或用 -Dz.cache.server.port 换端口），都需要定夺 —— 页面不会替你占端口。`
      }
    ),
    /* @__PURE__ */ e.jsxs(
      w,
      {
        title: "这一路到底连的是谁",
        loading: b,
        extra: /* @__PURE__ */ e.jsx(oe, { children: /* @__PURE__ */ e.jsx(ae, { icon: /* @__PURE__ */ e.jsx(de, {}), loading: b, onClick: E, children: "刷新" }) }),
        children: [
          /* @__PURE__ */ e.jsx(
            J,
            {
              bordered: !0,
              size: "small",
              column: 2,
              items: [
                {
                  key: "jvm",
                  label: "应答的 JVM (pid@host)",
                  children: /* @__PURE__ */ e.jsx("code", { children: (r == null ? void 0 : r.jvm) ?? "-" })
                },
                {
                  key: "surface",
                  label: "z-cache 对外面的种类",
                  children: /* @__PURE__ */ e.jsxs("span", { children: [
                    /* @__PURE__ */ e.jsx(l, { color: "volcano", children: (r == null ? void 0 : r.surface) ?? "-" }),
                    /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12, color: "#64748b" }, children: r == null ? void 0 : r.httpSurfaceOfZCache })
                  ] })
                },
                {
                  key: "lifecycleBean",
                  label: "内嵌配置已装载",
                  children: r ? r.lifecycleBean ? /* @__PURE__ */ e.jsx(l, { color: "success", children: "是（z.cache.enabled=true）" }) : /* @__PURE__ */ e.jsx(l, { color: "error", children: "否" }) : "-"
                },
                {
                  key: "starterClaim",
                  label: "starter 自报 isServerStarted()",
                  children: /* @__PURE__ */ e.jsxs("span", { children: [
                    /* @__PURE__ */ e.jsx(
                      N,
                      {
                        value: r == null ? void 0 : r.isServerStartedClaimedByStarter,
                        okText: "true",
                        badText: "false"
                      }
                    ),
                    /* @__PURE__ */ e.jsxs("div", { style: { fontSize: 12, color: "#b45309" }, children: [
                      '⚠ 这格的语义是"进过 startServer()"，',
                      /* @__PURE__ */ e.jsx("b", { children: "不是" }),
                      '"bind 成功了" —— 单独看它一定误判'
                    ] })
                  ] })
                },
                {
                  key: "reflected",
                  label: "反射取到内嵌 RedisServer",
                  children: /* @__PURE__ */ e.jsxs("span", { children: [
                    /* @__PURE__ */ e.jsx(
                      N,
                      {
                        value: r == null ? void 0 : r.serverFieldReflected,
                        okText: "是（路 A 可用）",
                        badText: "否（只剩路 B）"
                      }
                    ),
                    r != null && r.serverReflectError ? /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12, color: "#64748b" }, children: /* @__PURE__ */ e.jsx("code", { children: r.serverReflectError }) }) : null
                  ] })
                },
                {
                  key: "running",
                  label: "内嵌 server 真在跑 (isRunning)",
                  children: /* @__PURE__ */ e.jsx(N, { value: r == null ? void 0 : r.embeddedRunning, okText: "是", badText: "否" })
                },
                {
                  key: "boundPort",
                  label: "内嵌实际 bind 端口",
                  children: r ? r.boundPort > 0 ? /* @__PURE__ */ e.jsx(l, { color: "success", children: r.boundPort }) : /* @__PURE__ */ e.jsx(l, { color: "error", children: "0（没 bind 上）" }) : "-"
                },
                {
                  key: "store",
                  label: "进程内 store 可读（路 A）",
                  children: /* @__PURE__ */ e.jsx(
                    N,
                    {
                      value: r == null ? void 0 : r.storeAvailable,
                      okText: "可用",
                      badText: "不可用",
                      badColor: "warning"
                    }
                  )
                },
                {
                  key: "configured",
                  label: "配置端口（Spring 环境视角）",
                  children: (r == null ? void 0 : r.configuredPort) ?? "-"
                },
                {
                  key: "accepting",
                  label: "该端口此刻 TCP 可连",
                  children: /* @__PURE__ */ e.jsx(
                    N,
                    {
                      value: r == null ? void 0 : r.acceptingNow,
                      okText: "有人应答",
                      badText: "无人应答",
                      badColor: "warning"
                    }
                  )
                },
                {
                  key: "owned",
                  label: "该端口的属主是本 JVM",
                  children: /* @__PURE__ */ e.jsx(N, { value: r == null ? void 0 : r.embeddedPortOwnedByThisJvm, badColor: "error" })
                },
                {
                  key: "foreign",
                  label: "外来监听者嫌疑",
                  children: r ? r.foreignListenerSuspected ? /* @__PURE__ */ e.jsx(l, { color: "error", children: "有（应答者不是本 JVM）" }) : /* @__PURE__ */ e.jsx(l, { color: "success", children: "无" }) : "-"
                },
                {
                  key: "clientBean",
                  label: "zCacheClient bean 存在（路 B）",
                  children: /* @__PURE__ */ e.jsx(N, { value: r == null ? void 0 : r.clientBeanPresent, badColor: "warning" })
                },
                {
                  key: "clientConn",
                  label: "RESP 连接已建立",
                  children: /* @__PURE__ */ e.jsx(
                    N,
                    {
                      value: r == null ? void 0 : r.clientTransportConnected,
                      okText: "已连",
                      badText: "未连",
                      badColor: "warning"
                    }
                  )
                },
                {
                  key: "clientTarget",
                  label: "client 被配到",
                  children: r ? /* @__PURE__ */ e.jsx("code", { children: `${r.clientTargetHost ?? "-"}:${r.clientTargetPort ?? "-"} /db${r.clientTargetDatabase ?? "-"}` }) : "-"
                },
                {
                  key: "clientAuth",
                  label: "client 侧启用了密码",
                  children: /* @__PURE__ */ e.jsx(
                    N,
                    {
                      value: r == null ? void 0 : r.clientTargetHasPassword,
                      okText: "有",
                      badText: "无（6379 裸奔）",
                      badColor: "warning"
                    }
                  )
                },
                {
                  key: "authority",
                  label: "路 B 应答者的归属",
                  children: /* @__PURE__ */ e.jsxs("span", { children: [
                    /* @__PURE__ */ e.jsx(tr, { value: r == null ? void 0 : r.authorityOfClient }),
                    /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12, color: "#64748b" }, children: "THIS_JVM_EMBEDDED 才算孵化成功；EXTERNAL_PROCESS = 读到的是别人的实例" })
                  ] })
                },
                {
                  key: "portChain",
                  label: "端口配置的两条解析链",
                  children: /* @__PURE__ */ e.jsxs("span", { children: [
                    /* @__PURE__ */ e.jsxs("code", { children: [
                      "-Dz.cache.server.port = ",
                      String((r == null ? void 0 : r.serverPortFromSystemProperty) ?? "-")
                    ] }),
                    /* @__PURE__ */ e.jsxs("div", { style: { fontSize: 12, color: "#64748b" }, children: [
                      "Spring 环境解析值 = ",
                      String((r == null ? void 0 : r.serverPortFromSpringEnv) ?? "-"),
                      "；",
                      r != null && r.serverPortResolutionDiverges ? /* @__PURE__ */ e.jsx("b", { style: { color: "#b45309" }, children: " 两者不一致 —— static 块只认 -D，properties 是装饰" }) : " 两者一致"
                    ] })
                  ] })
                },
                {
                  key: "readApi",
                  label: "取数实现",
                  children: /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12 }, children: r == null ? void 0 : r.readApiImplementedBy })
                },
                {
                  key: "prefix",
                  label: "鉴权前缀",
                  children: /* @__PURE__ */ e.jsxs("span", { children: [
                    /* @__PURE__ */ e.jsx("code", { children: "/api/cache/** → sso.intercept-paths=/**/api/**" }),
                    /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12, color: "#64748b" }, children: "实测无 Cookie 时 /api/** 一律 302 到登录页；不开裸路径，也不引导直连 6379（内嵌默认 bind 0.0.0.0 且无密码）" })
                  ] })
                },
                {
                  key: "write",
                  label: "写端点",
                  children: /* @__PURE__ */ e.jsx(l, { color: "success", children: "0 个（writeEndpoints 恒为 []，后端只有 @GetMapping）" })
                },
                {
                  key: "select",
                  label: "是否发过 SELECT",
                  children: /* @__PURE__ */ e.jsx(l, { color: "success", children: "从不（会改掉业务共用连接的活动库）" })
                },
                {
                  key: "invariant",
                  label: "保真不变式（代替 HTTP 转发探针）",
                  children: /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12 }, children: r == null ? void 0 : r.invariant })
                },
                {
                  key: "blocked",
                  label: "当前阻塞原因",
                  span: 2,
                  children: r != null && r.blockedReason ? /* @__PURE__ */ e.jsx("span", { style: { color: "#b45309" }, children: r.blockedReason }) : /* @__PURE__ */ e.jsx(l, { color: "success", children: "无" })
                }
              ]
            }
          ),
          /* @__PURE__ */ e.jsxs("div", { style: { marginTop: 12, fontSize: 12, color: "#64748b" }, children: [
            "只读端点清单：",
            ((r == null ? void 0 : r.readEndpoints) ?? []).map((T) => /* @__PURE__ */ e.jsx(
              "code",
              {
                style: { marginRight: 6 },
                children: T
              },
              T
            ))
          ] })
        ]
      }
    ),
    /* @__PURE__ */ e.jsx(
      w,
      {
        title: "本 controller 实际下发 / 白名单允许 / 永不下发的 RESP 命令",
        style: { marginTop: 16 },
        extra: /* @__PURE__ */ e.jsx(l, { color: "blue", children: "命令名是代码常量，HTTP 侧无法指定" }),
        children: s ? /* @__PURE__ */ e.jsxs(e.Fragment, { children: [
          /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12, color: "#64748b", marginBottom: 8 }, children: s.provenance }),
          /* @__PURE__ */ e.jsx(
            O,
            {
              size: "small",
              rowKey: "command",
              pagination: !1,
              columns: pe,
              dataSource: s.commandsActuallyIssued ?? [],
              title: () => {
                var T;
                return /* @__PURE__ */ e.jsx("span", { children: /* @__PURE__ */ e.jsxs(l, { color: "success", children: [
                  "只读 · 本 controller 真正会发的（",
                  ((T = s.commandsActuallyIssued) == null ? void 0 : T.length) ?? 0,
                  " 条）"
                ] }) });
              }
            }
          ),
          s.notCountedAsIssued ? /* @__PURE__ */ e.jsx(
            g,
            {
              style: { marginTop: 8 },
              type: "warning",
              showIcon: !0,
              message: "为什么 PING 不算「已下发」",
              description: s.notCountedAsIssued
            }
          ) : null,
          /* @__PURE__ */ e.jsx(
            O,
            {
              style: { marginTop: 12 },
              size: "small",
              rowKey: "command",
              pagination: !1,
              columns: pe,
              dataSource: s.readOnlyCommandsAllowedByWhitelist ?? [],
              title: () => /* @__PURE__ */ e.jsx("span", { children: /* @__PURE__ */ e.jsx(l, { color: "processing", children: "只读白名单 = 上限集合（safeSend 入口校验用）" }) })
            }
          ),
          s.whitelistSemantics ? /* @__PURE__ */ e.jsx("div", { style: { marginTop: 8, fontSize: 12, color: "#64748b" }, children: s.whitelistSemantics }) : null,
          /* @__PURE__ */ e.jsx(
            O,
            {
              style: { marginTop: 12 },
              size: "small",
              rowKey: "command",
              pagination: !1,
              columns: pe,
              dataSource: s.neverIssuedDespiteBeingSupportedByServer ?? [],
              title: () => /* @__PURE__ */ e.jsx("span", { children: /* @__PURE__ */ e.jsx(l, { color: "error", children: "server 支持但本 controller 永不下发" }) })
            }
          )
        ] }) : /* @__PURE__ */ e.jsx(
          g,
          {
            type: "info",
            showIcon: !0,
            message: r ? "命令目录读不到（不影响上面三态结论）" : "等自省接口回来再看"
          }
        )
      }
    ),
    /* @__PURE__ */ e.jsx(w, { title: "「上游自报存活时长」这一招在 z-cache 上不可用", style: { marginTop: 16 }, children: /* @__PURE__ */ e.jsx(
      g,
      {
        type: "warning",
        showIcon: !0,
        message: "别拿 storeUptimeSeconds 或 INFO 的字段去反推应答者是谁",
        description: /* @__PURE__ */ e.jsxs("div", { children: [
          /* @__PURE__ */ e.jsx("div", { children: "· MemoryStore.getStartTime() 在内嵌 RedisServer 被 new 出来时就有了，bind 失败也照样涨 ⇒ 它衡量的是「对象存在多久」，不是「服务在线多久」。" }),
          /* @__PURE__ */ e.jsxs("div", { children: [
            "· 上游 INFO 里的 tcp_port:6379 与 z-cache_version:1.0.2 是 CommandHandler.handleInfo 里的",
            /* @__PURE__ */ e.jsx("b", { children: "硬编码字面量" }),
            "（实测 classpath 上的 jar 是 1.3.1）， 拿它判断端口/版本必然得出错误结论。"
          ] }),
          /* @__PURE__ */ e.jsx("div", { children: "· 所以 z-cache 这条路上，归属判定只能用本页的 embeddedRunning + acceptingNow + foreignListenerSuspected + authorityOfClient 四格， 外加 shell 侧的 lsof / ps / 本 JVM stdout 三条互不依赖的证据。" })
        ] })
      }
    ) })
  ] });
}
function dr() {
  return /* @__PURE__ */ e.jsxs(Pe, { children: [
    /* @__PURE__ */ e.jsx(W, { index: !0, element: /* @__PURE__ */ e.jsx(Ne, { to: "overview", replace: !0 }) }),
    /* @__PURE__ */ e.jsx(W, { path: "overview", element: /* @__PURE__ */ e.jsx(We, {}) }),
    /* @__PURE__ */ e.jsx(W, { path: "keys", element: /* @__PURE__ */ e.jsx(qe, {}) }),
    /* @__PURE__ */ e.jsx(W, { path: "stats", element: /* @__PURE__ */ e.jsx(rr, {}) }),
    /* @__PURE__ */ e.jsx(W, { path: "instance", element: /* @__PURE__ */ e.jsx(nr, {}) })
  ] });
}
export {
  Fe as A,
  dr as C,
  Ue as a,
  L as b,
  B as c,
  Y as d,
  ve as e,
  ie as f,
  Je as g,
  ar as i,
  e as j
};
