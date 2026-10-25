import { Routes as Pe, Route as W, Navigate as Ie } from "react-router-dom";
import ze, { useState as x, useCallback as X, useEffect as ie, useMemo as Te } from "react";
import { Alert as b, Row as Z, Col as A, Card as C, Statistic as F, Descriptions as U, Tag as o, Button as ce, Table as L, Tooltip as se, Space as oe, Input as Oe, Select as we, InputNumber as Ne, Radio as ne, Badge as Be, Drawer as Le, Empty as Se } from "antd";
import { ReloadOutlined as ae, SearchOutlined as $e } from "@ant-design/icons";
import D from "@yuku123/z-frontend-common";
import { ListTable as Me, DEFAULT_PAGINATION as De, EmptyState as pe, ComponentDoc as Fe } from "@yuku123/z-frontend-common/ui";
const N = {
  instance: () => D.get("/cache/__instance"),
  overview: () => D.get("/cache/overview"),
  keys: (r) => D.get("/cache/keys", { params: r }),
  key: (r) => D.get("/cache/key", { params: r }),
  info: () => D.get("/cache/info"),
  hotkeys: (r) => D.get("/cache/hotkeys", { params: r }),
  commandCatalog: () => D.get("/cache/command-catalog")
}, Ue = {
  THIS_JVM_IN_PROCESS: { color: "success", text: "本 JVM 进程内对象（不可能跨进程）" },
  THIS_JVM_EMBEDDED: { color: "success", text: "本 JVM 内嵌 server 应答" },
  EXTERNAL_PROCESS: {
    color: "error",
    text: "外部进程应答 —— 6379 的属主不是本 JVM，这一路读到的是别的进程的缓存"
  },
  NO_ANSWER: { color: "warning", text: "没有任何 server 在应答" },
  UNKNOWN: { color: "warning", text: "连上了，但无法判定连的是谁" }
};
function $(r) {
  return Ue[r] || { color: "default", text: r || "-" };
}
function G(r) {
  var s;
  const t = (s = r == null ? void 0 : r.response) == null ? void 0 : s.data;
  if (t && typeof t == "object") {
    const h = t.message || t.error, i = t.hint ? `｜${t.hint}` : "", y = t.source ? ` (${t.source})` : "";
    if (h) return `${h}${y}${i}`;
  }
  return typeof t == "string" && t.trim() ? t.slice(0, 240) : (r == null ? void 0 : r.message) || String(r);
}
function hr(r) {
  var h, i, y;
  const t = (h = r == null ? void 0 : r.response) == null ? void 0 : h.status, s = (y = (i = r == null ? void 0 : r.response) == null ? void 0 : i.data) == null ? void 0 : y.source;
  return t === 503 && s === "z-opc-cache-read-api";
}
function le(r) {
  if (r == null) return "-";
  const t = Number(r);
  return isFinite(t) ? t < 1024 ? `${t} B` : t < 1024 * 1024 ? `${(t / 1024).toFixed(1)} KiB` : t < 1024 * 1024 * 1024 ? `${(t / 1024 / 1024).toFixed(1)} MiB` : `${(t / 1024 / 1024 / 1024).toFixed(2)} GiB` : "-";
}
function Ee(r) {
  if (r == null) return "-";
  const t = Number(r);
  return t === -2 ? "不存在（-2）" : t === -1 ? "常驻（无过期）" : t < 10 ? `${t} 秒` : t < 600 ? `${Math.floor(t / 60)} 分 ${t % 60} 秒` : `${Math.floor(t / 86400)} 天 ${Math.floor(t % 86400 / 3600)} 时`;
}
function Je(r) {
  if (r == null) return "-";
  const t = Number(r), s = Math.floor(t / 86400), h = Math.floor(t % 86400 / 3600), i = Math.floor(t % 3600 / 60);
  return s > 0 ? `${s} 天 ${h} 时 ${i} 分` : h > 0 ? `${h} 时 ${i} 分 ${t % 60} 秒` : `${i} 分 ${t % 60} 秒`;
}
function Ve(r) {
  if (r == null) return null;
  if (typeof r == "string") return r;
  if (typeof r == "number" || typeof r == "boolean") return String(r);
  if (Array.isArray(r)) return r.map(Ve).filter((t) => t != null).join(", ");
  if (typeof r.error == "string") return `命令失败：${r.command ?? "?"} → ${r.error}`;
  try {
    return JSON.stringify(r);
  } catch {
    return String(r);
  }
}
var te = { exports: {} }, K = {};
/**
 * @license React
 * react-jsx-runtime.production.js
 *
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
var ve;
function He() {
  if (ve) return K;
  ve = 1;
  var r = Symbol.for("react.transitional.element"), t = Symbol.for("react.fragment");
  function s(h, i, y) {
    var g = null;
    if (y !== void 0 && (g = "" + y), i.key !== void 0 && (g = "" + i.key), "key" in i) {
      y = {};
      for (var k in i)
        k !== "key" && (y[k] = i[k]);
    } else y = i;
    return i = y.ref, {
      $$typeof: r,
      type: h,
      key: g,
      ref: i !== void 0 ? i : null,
      props: y
    };
  }
  return K.Fragment = t, K.jsx = s, K.jsxs = s, K;
}
var q = {};
/**
 * @license React
 * react-jsx-runtime.development.js
 *
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
var ke;
function Ge() {
  return ke || (ke = 1, process.env.NODE_ENV !== "production" && (function() {
    function r(n) {
      if (n == null) return null;
      if (typeof n == "function")
        return n.$$typeof === re ? null : n.displayName || n.name || null;
      if (typeof n == "string") return n;
      switch (n) {
        case P:
          return "Fragment";
        case m:
          return "Profiler";
        case M:
          return "StrictMode";
        case Y:
          return "Suspense";
        case ee:
          return "SuspenseList";
        case de:
          return "Activity";
        case ue:
          return "ViewTransition";
      }
      if (typeof n == "object")
        switch (typeof n.tag == "number" && console.error(
          "Received an unexpected object in getComponentNameFromType(). This is likely a bug in React. Please file an issue."
        ), n.$$typeof) {
          case v:
            return "Portal";
          case I:
            return n.displayName || "Context";
          case T:
            return (n._context.displayName || "Context") + ".Consumer";
          case Q:
            var a = n.render;
            return n = n.displayName, n || (n = a.displayName || a.name || "", n = n !== "" ? "ForwardRef(" + n + ")" : "ForwardRef"), n;
          case J:
            return a = n.displayName || null, a !== null ? a : r(n.type) || "Memo";
          case V:
            a = n._payload, n = n._init;
            try {
              return r(n(a));
            } catch {
            }
        }
      return null;
    }
    function t(n) {
      return "" + n;
    }
    function s(n) {
      try {
        t(n);
        var a = !1;
      } catch {
        a = !0;
      }
      if (a) {
        a = console;
        var j = a.error, S = typeof Symbol == "function" && Symbol.toStringTag && n[Symbol.toStringTag] || n.constructor.name || "Object";
        return j.call(
          a,
          "The provided key is an unsupported type %s. This value must be coerced to a string before using it here.",
          S
        ), t(n);
      }
    }
    function h(n) {
      if (n === P) return "<>";
      if (typeof n == "object" && n !== null && n.$$typeof === V)
        return "<...>";
      try {
        var a = r(n);
        return a ? "<" + a + ">" : "<...>";
      } catch {
        return "<...>";
      }
    }
    function i() {
      var n = l.A;
      return n === null ? null : n.getOwner();
    }
    function y() {
      return Error("react-stack-top-frame");
    }
    function g(n) {
      if (d.call(n, "key")) {
        var a = Object.getOwnPropertyDescriptor(n, "key").get;
        if (a && a.isReactWarning) return !1;
      }
      return n.key !== void 0;
    }
    function k(n, a) {
      function j() {
        ye || (ye = !0, console.error(
          "%s: `key` is not a prop. Trying to access it will result in `undefined` being returned. If you need to access the same value within the child component, you should pass it as a different prop. (https://react.dev/link/special-props)",
          a
        ));
      }
      j.isReactWarning = !0, Object.defineProperty(n, "key", {
        get: j,
        configurable: !0
      });
    }
    function R() {
      var n = r(this.type);
      return je[n] || (je[n] = !0, console.error(
        "Accessing element.ref was removed in React 19. ref is now a regular prop. It will be removed from the JSX Element type in a future release."
      )), n = this.props.ref, n !== void 0 ? n : null;
    }
    function u(n, a, j, S, B, z) {
      var E = j.ref;
      return n = {
        $$typeof: _,
        type: n,
        key: a,
        props: j,
        _owner: S
      }, (E !== void 0 ? E : null) !== null ? Object.defineProperty(n, "ref", {
        enumerable: !1,
        get: R
      }) : Object.defineProperty(n, "ref", { enumerable: !1, value: null }), n._store = {}, Object.defineProperty(n._store, "validated", {
        configurable: !1,
        enumerable: !1,
        writable: !0,
        value: 0
      }), Object.defineProperty(n, "_debugInfo", {
        configurable: !1,
        enumerable: !1,
        writable: !0,
        value: null
      }), Object.defineProperty(n, "_debugStack", {
        configurable: !1,
        enumerable: !1,
        writable: !0,
        value: B
      }), Object.defineProperty(n, "_debugTask", {
        configurable: !1,
        enumerable: !1,
        writable: !0,
        value: z
      }), Object.freeze && (Object.freeze(n.props), Object.freeze(n)), n;
    }
    function p(n, a, j, S, B, z) {
      var E = a.children;
      if (E !== void 0)
        if (S)
          if (_e(E)) {
            for (S = 0; S < E.length; S++)
              f(E[S]);
            Object.freeze && Object.freeze(E);
          } else
            console.error(
              "React.jsx: Static children should always be an array. You are likely explicitly calling React.jsxs or React.jsxDEV. Use the Babel transform instead."
            );
        else f(E);
      if (d.call(a, "key")) {
        E = r(n);
        var H = Object.keys(a).filter(function(Ae) {
          return Ae !== "key";
        });
        S = 0 < H.length ? "{key: someKey, " + H.join(": ..., ") + ": ...}" : "{key: someKey}", fe[E + S] || (H = 0 < H.length ? "{" + H.join(": ..., ") + ": ...}" : "{}", console.error(
          `A props object containing a "key" prop is being spread into JSX:
  let props = %s;
  <%s {...props} />
React keys must be passed directly to JSX without using spread:
  let props = %s;
  <%s key={someKey} {...props} />`,
          S,
          E,
          H,
          E
        ), fe[E + S] = !0);
      }
      if (E = null, j !== void 0 && (s(j), E = "" + j), g(a) && (s(a.key), E = "" + a.key), "key" in a) {
        j = {};
        for (var me in a)
          me !== "key" && (j[me] = a[me]);
      } else j = a;
      return E && k(
        j,
        typeof n == "function" ? n.displayName || n.name || "Unknown" : n
      ), u(
        n,
        E,
        j,
        i(),
        B,
        z
      );
    }
    function f(n) {
      c(n) ? n._store && (n._store.validated = 1) : typeof n == "object" && n !== null && n.$$typeof === V && (n._payload.status === "fulfilled" ? c(n._payload.value) && n._payload.value._store && (n._payload.value._store.validated = 1) : n._store && (n._store.validated = 1));
    }
    function c(n) {
      return typeof n == "object" && n !== null && n.$$typeof === _;
    }
    var w = ze, _ = Symbol.for("react.transitional.element"), v = Symbol.for("react.portal"), P = Symbol.for("react.fragment"), M = Symbol.for("react.strict_mode"), m = Symbol.for("react.profiler"), T = Symbol.for("react.consumer"), I = Symbol.for("react.context"), Q = Symbol.for("react.forward_ref"), Y = Symbol.for("react.suspense"), ee = Symbol.for("react.suspense_list"), J = Symbol.for("react.memo"), V = Symbol.for("react.lazy"), de = Symbol.for("react.activity"), ue = Symbol.for("react.view_transition"), re = Symbol.for("react.client.reference"), l = w.__CLIENT_INTERNALS_DO_NOT_USE_OR_WARN_USERS_THEY_CANNOT_UPGRADE, d = Object.prototype.hasOwnProperty, _e = Array.isArray, he = console.createTask ? console.createTask : function() {
      return null;
    };
    w = {
      react_stack_bottom_frame: function(n) {
        return n();
      }
    };
    var ye, je = {}, be = w.react_stack_bottom_frame.bind(
      w,
      y
    )(), ge = he(h(y)), fe = {};
    q.Fragment = P, q.jsx = function(n, a, j) {
      var S = 1e4 > l.recentlyCreatedOwnerStacks++;
      if (S) {
        var B = Error.stackTraceLimit;
        Error.stackTraceLimit = 10;
        var z = Error("react-stack-top-frame");
        Error.stackTraceLimit = B;
      } else z = be;
      return p(
        n,
        a,
        j,
        !1,
        z,
        S ? he(h(n)) : ge
      );
    }, q.jsxs = function(n, a, j) {
      var S = 1e4 > l.recentlyCreatedOwnerStacks++;
      if (S) {
        var B = Error.stackTraceLimit;
        Error.stackTraceLimit = 10;
        var z = Error("react-stack-top-frame");
        Error.stackTraceLimit = B;
      } else z = be;
      return p(
        n,
        a,
        j,
        !0,
        z,
        S ? he(h(n)) : ge
      );
    };
  })()), q;
}
var Re;
function Ye() {
  return Re || (Re = 1, process.env.NODE_ENV === "production" ? te.exports = He() : te.exports = Ge()), te.exports;
}
var e = Ye();
const We = [
  { title: "库", dataIndex: "db", key: "db", width: 70, render: (r) => `db${r}` },
  {
    title: "key 数",
    dataIndex: "keys",
    key: "keys",
    width: 120,
    render: (r) => r > 0 ? /* @__PURE__ */ e.jsx(o, { color: "blue", children: r }) : /* @__PURE__ */ e.jsx("span", { style: { color: "#94a3b8" }, children: "0" })
  }
];
function Ke() {
  const [r, t] = x(null), [s, h] = x(null), [i, y] = x(null), [g, k] = x(!1), R = X(async () => {
    k(!0);
    try {
      const [w, _] = await Promise.all([N.overview(), N.instance()]);
      t(w), h(_), y(null);
    } catch (w) {
      t(null), y(w);
    } finally {
      k(!1);
    }
  }, []);
  ie(() => {
    R();
  }, [R]);
  const u = (r == null ? void 0 : r.embedded) ?? {}, p = (r == null ? void 0 : r.client) ?? {}, f = !!(r != null && r.foreignListenerSuspected || s != null && s.foreignListenerSuspected), c = !!(r != null && r.authorityDiverges);
  return /* @__PURE__ */ e.jsxs("div", { children: [
    i && /* @__PURE__ */ e.jsx(
      b,
      {
        type: "error",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: `读不到总览：${G(i)}`,
        description: "overview 与 __instance 一起失败 ⇒ 是 z-opc 侧路由/鉴权问题；只有 overview 失败而「实例与三态」能开，则是取数那一层的问题。"
      }
    ),
    f && /* @__PURE__ */ e.jsx(
      b,
      {
        type: "error",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: "下面 client 一栏的数字来自外部进程，不是本 JVM",
        description: "本 JVM 的 RedisServer 没 bind 上配置端口，而该端口 TCP 可连 ⇒ 应答者是别的进程（实测是 5 天前的另一个 z-opc boot jar）。embedded 一栏才是本 JVM 的真相，而它现在必然是空的。"
      }
    ),
    /* @__PURE__ */ e.jsxs(Z, { gutter: 16, children: [
      /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsxs(
        C,
        {
          title: /* @__PURE__ */ e.jsxs("span", { children: [
            "路 A · 进程内 MemoryStore ",
            /* @__PURE__ */ e.jsx(o, { color: "blue", children: "不可能跨进程" })
          ] }),
          size: "small",
          loading: g,
          extra: /* @__PURE__ */ e.jsx(ce, { icon: /* @__PURE__ */ e.jsx(ae, {}), loading: g, onClick: R, children: "刷新" }),
          children: [
            /* @__PURE__ */ e.jsxs(Z, { gutter: 8, children: [
              /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsx(F, { title: "dbsize（本 JVM 全部库）", value: u.dbsizeTotal ?? "-" }) }),
              /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsx(
                F,
                {
                  title: "内嵌 server 在跑",
                  value: u.running ? "是" : "否",
                  valueStyle: { color: u.running ? "#16a34a" : "#dc2626", fontSize: 20 }
                }
              ) })
            ] }),
            /* @__PURE__ */ e.jsx(
              U,
              {
                size: "small",
                column: 1,
                style: { marginTop: 12 },
                items: [
                  { key: "reflected", label: "反射取到 RedisServer", children: u.serverReflected ? /* @__PURE__ */ e.jsx(o, { color: "success", children: "是" }) : /* @__PURE__ */ e.jsx(o, { color: "warning", children: "否" }) },
                  { key: "reflectErr", label: "反射失败原因", children: u.reflectError ? /* @__PURE__ */ e.jsx("code", { children: u.reflectError }) : "-" },
                  { key: "port", label: "内嵌 RedisServer.getPort()", children: u.port ?? "-" },
                  { key: "dbCount", label: "库数量（DEFAULT_DB_COUNT）", children: u.dbCount ?? "-" },
                  { key: "hits", label: "命中 / 未命中", children: `${u.hits ?? "-"} / ${u.misses ?? "-"}` },
                  { key: "hitRate", label: "命中率", children: u.hitRate != null ? `${(u.hitRate * 100).toFixed(2)}%` : "-" },
                  { key: "evict", label: "被淘汰 key", children: u.evictions ?? "-" },
                  { key: "cmds", label: "累计命令数", children: u.totalCommands ?? "-" },
                  { key: "conns", label: "累计连接 / 当前连接", children: `${u.totalConnections ?? "-"} / ${u.connectedClients ?? "-"}` },
                  { key: "max", label: "maxEntries（0=不限）", children: u.maxEntries ?? "-" },
                  {
                    key: "uptime",
                    label: "store 存在时长",
                    children: /* @__PURE__ */ e.jsxs("span", { children: [
                      Je(u.storeUptimeSeconds),
                      /* @__PURE__ */ e.jsx("span", { style: { color: "#b45309" }, children: "（自构造起算，不代表在线时长）" })
                    ] })
                  },
                  { key: "unavailable", label: "不可用原因", children: u.unavailableReason ?? /* @__PURE__ */ e.jsx(o, { color: "success", children: "可用" }) }
                ]
              }
            )
          ]
        }
      ) }),
      /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsxs(
        C,
        {
          title: /* @__PURE__ */ e.jsxs("span", { children: [
            "路 B · zCacheClient（RESP DBSIZE） ",
            /* @__PURE__ */ e.jsx(o, { color: "purple", children: "业务看到的数" })
          ] }),
          size: "small",
          loading: g,
          children: [
            /* @__PURE__ */ e.jsxs(Z, { gutter: 8, children: [
              /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsx(F, { title: "dbsize", value: p.dbsize ?? "-" }) }),
              /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsx(
                F,
                {
                  title: "RESP 已连接",
                  value: p.transportConnected ? "是" : "否",
                  valueStyle: { color: p.transportConnected ? "#16a34a" : "#d97706", fontSize: 20 }
                }
              ) })
            ] }),
            /* @__PURE__ */ e.jsx(
              U,
              {
                size: "small",
                column: 1,
                style: { marginTop: 12 },
                items: [
                  { key: "present", label: "bean 存在", children: p.present ? /* @__PURE__ */ e.jsx(o, { color: "success", children: "是" }) : /* @__PURE__ */ e.jsx(o, { color: "warning", children: "否" }) },
                  { key: "target", label: "连接目标 host:port/db", children: p.target ? /* @__PURE__ */ e.jsx("code", { children: p.target }) : "-" },
                  {
                    key: "authority",
                    label: "应答者归属",
                    children: /* @__PURE__ */ e.jsxs("span", { children: [
                      /* @__PURE__ */ e.jsx(o, { color: $(p.authority).color, children: p.authority ?? "-" }),
                      /* @__PURE__ */ e.jsx("span", { style: { fontSize: 12, color: "#64748b" }, children: $(p.authority).text })
                    ] })
                  },
                  { key: "dbsizeErr", label: "DBSIZE 报错", children: p.dbsizeError ? /* @__PURE__ */ e.jsx("code", { children: p.dbsizeError }) : "-" },
                  { key: "probeErr", label: "连接探测报错", children: p.probeError ? /* @__PURE__ */ e.jsx("code", { children: p.probeError }) : "-" }
                ]
              }
            )
          ]
        }
      ) })
    ] }),
    /* @__PURE__ */ e.jsx(
      C,
      {
        style: { marginTop: 16 },
        title: "双路对账结论（这一格就是本卡的保真探针）",
        extra: c ? /* @__PURE__ */ e.jsx(o, { color: "error", children: "authorityDiverges = true" }) : /* @__PURE__ */ e.jsx(o, { color: "success", children: "authorityDiverges = false" }),
        children: /* @__PURE__ */ e.jsx(
          b,
          {
            showIcon: !0,
            type: c ? "error" : u.running ? "success" : "warning",
            message: c ? `路 A = ${u.dbsizeTotal ?? "-"}，路 B = ${p.dbsize ?? "-"} —— 两路不一致` : u.running ? `两路一致（都是 ${u.dbsizeTotal ?? "-"}）：内嵌 server 真在跑，且业务连的就是它` : '路 A 无数据可比（内嵌没 bind），所以现在还不能下"一致/不一致"的结论',
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
      C,
      {
        title: "每库 key 数（只有路 A 能按库统计；路 B 不能发 SELECT）",
        style: { marginTop: 16 },
        extra: /* @__PURE__ */ e.jsx(o, { color: "blue", children: "embedded 侧，16 库" }),
        children: !u.perDb || !u.perDb.length ? /* @__PURE__ */ e.jsx(
          b,
          {
            type: "info",
            showIcon: !0,
            message: "路 A 的 store 现在拿不到 ⇒ 没有按库数据",
            description: "原因见上面「不可用原因」/「反射失败原因」两格。这不是「缓存被清空了」。"
          }
        ) : /* @__PURE__ */ e.jsx(
          L,
          {
            size: "small",
            rowKey: "db",
            columns: We,
            dataSource: u.perDb,
            pagination: !1,
            summary: () => /* @__PURE__ */ e.jsxs(L.Summary.Row, { children: [
              /* @__PURE__ */ e.jsx(L.Summary.Cell, { index: 0, children: "合计" }),
              /* @__PURE__ */ e.jsx(L.Summary.Cell, { index: 1, children: u.dbsizeTotal })
            ] })
          }
        )
      }
    ),
    /* @__PURE__ */ e.jsx(C, { title: "口径说明", style: { marginTop: 16 }, children: /* @__PURE__ */ e.jsxs("div", { style: { fontSize: 12, color: "#64748b", lineHeight: 1.9 }, children: [
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
const Ce = {
  STRING: "blue",
  HASH: "purple",
  LIST: "cyan",
  SET: "orange",
  ZSET: "magenta",
  NONE: "default"
}, qe = [
  { value: "STRING", label: "STRING" },
  { value: "HASH", label: "HASH" },
  { value: "LIST", label: "LIST" },
  { value: "SET", label: "SET" },
  { value: "ZSET", label: "ZSET" },
  { value: "NONE", label: "NONE" }
];
function Ze(r) {
  return r == null ? /* @__PURE__ */ e.jsx("span", { style: { color: "#94a3b8" }, children: "nil" }) : typeof r == "object" ? r.notUtf8 ? /* @__PURE__ */ e.jsxs(se, { title: "非 UTF-8 字节（多半是序列化后的对象），这里给 base64", children: [
    /* @__PURE__ */ e.jsx(o, { color: "volcano", children: "binary" }),
    /* @__PURE__ */ e.jsxs("code", { style: { fontSize: 11 }, children: [
      String(r.base64).slice(0, 60),
      "…"
    ] })
  ] }) : /* @__PURE__ */ e.jsx("pre", { style: { margin: 0, fontSize: 11, whiteSpace: "pre-wrap" }, children: JSON.stringify(r, null, 2) }) : /* @__PURE__ */ e.jsx("span", { style: { wordBreak: "break-all" }, children: String(r) });
}
function Xe() {
  const [r, t] = x("auto"), [s, h] = x("*"), [i, y] = x(0), [g, k] = x(200), [R, u] = x(null), [p, f] = x([]), [c, w] = x(null), [_, v] = x(!1), [P, M] = x(null), [m, T] = x(null), [I, Q] = x(!1), [Y, ee] = x(null), J = X(async () => {
    v(!0);
    try {
      const l = await N.keys({ pattern: s || "*", db: i, limit: g, source: r });
      f(l.keys ?? []), w(l), M(null);
    } catch (l) {
      f([]), w(null), M(l);
    } finally {
      v(!1);
    }
  }, [s, i, g, r]);
  ie(() => {
    J();
  }, [J]);
  const V = X(async (l) => {
    T({ key: l }), Q(!0), ee(null);
    try {
      const d = await N.key({ key: l, db: i, source: r, maxElements: 50 });
      T(d);
    } catch (d) {
      T(null), ee(d);
    } finally {
      Q(!1);
    }
  }, [i, r]), de = Te(() => R ? p.filter((l) => l.type === R) : p, [p, R]), ue = Te(() => [
    {
      title: "key",
      dataIndex: "key",
      key: "key",
      ellipsis: !0,
      render: (l) => /* @__PURE__ */ e.jsx("a", { onClick: () => V(l), children: /* @__PURE__ */ e.jsx("code", { children: l }) })
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
      render: (l, d) => d != null && d.desyncSuspect ? /* @__PURE__ */ e.jsx(se, { title: d.desyncReason || "这一行的应答与所发命令对不上，不采信", children: /* @__PURE__ */ e.jsx(o, { color: "red", children: "应答错位·未采信" }) }) : l == null ? /* @__PURE__ */ e.jsx("span", { style: { color: "#94a3b8" }, children: "未采信" }) : /* @__PURE__ */ e.jsx(o, { color: Ce[l] || "default", children: l }),
      filters: qe.map((l) => ({ text: l.label, value: l.value })),
      onFilter: (l, d) => d.type === l
    },
    {
      title: "TTL",
      dataIndex: "ttlSeconds",
      key: "ttl",
      width: 170,
      unmeasured: "未测",
      render: (l, d) => d != null && d.ttlSecondsError ? /* @__PURE__ */ e.jsx(se, { title: d.ttlSecondsError, children: /* @__PURE__ */ e.jsx(o, { color: "red", children: "读数不可信" }) }) : l == null ? /* @__PURE__ */ e.jsx("span", { style: { color: "#94a3b8" }, title: d == null ? void 0 : d.ttlSecondsNote, children: "未测" }) : Ee(l)
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
      render: (l, d) => l == null ? /* @__PURE__ */ e.jsx("span", { style: { color: "#94a3b8" }, title: d == null ? void 0 : d.byteLengthNote, children: "未测" }) : l < 0 ? /* @__PURE__ */ e.jsx("span", { style: { color: "#94a3b8" }, children: "非 string" }) : le(l)
    },
    {
      title: "数据源",
      dataIndex: "authority",
      key: "authority",
      width: 190,
      unmeasured: "未采信",
      render: (l, d) => /* @__PURE__ */ e.jsx(o, { color: $((d == null ? void 0 : d.authority) ?? l).color, children: (d == null ? void 0 : d.authority) ?? l ?? "-" })
    }
  ], [V]), re = (c == null ? void 0 : c.sourceUsed) === "client";
  return /* @__PURE__ */ e.jsxs("div", { children: [
    /* @__PURE__ */ e.jsxs(C, { size: "small", style: { marginBottom: 12 }, children: [
      /* @__PURE__ */ e.jsxs(oe, { wrap: !0, size: 8, children: [
        /* @__PURE__ */ e.jsx("span", { children: "pattern" }),
        /* @__PURE__ */ e.jsx(
          Oe,
          {
            style: { width: 240 },
            value: s,
            allowClear: !0,
            prefix: /* @__PURE__ */ e.jsx($e, {}),
            placeholder: "glob：* ? [] （默认 * = 全部）",
            onChange: (l) => h(l.target.value),
            onPressEnter: J
          }
        ),
        /* @__PURE__ */ e.jsx("span", { children: "db" }),
        /* @__PURE__ */ e.jsx(
          we,
          {
            style: { width: 100 },
            value: i,
            onChange: y,
            disabled: re,
            options: Array.from({ length: 16 }, (l, d) => ({ value: d, label: `db${d}` }))
          }
        ),
        /* @__PURE__ */ e.jsx("span", { children: "limit" }),
        /* @__PURE__ */ e.jsx(Ne, { style: { width: 90 }, min: 1, max: 1e3, value: g, onChange: k }),
        /* @__PURE__ */ e.jsx("span", { children: "source" }),
        /* @__PURE__ */ e.jsxs(ne.Group, { value: r, onChange: (l) => t(l.target.value), children: [
          /* @__PURE__ */ e.jsx(ne.Button, { value: "auto", children: "auto" }),
          /* @__PURE__ */ e.jsx(ne.Button, { value: "embedded", children: "路 A 进程内" }),
          /* @__PURE__ */ e.jsx(ne.Button, { value: "client", children: "路 B RESP" })
        ] }),
        /* @__PURE__ */ e.jsx(ce, { icon: /* @__PURE__ */ e.jsx(ae, {}), loading: _, onClick: J, children: "查询" })
      ] }),
      re && /* @__PURE__ */ e.jsxs("div", { style: { marginTop: 8, fontSize: 12, color: "#b45309" }, children: [
        "当前走路 B（RESP），",
        /* @__PURE__ */ e.jsx("b", { children: "db 选择器已被忽略" }),
        "：",
        (c == null ? void 0 : c.dbIgnoreReason) || "SELECT 会永久改掉业务共用连接的活动库，所以路 B 不切库"
      ] })
    ] }),
    P && /* @__PURE__ */ e.jsx(
      b,
      {
        type: "error",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: `读不到 key 列表：${G(P)}`,
        description: /* @__PURE__ */ e.jsxs("span", { children: [
          '后端 503 的含义是"这条路取不到数"，不是"缓存是空的"。',
          r === "embedded" && " 你强制了路 A，而本 JVM 的内嵌 server 没 bind ⇒ 必然 503；",
          "换 source=client 或直接看「实例与三态」页确认归属。"
        ] })
      }
    ),
    (c == null ? void 0 : c.desyncRows) > 0 && /* @__PURE__ */ e.jsx(
      b,
      {
        type: "warning",
        showIcon: !0,
        style: { marginBottom: 8 },
        message: `路 B 本次有 ${c.desyncRows}/${c.totalRows} 行的应答对不上，这些行的类型/TTL 不参与展示`,
        description: c.desyncNote
      }
    ),
    /* @__PURE__ */ e.jsx(
      Me,
      {
        cardTitle: /* @__PURE__ */ e.jsxs("span", { children: [
          "key 列表 ",
          c && /* @__PURE__ */ e.jsxs(o, { color: "blue", children: [
            "sourceUsed = ",
            c.sourceUsed
          ] }),
          (c == null ? void 0 : c.authority) && /* @__PURE__ */ e.jsx(o, { color: $(c.authority).color, children: c.authority })
        ] }),
        cardExtra: c && /* @__PURE__ */ e.jsx(oe, { size: 8, children: /* @__PURE__ */ e.jsx(
          Be,
          {
            status: c.truncated ? "warning" : "default",
            text: c.truncated ? `后端 KEYS 命中 ${c.matchedTotal} 条，只取了前 ${c.limit} 条（KEYS 是 O(N)，硬上限 1000）` : `命中 ${c.matchedTotal ?? p.length} 条`
          }
        ) }),
        rows: de,
        rowKey: (l) => `${l.key}`,
        loading: _,
        columns: ue,
        emptyTitle: P ? "读取失败" : c ? "没有匹配的 key" : "还没取到数据",
        emptyDescription: P ? "见上方告警。" : c ? c.sourceUsed === "embedded" ? "路 A 的进程内 store 里这个 pattern 没有匹配项（本 JVM 自己那份，确定没跨进程）" : "路 B 无应答或该库确实为空 —— 先看「实例与三态」确认应答者是不是本 JVM" : "先点「查询」读一次。",
        tableProps: { size: "small" }
      }
    ),
    /* @__PURE__ */ e.jsxs(
      Le,
      {
        width: 720,
        open: !!m,
        title: m ? `key: ${m.key}` : "key 详情",
        onClose: () => T(null),
        destroyOnHidden: !0,
        children: [
          Y && /* @__PURE__ */ e.jsx(b, { type: "error", showIcon: !0, message: `读详情失败：${G(Y)}` }),
          !m && !Y && /* @__PURE__ */ e.jsx(Se, { description: "载入中…" }),
          m && /* @__PURE__ */ e.jsxs(e.Fragment, { children: [
            /* @__PURE__ */ e.jsx(
              U,
              {
                bordered: !0,
                size: "small",
                column: 2,
                items: [
                  { key: "src", label: "取数路", children: /* @__PURE__ */ e.jsx(o, { children: m.sourceUsed }) },
                  { key: "auth", label: "归属", children: /* @__PURE__ */ e.jsx(o, { color: $(m.authority).color, children: m.authority ?? "-" }) },
                  { key: "db", label: "库", children: m.dbIgnoredForClientPath ? /* @__PURE__ */ e.jsxs("span", { children: [
                    "db",
                    m.db,
                    "（",
                    /* @__PURE__ */ e.jsx("b", { children: "已忽略" }),
                    "，路 B 不切库）"
                  ] }) : `db${m.db ?? "-"}` },
                  { key: "type", label: "类型", children: m.desyncSuspect ? /* @__PURE__ */ e.jsx(se, { title: m.desyncReason || "应答与所发命令对不上", children: /* @__PURE__ */ e.jsx(o, { color: "red", children: "应答错位·未采信" }) }) : /* @__PURE__ */ e.jsx(o, { color: Ce[m.type] || "default", children: m.type ?? "未采信" }) },
                  { key: "exists", label: "存在", children: m.exists == null ? "-" : m.exists ? /* @__PURE__ */ e.jsx(o, { color: "success", children: "是" }) : /* @__PURE__ */ e.jsx(o, { children: "否" }) },
                  { key: "ttl", label: "TTL", children: Ee(m.ttlSeconds) },
                  { key: "pttl", label: "PTTL(ms)", children: m.pttlMillis ?? "-" },
                  { key: "ver", label: "keyVersion（路 A）", children: m.keyVersion ?? "-" },
                  m.error && { key: "err", label: "读取错误", span: 2, children: /* @__PURE__ */ e.jsx("code", { children: m.error }) }
                ].filter(Boolean)
              }
            ),
            /* @__PURE__ */ e.jsx(C, { size: "small", title: "值（按类型取，非 UTF-8 会降级成 base64）", style: { marginTop: 12 }, children: !m.value || !Object.keys(m.value).length ? /* @__PURE__ */ e.jsx(Se, { description: "该类型没有可读的值，或路 A/B 都取不到" }) : /* @__PURE__ */ e.jsx(
              U,
              {
                size: "small",
                column: 1,
                bordered: !0,
                items: Object.entries(m.value).map(([l, d]) => ({
                  key: l,
                  label: l,
                  children: Ze(d)
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
const Qe = [
  { title: "key", dataIndex: "key", key: "key", ellipsis: !0, render: (r) => /* @__PURE__ */ e.jsx("code", { children: r }) },
  {
    title: "访问次数",
    dataIndex: "accessCount",
    key: "accessCount",
    width: 110,
    sorter: (r, t) => r.accessCount - t.accessCount,
    render: (r) => /* @__PURE__ */ e.jsx(o, { color: "geekblue", children: r })
  },
  { title: "LFU 计数", dataIndex: "lfuCounter", key: "lfuCounter", width: 100 },
  {
    title: "已过期",
    dataIndex: "expired",
    key: "expired",
    width: 90,
    render: (r) => r ? /* @__PURE__ */ e.jsx(o, { color: "warning", children: "是" }) : /* @__PURE__ */ e.jsx(o, { color: "success", children: "否" })
  },
  { title: "TTL", dataIndex: "ttlSeconds", key: "ttlSeconds", width: 110, render: (r) => r === -1 ? "常驻" : `${r}s` },
  {
    title: "字节",
    dataIndex: "byteLength",
    key: "byteLength",
    width: 100,
    render: (r) => le(r)
  },
  {
    title: "最后访问",
    dataIndex: "lastAccessTime",
    key: "lastAccessTime",
    width: 180,
    render: (r) => r ? new Date(r).toLocaleString() : "-"
  }
];
function er({ name: r, fields: t }) {
  return t ? /* @__PURE__ */ e.jsx(C, { size: "small", title: /* @__PURE__ */ e.jsxs("span", { children: [
    "# ",
    r
  ] }), style: { marginBottom: 12 }, children: /* @__PURE__ */ e.jsx(
    U,
    {
      size: "small",
      column: 2,
      bordered: !0,
      items: Object.entries(t).map(([s, h]) => ({ key: s, label: s, children: /* @__PURE__ */ e.jsx("code", { children: h }) }))
    }
  ) }) : null;
}
function rr({ fp: r }) {
  var h, i;
  const t = r.verdict, s = t === "DIFFERENT_BUILD" ? "error" : t === "SAME_BUILD_CANDIDATE" ? "warning" : "info";
  return /* @__PURE__ */ e.jsx(
    b,
    {
      type: s,
      showIcon: !0,
      style: { marginTop: 4 },
      message: `构建指纹：${t === "DIFFERENT_BUILD" ? "应答者不是本次构建的产物" : t === "SAME_BUILD_CANDIDATE" ? "版本号一致，但不足以定身份" : "指纹不可用（一边取不到值，页面不猜）"}`,
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
          /* @__PURE__ */ e.jsx("code", { children: ((i = r.ownBuildConstants) == null ? void 0 : i.tcp_port) ?? "?" }),
          "／ 对面 ",
          /* @__PURE__ */ e.jsx("code", { children: r.responderTcpPort ?? "?" })
        ] }),
        /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12, color: "#64748b", marginTop: 4 }, children: r.note }),
        /* @__PURE__ */ e.jsx("div", { style: { fontSize: 11, color: "#94a3b8", marginTop: 2 }, children: r.ownConstantsSource })
      ] })
    }
  );
}
function nr() {
  const [r, t] = x(null), [s, h] = x(null), [i, y] = x(null), [g, k] = x(null), [R, u] = x(null), [p, f] = x(0), [c, w] = x(!1), _ = X(async () => {
    w(!0);
    try {
      const [T, I] = await Promise.all([N.info(), N.instance()]);
      t(T), y(I), k(null);
    } catch (T) {
      t(null), k(T);
    }
    try {
      h(await N.hotkeys({ db: p, limit: 50 })), u(null);
    } catch (T) {
      h(null), u(T);
    } finally {
      w(!1);
    }
  }, [p]);
  ie(() => {
    _();
  }, [_]);
  const v = r == null ? void 0 : r.embedded, P = !!(i != null && i.foreignListenerSuspected), M = (r == null ? void 0 : r.sections) ?? {}, m = (r == null ? void 0 : r.responderVsOwnBuild) ?? null;
  return /* @__PURE__ */ e.jsxs("div", { children: [
    g && /* @__PURE__ */ e.jsx(
      b,
      {
        type: "error",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: `读不到统计：${G(g)}`
      }
    ),
    P && /* @__PURE__ */ e.jsx(
      b,
      {
        type: "error",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: "下面 INFO 一栏是外部进程自报的，不是本 JVM 的运行时指标",
        description: "authorityOfClient = EXTERNAL_PROCESS。要看本 JVM 的数，只有路 A 那一栏（它现在可能是空的，因为内嵌没 bind）。"
      }
    ),
    /* @__PURE__ */ e.jsxs(Z, { gutter: 16, children: [
      /* @__PURE__ */ e.jsx(A, { span: 12, children: /* @__PURE__ */ e.jsx(
        C,
        {
          title: /* @__PURE__ */ e.jsxs("span", { children: [
            "路 A · 进程内计数 ",
            /* @__PURE__ */ e.jsx(o, { color: "blue", children: "本 JVM 独有" })
          ] }),
          size: "small",
          loading: c,
          children: v ? /* @__PURE__ */ e.jsxs(e.Fragment, { children: [
            /* @__PURE__ */ e.jsxs(Z, { gutter: 8, children: [
              /* @__PURE__ */ e.jsx(A, { span: 8, children: /* @__PURE__ */ e.jsx(F, { title: "dbsize", value: v.dbsizeTotal }) }),
              /* @__PURE__ */ e.jsx(A, { span: 8, children: /* @__PURE__ */ e.jsx(
                F,
                {
                  title: "命中率",
                  value: `${(v.hitRate * 100).toFixed(2)}%`
                }
              ) }),
              /* @__PURE__ */ e.jsx(A, { span: 8, children: /* @__PURE__ */ e.jsx(F, { title: "淘汰", value: v.evictions }) })
            ] }),
            /* @__PURE__ */ e.jsx(
              U,
              {
                size: "small",
                column: 1,
                style: { marginTop: 12 },
                bordered: !0,
                items: [
                  { key: "hits", label: "hits / misses", children: `${v.hits} / ${v.misses}` },
                  { key: "cmds", label: "累计命令", children: v.totalCommands },
                  { key: "clients", label: "当前连接", children: v.connectedClients },
                  { key: "max", label: "maxEntries", children: v.maxEntries },
                  { key: "heap", label: "JVM 堆（已用/上限）", children: `${le(v.jvmHeapUsedBytes)} / ${le(v.jvmHeapMaxBytes)}` },
                  { key: "cpu", label: "可用核数", children: v.jvmAvailableProcessors }
                ]
              }
            )
          ] }) : /* @__PURE__ */ e.jsx(
            b,
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
        C,
        {
          title: /* @__PURE__ */ e.jsxs("span", { children: [
            "路 B · 上游 INFO ",
            /* @__PURE__ */ e.jsx(o, { color: $(r == null ? void 0 : r.authorityOfClient).color, children: (r == null ? void 0 : r.authorityOfClient) ?? "-" })
          ] }),
          size: "small",
          loading: c,
          children: [
            (r == null ? void 0 : r.clientError) && /* @__PURE__ */ e.jsx(b, { type: "error", showIcon: !0, style: { marginBottom: 8 }, message: r.clientError }),
            (r == null ? void 0 : r.clientNote) && /* @__PURE__ */ e.jsx(b, { type: "warning", showIcon: !0, style: { marginBottom: 8 }, message: r.clientNote }),
            Object.keys(M).length === 0 && !(r != null && r.clientError) && !(r != null && r.clientNote) && /* @__PURE__ */ e.jsx(b, { type: "info", showIcon: !0, message: "INFO 为空 —— 应答者没返回任何分节" }),
            Object.entries(M).map(([T, I]) => /* @__PURE__ */ e.jsx(er, { name: T, fields: I }, T)),
            (r == null ? void 0 : r.hardcodedInUpstream) && /* @__PURE__ */ e.jsx(
              b,
              {
                type: "error",
                showIcon: !0,
                style: { marginTop: 4 },
                message: "INFO 里这两个字段是写死的字面量，不要拿去对照 jar 版本或端口",
                description: /* @__PURE__ */ e.jsx("ul", { style: { margin: 0, paddingLeft: 18 }, children: r.hardcodedInUpstream.map((T) => /* @__PURE__ */ e.jsx("li", { style: { fontSize: 12 }, children: T }, T)) })
              }
            ),
            m && /* @__PURE__ */ e.jsx(rr, { fp: m })
          ]
        }
      ) })
    ] }),
    (r == null ? void 0 : r.raw) && /* @__PURE__ */ e.jsx(C, { title: "INFO 原文（未加工，逐字符来自上游）", size: "small", style: { marginTop: 16 }, children: /* @__PURE__ */ e.jsx("pre", { style: { margin: 0, maxHeight: 260, overflow: "auto", fontSize: 12 }, children: r.raw }) }),
    /* @__PURE__ */ e.jsxs(
      C,
      {
        title: "热 key（路 A 的 LFU 埋点，只覆盖 string 类型）",
        style: { marginTop: 16 },
        size: "small",
        extra: /* @__PURE__ */ e.jsxs(oe, { children: [
          /* @__PURE__ */ e.jsx("span", { style: { fontSize: 12, color: "#64748b" }, children: "库" }),
          /* @__PURE__ */ e.jsx(
            we,
            {
              size: "small",
              style: { width: 96 },
              value: p,
              onChange: f,
              options: Array.from({ length: 16 }, (T, I) => ({ value: I, label: `db${I}` }))
            }
          ),
          /* @__PURE__ */ e.jsx(ce, { icon: /* @__PURE__ */ e.jsx(ae, {}), loading: c, onClick: _, children: "刷新" })
        ] }),
        children: [
          R && /* @__PURE__ */ e.jsx(
            b,
            {
              type: "warning",
              showIcon: !0,
              style: { marginBottom: 8 },
              message: `读不到热 key：${G(R)}`,
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
              /* @__PURE__ */ e.jsx(o, { color: $(s.authority).color, children: s.authority })
            ] }),
            /* @__PURE__ */ e.jsx(
              L,
              {
                size: "small",
                rowKey: "key",
                columns: Qe,
                dataSource: s.rows ?? [],
                pagination: De,
                locale: { emptyText: `db${p} 的 string store 里一个 key 都没有（路 A 口径，确定没跨进程）` }
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
const xe = [
  { title: "RESP 命令", dataIndex: "command", key: "command", width: 140, render: (r) => /* @__PURE__ */ e.jsx("code", { children: r }) },
  { title: "说明", dataIndex: "note", key: "note", ellipsis: !0 }
];
function O({ value: r, okText: t = "是", badText: s = "否", badColor: h = "error", unknown: i = "未探测" }) {
  return r == null ? /* @__PURE__ */ e.jsx(o, { children: i }) : r ? /* @__PURE__ */ e.jsx(o, { color: "success", children: t }) : /* @__PURE__ */ e.jsx(o, { color: h, children: s });
}
function tr({ value: r }) {
  const t = $(r);
  return /* @__PURE__ */ e.jsx(o, { color: t.color, children: r || "-" });
}
function sr() {
  const [r, t] = x(null), [s, h] = x(null), [i, y] = x(null), [g, k] = x(!1), R = X(async () => {
    k(!0);
    try {
      const f = await N.instance();
      t(f), y(null);
      try {
        h(await N.commandCatalog());
      } catch {
        h(null);
      }
    } catch (f) {
      t(null), y(f);
    } finally {
      k(!1);
    }
  }, []);
  ie(() => {
    R();
  }, [R]);
  const u = !i && r && r.foreignListenerSuspected, p = !i && r && !r.embeddedRunning;
  return /* @__PURE__ */ e.jsxs("div", { children: [
    i && /* @__PURE__ */ e.jsx(
      b,
      {
        type: "error",
        showIcon: !0,
        message: `自省接口调用失败：${G(i)}`,
        description: "/api/cache/__instance 由 z-opc 自己的 controller 直接应答，不经过任何 RESP 连接；它都不通说明是 z-opc 侧的路由/鉴权问题（302 到登录页 = 没带会话 Cookie）。"
      }
    ),
    p && !u && /* @__PURE__ */ e.jsx(
      b,
      {
        type: "warning",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: "内嵌 RedisServer 没有 bind 成功 —— 其余三页的空表是「没起」，不是「缓存里没有 key」",
        description: `configuredPort=${r.configuredPort} 上没有任何监听者。blockedReason：${r.blockedReason || "-"}`
      }
    ),
    u && /* @__PURE__ */ e.jsx(
      b,
      {
        type: "error",
        showIcon: !0,
        style: { marginBottom: 12 },
        message: `${r.configuredPort} 端口上有人在应答，但它不是本 JVM —— 这一路读到的是外部进程的缓存，不能算孵化结果`,
        description: `embeddedRunning=false（本 JVM 的 RedisServer 没 bind 上）而 ${r.configuredPort} TCP 可连 ⇒ 应答者必然不是本进程。请用 lsof -nP -iTCP:${r.configuredPort} -sTCP:LISTEN 取属主 PID，与本页「应答的 JVM」= ${r.jvm} 比对。实测本仓库当前状态：6379 属主是 5 天前的另一个 z-opc boot jar（交接文件 §二 E3/E5），本 JVM 的 stdout 里有 java.net.BindException: Address already in use。解法只有两种（让出端口，或用 -Dz.cache.server.port 换端口），都需要定夺 —— 页面不会替你占端口。`
      }
    ),
    /* @__PURE__ */ e.jsxs(
      C,
      {
        title: "这一路到底连的是谁",
        loading: g,
        extra: /* @__PURE__ */ e.jsx(oe, { children: /* @__PURE__ */ e.jsx(ce, { icon: /* @__PURE__ */ e.jsx(ae, {}), loading: g, onClick: R, children: "刷新" }) }),
        children: [
          /* @__PURE__ */ e.jsx(
            U,
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
                    /* @__PURE__ */ e.jsx(o, { color: "volcano", children: (r == null ? void 0 : r.surface) ?? "-" }),
                    /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12, color: "#64748b" }, children: r == null ? void 0 : r.httpSurfaceOfZCache })
                  ] })
                },
                {
                  key: "lifecycleBean",
                  label: "内嵌配置已装载",
                  children: r ? r.lifecycleBean ? /* @__PURE__ */ e.jsx(o, { color: "success", children: "是（z.cache.enabled=true）" }) : /* @__PURE__ */ e.jsx(o, { color: "error", children: "否" }) : "-"
                },
                {
                  key: "starterClaim",
                  label: "starter 自报 isServerStarted()",
                  children: /* @__PURE__ */ e.jsxs("span", { children: [
                    /* @__PURE__ */ e.jsx(
                      O,
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
                      O,
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
                  children: /* @__PURE__ */ e.jsx(O, { value: r == null ? void 0 : r.embeddedRunning, okText: "是", badText: "否" })
                },
                {
                  key: "boundPort",
                  label: "内嵌实际 bind 端口",
                  children: r ? r.boundPort > 0 ? /* @__PURE__ */ e.jsx(o, { color: "success", children: r.boundPort }) : /* @__PURE__ */ e.jsx(o, { color: "error", children: "0（没 bind 上）" }) : "-"
                },
                {
                  key: "store",
                  label: "进程内 store 可读（路 A）",
                  children: /* @__PURE__ */ e.jsx(
                    O,
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
                    O,
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
                  children: /* @__PURE__ */ e.jsx(O, { value: r == null ? void 0 : r.embeddedPortOwnedByThisJvm, badColor: "error" })
                },
                {
                  key: "foreign",
                  label: "外来监听者嫌疑",
                  children: r ? r.foreignListenerSuspected ? /* @__PURE__ */ e.jsx(o, { color: "error", children: "有（应答者不是本 JVM）" }) : /* @__PURE__ */ e.jsx(o, { color: "success", children: "无" }) : "-"
                },
                {
                  key: "clientBean",
                  label: "zCacheClient bean 存在（路 B）",
                  children: /* @__PURE__ */ e.jsx(O, { value: r == null ? void 0 : r.clientBeanPresent, badColor: "warning" })
                },
                {
                  key: "clientConn",
                  label: "RESP 连接已建立",
                  children: /* @__PURE__ */ e.jsx(
                    O,
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
                    O,
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
                  children: /* @__PURE__ */ e.jsx(o, { color: "success", children: "0 个（writeEndpoints 恒为 []，后端只有 @GetMapping）" })
                },
                {
                  key: "select",
                  label: "是否发过 SELECT",
                  children: /* @__PURE__ */ e.jsx(o, { color: "success", children: "从不（会改掉业务共用连接的活动库）" })
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
                  children: r != null && r.blockedReason ? /* @__PURE__ */ e.jsx("span", { style: { color: "#b45309" }, children: r.blockedReason }) : /* @__PURE__ */ e.jsx(o, { color: "success", children: "无" })
                }
              ]
            }
          ),
          /* @__PURE__ */ e.jsxs("div", { style: { marginTop: 12, fontSize: 12, color: "#64748b" }, children: [
            "只读端点清单：",
            ((r == null ? void 0 : r.readEndpoints) ?? []).map((f) => /* @__PURE__ */ e.jsx(
              "code",
              {
                style: { marginRight: 6 },
                children: f
              },
              f
            ))
          ] })
        ]
      }
    ),
    /* @__PURE__ */ e.jsx(
      C,
      {
        title: "本 controller 实际下发 / 白名单允许 / 永不下发的 RESP 命令",
        style: { marginTop: 16 },
        extra: /* @__PURE__ */ e.jsx(o, { color: "blue", children: "命令名是代码常量，HTTP 侧无法指定" }),
        children: s ? /* @__PURE__ */ e.jsxs(e.Fragment, { children: [
          /* @__PURE__ */ e.jsx("div", { style: { fontSize: 12, color: "#64748b", marginBottom: 8 }, children: s.provenance }),
          /* @__PURE__ */ e.jsx(
            L,
            {
              size: "small",
              rowKey: "command",
              pagination: !1,
              columns: xe,
              locale: { emptyText: /* @__PURE__ */ e.jsx(pe, { title: "没有已下发命令", size: "sm" }) },
              dataSource: s.commandsActuallyIssued ?? [],
              title: () => {
                var f;
                return /* @__PURE__ */ e.jsx("span", { children: /* @__PURE__ */ e.jsxs(o, { color: "success", children: [
                  "只读 · 本 controller 真正会发的（",
                  ((f = s.commandsActuallyIssued) == null ? void 0 : f.length) ?? 0,
                  " 条）"
                ] }) });
              }
            }
          ),
          s.notCountedAsIssued ? /* @__PURE__ */ e.jsx(
            b,
            {
              style: { marginTop: 8 },
              type: "warning",
              showIcon: !0,
              message: "为什么 PING 不算「已下发」",
              description: s.notCountedAsIssued
            }
          ) : null,
          /* @__PURE__ */ e.jsx(
            L,
            {
              style: { marginTop: 12 },
              size: "small",
              rowKey: "command",
              pagination: !1,
              columns: xe,
              locale: { emptyText: /* @__PURE__ */ e.jsx(pe, { title: "只读白名单为空", size: "sm" }) },
              dataSource: s.readOnlyCommandsAllowedByWhitelist ?? [],
              title: () => /* @__PURE__ */ e.jsx("span", { children: /* @__PURE__ */ e.jsx(o, { color: "processing", children: "只读白名单 = 上限集合（safeSend 入口校验用）" }) })
            }
          ),
          s.whitelistSemantics ? /* @__PURE__ */ e.jsx("div", { style: { marginTop: 8, fontSize: 12, color: "#64748b" }, children: s.whitelistSemantics }) : null,
          /* @__PURE__ */ e.jsx(
            L,
            {
              style: { marginTop: 12 },
              size: "small",
              rowKey: "command",
              pagination: !1,
              columns: xe,
              locale: { emptyText: /* @__PURE__ */ e.jsx(pe, { title: "没有此类命令", size: "sm" }) },
              dataSource: s.neverIssuedDespiteBeingSupportedByServer ?? [],
              title: () => /* @__PURE__ */ e.jsx("span", { children: /* @__PURE__ */ e.jsx(o, { color: "error", children: "server 支持但本 controller 永不下发" }) })
            }
          )
        ] }) : /* @__PURE__ */ e.jsx(
          b,
          {
            type: "info",
            showIcon: !0,
            message: r ? "命令目录读不到（不影响上面三态结论）" : "等自省接口回来再看"
          }
        )
      }
    ),
    /* @__PURE__ */ e.jsx(C, { title: "「上游自报存活时长」这一招在 z-cache 上不可用", style: { marginTop: 16 }, children: /* @__PURE__ */ e.jsx(
      b,
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
function mr() {
  return /* @__PURE__ */ e.jsxs(Pe, { children: [
    /* @__PURE__ */ e.jsx(W, { index: !0, element: /* @__PURE__ */ e.jsx(Ie, { to: "overview", replace: !0 }) }),
    /* @__PURE__ */ e.jsx(W, { path: "overview", element: /* @__PURE__ */ e.jsx(Ke, {}) }),
    /* @__PURE__ */ e.jsx(W, { path: "keys", element: /* @__PURE__ */ e.jsx(Xe, {}) }),
    /* @__PURE__ */ e.jsx(W, { path: "stats", element: /* @__PURE__ */ e.jsx(nr, {}) }),
    /* @__PURE__ */ e.jsx(W, { path: "instance", element: /* @__PURE__ */ e.jsx(sr, {}) })
  ] });
}
const or = "# z-cache-component\n\nz-cache（Redis 兼容缓存，自研）跨仓唯一前端面：把「缓存总览 / 键浏览 / 统计 / 实例自省」\n这一组**只读**的缓存运维视图收敛成一个可复用的 React 组件包，由 `z-cache-suit` 独立壳、\nz-team 的 OpsWorkbench（`/cache/*` 通配挂载）或 z-opc 主壳挂载。\n\n- **npm 包名**：`@yuku123/z-cache-component`\n- **宿主接入**：`@yuku123/z-cache-component/pages`（manifest + 页面组件），主壳可只取 `ComponentDocPage`\n- **后端**：z-opc 侧的 `CacheProxyController`（`GET /api/cache/**`）—— **它不是转发代理**，\n  而是用 z-cache 自己的 Java API 现读现拼\n- **技术栈**：React 19 + antd 6 + react-router-dom 7 + vite（library mode，双入口 `.` / `./pages`）\n\n---\n\n## 一、能力概览\n\n包里有**两套**页面入口，路由前缀不同，别混：\n\n### 1.1 manifest 面（`/z-cache/*`，`src/pages-manifest.jsx`）\n\n| 页面 | 路由（相对 `/z-cache/*`） | 源文件 | 能力 |\n|------|--------------------------|--------|------|\n| 首页 | `home` | `pages/HomePage.jsx` | 读 `userInfo` 显示登录人，按 `menuItems` 渲染功能入口卡片 |\n| 总览 | `overview` | `pages/Overview.jsx` | 缓存总览（`/cache/overview` + `/cache/info`） |\n| 键浏览 | `keys` | `pages/Keys.jsx` | 键浏览（`/cache/keys`）+ 单键详情（`/cache/key`） |\n| 登录 | （不在菜单内） | `pages/LoginPage.jsx` | mock 登录，写 `token` / `userInfo` 后跳 `/z-cache/home` |\n| 组件文档 | `docs` | `pages/ComponentDoc.jsx` | 本文件渲染页（左侧 240px TOC + 右侧正文） |\n\n### 1.2 CacheApp 面（`/cache/*`，`src/pages/CacheApp.jsx`）\n\n`CacheApp` 是给 OpsWorkbench 通配挂载用的**子路由集合**，入口重定向到 `overview`：\n\n| 子路由 | 源文件 | 能力 |\n|--------|--------|------|\n| `overview` | `pages/OverviewPage.jsx` | 缓存总览 —— 双路对账（路 A 进程内 MemoryStore vs 路 B zCacheClient） |\n| `keys` | `pages/KeysPage.jsx` | 键浏览（支持切库 / 切数据源 / 类型筛选 / 详情抽屉） |\n| `stats` | `pages/StatsPage.jsx` | 热点 key 统计（`/cache/hotkeys`）+ 实例自省 |\n| `instance` | `pages/InstanceStatus.jsx` | 实例自省（`/cache/__instance`）+ 命令目录（`/cache/command-catalog`） |\n\n## 二、导出面\n\n```js\n// 主入口（. 桶）：数据源 + 域挂载点\nimport {cacheApi, AUTHORITY_META, authorityTag, cacheErrorText, isNotBoundError,\n        fmtBytes, fmtTtl, fmtUptime, asText, CacheApp} from '@yuku123/z-cache-component'\n\n// 宿主组装面（./pages 桶）：manifest + 页面组件\nimport {menuItems, routes, HomePage, LoginPage, Overview, Keys, ComponentDocPage} from '@yuku123/z-cache-component/pages'\n\n// 组件文档页（主壳接线用，名字全仓统一）\nimport {ComponentDocPage} from '@yuku123/z-cache-component'\n```\n\n## 三、API 端点\n\n请求实例是基座的默认 `request`（`@yuku123/z-frontend-common` 的 `createRequest()` 预建实例，\n`baseURL` 默认 `/api`），token 走 `Authorization: Bearer`。\n下表路径是 `src/services/api.js` 里 `cacheApi` 定义的**实际路径**（拼上 baseURL 即 `/api/cache/**`）。\n\n| 方法 | 路径 | 说明 |\n|---|---|---|\n| GET | `/api/cache/__instance` | 实例自省（是否 bind 上、store 是否可用、client 是否连上、authority 判定） |\n| GET | `/api/cache/overview` | 缓存总览（路 A / 路 B 双路数据 + `authorityDiverges`） |\n| GET | `/api/cache/keys` | 键浏览（query：`pattern` / `limit` / `db` / `source`） |\n| GET | `/api/cache/key` | 单键详情（query：`key` / `db` / `source` / `maxElements`） |\n| GET | `/api/cache/info` | 服务端 INFO（`Server` / `Clients` / `Memory` / `Stats` / `Keyspace` 五段） |\n| GET | `/api/cache/hotkeys` | 热点 key（query：`db` / `limit`） |\n| GET | `/api/cache/command-catalog` | RESP 命令目录（实际发过哪些命令 / 白名单语义） |\n\n### 3.1 两条取数路（这是本包最需要先讲清楚的一件事）\n\nz-cache 的上游**根本没有 HTTP 面** —— 全仓 grep `HttpServer|RestController|createContext`\n在 `z-cache` 下零命中，唯一的 server 是 `RedisServer` 的 Netty `ServerBootstrap`，\n讲的是 **RESP2 over TCP**。⇒ `CacheProxyController`（文件名沿用需求指定，但它不是 proxy）\n不转发任何 HTTP。\n\n同一个 JVM 内有两条**互不相关**的取数路：\n\n- **路 A = 反射** `ZCacheEmbeddedServerConfig.SHARED_SERVER` → `RedisServer.getStore()` → `MemoryStore`\n  只读方法。不开 socket、不碰端口，**物理上不可能读到别的进程** ⇒「本 JVM 自己那份」的权威。\n- **路 B = 容器里已存在的 `zCacheClient` bean**（starter 自动装配，业务代码用的就是它）\n  ⇒「业务此刻真正看到的那份」。\n\n页面首屏就是把 A 和 B 并排 + 显式比对（`authorityDiverges`）。\n\n### 3.2 authority 三态\n\n| 取值 | 含义 |\n|------|------|\n| `THIS_JVM_IN_PROCESS` | 本 JVM 进程内对象（不可能跨进程） |\n| `THIS_JVM_EMBEDDED` | 本 JVM 内嵌 server 应答 |\n| `EXTERNAL_PROCESS` | 外部进程应答 —— 6379 的属主不是本 JVM，这一路读到的是别的进程的缓存 |\n| `NO_ANSWER` | 没有任何 server 在应答 |\n| `UNKNOWN` | 连上了，但无法判定连的是谁 |\n\n## 四、页面说明\n\n### 4.1 首页\n\n登录后的着陆页。读 `localStorage.userInfo` 显示欢迎语与角色标签，再把 `menuItems`（去掉首页自身）\n渲染成入口卡片 —— 菜单加一项，首页卡片自动多一张，不用改页面代码。\n\n### 4.2 总览\n\n`pages/Overview.jsx`：`/cache/overview` + `/cache/info` 两个接口并排渲染，\n其中 `/cache/info` 失败时降级为 `null`，不拖垮主表。\n\n`pages/OverviewPage.jsx`（CacheApp 版）：双路对账，逐库列出 key 数、命中率等。\n\n### 4.3 键浏览\n\n`pages/Keys.jsx`：`/cache/keys` 分页 + 点行开 `/cache/key` 详情。\n\n`pages/KeysPage.jsx`（CacheApp 版）：在此基础上支持切库（`db`）、切数据源（`source`）、\n类型筛选（`type`）、详情抽屉。\n\n⚠ 类型取值域两路统一成**大写**枚举名（`STRING` / `HASH` / `LIST` / `SET` / `ZSET` / `NONE`）——\n旧版拿大写去比小写（server 回 `\"string\"`）⇒ 类型筛选永远 0 命中。\n\n### 4.4 统计\n\n`pages/StatsPage.jsx`：热点 key 表（key / 访问次数 / LFU 计数 / 是否已过期）+ 实例自省。\n热点表默认取 `db` 下 `limit: 50`。\n\n### 4.5 实例自省\n\n`pages/InstanceStatus.jsx`：`/cache/__instance` + `/cache/command-catalog`。\n把「自省接口本身不通」（z-opc 侧路由/鉴权问题）与「自省通了但内嵌没起」\n（中间件问题）分成两套提示，判据是 HTTP 503 且 `source === 'z-opc-cache-read-api'`。\n\n### 4.6 登录\n\n当前是 **mock 模式**：比对一对写死在 `src/pages/LoginPage.jsx` 里的管理员凭据\n（明文口令不在本文复述，见源码），成功后写 `localStorage.token` 与 `localStorage.userInfo`，\n再 `window.location.href = '/z-cache/home'`。CTC/SSO 尚未接入。\n\n### 4.7 组件文档\n\n本文件。内容以 `?raw` 在编译期内联，零额外请求；左侧 240px TOC 随滚动联动。\n\n## 五、配置\n\n| 配置项 | 默认值 | 说明 |\n|---|---|---|\n| 请求实例 | 基座默认 `request` | `baseURL` 默认 `/api`，token 走 `Authorization: Bearer`（读 `localStorage.token`） |\n| 热点 key 条数 | `50` | `hotkeys` 的 `limit` |\n| 详情元素上限 | `50` | `KeysPage` 调 `key` 时传的 `maxElements` |\n\n## 六、已知限制\n\n- **本卡是只读的**：后端只有 `@GetMapping`，前端也就不会有「清空 / 批量删除 / 设值」这类按钮。\n  写面单独立卡，不在本包范围内。\n- **切库（SELECT）被刻意禁用**：`zCacheClient` 是**业务共用连接**，发一次 SELECT 会把它永久留在 n 库，\n  之后所有业务的 SET/GET 都写错库。所以路 B 的接口一律忽略 `db` 参数\n  （响应里 `dbIgnoredForClientPath=true` 就是这个意思）；要按库浏览请用 `source=embedded`。\n- **路 B 的 `byteLength` 恒为 null**（路 B 不发 `STRLEN`）⇒ 页面显示「未测」，\n  不是「非 string」—— 后者会把「没测」渲染成否定结论。\n- **路 B 的 `type` 可能为 null**：null 且 `desyncSuspect=true` ⇒ 这一行的应答与所发命令对不上（串台），\n  **不采信**，原始应答留在 `rawReply`、判据在 `desyncReason`。\n- **TTL 语义**：`-1` = 常驻、`-2` = 不存在，直接显示成「负数秒」会被读成 bug，所以 `fmtTtl` 单独翻译。\n- **没有任何页面调用的接口**：无（`cacheApi` 的 7 个方法都有页面在用）。\n- 直达 / 刷新需要四处接线都齐（大需求 004 的四级路由契约）：MENU_GROUPS「缓存」组 ↔\n  OPS_ROUTES 的 `/cache` + `/cache/*` ↔ main.jsx 顶层 `<Route path=\"/cache/*\">` ↔\n  `MainWebConfig.spaPaths` 的 `/cache/**`。缺 main.jsx 那条 = 白屏，缺 spaPaths 那条 = 直达 404。\n- z-cache 已被 z-opc 主壳的 `domainRoutes.ts` 与 z-team 的 OpsWorkbench 引用\n  ⇒ `/z-cache/docs` 在平台主壳里**可达**。\n\n## 七、变更记录\n\n| 版本 | 变更 |\n|---|---|\n| 0.1.2 | 新增组件文档页 `ComponentDocPage`（lead 008 §7）：`src/DOC.md` + 基座 `ComponentDoc` 薄包装 |\n| 0.1.2 | 首版：缓存总览 / 键浏览 / 统计 / 实例自省 + manifest + CacheApp 挂载点 |\n";
function pr() {
  return /* @__PURE__ */ e.jsx(Fe, { markdown: or, title: "组件文档 — z-cache" });
}
export {
  Ue as A,
  mr as C,
  sr as I,
  nr as S,
  pr as a,
  Ve as b,
  $ as c,
  N as d,
  G as e,
  le as f,
  Ee as g,
  Je as h,
  hr as i,
  e as j
};
