/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

/**
 * Swan-compatible global API evaluated before a plugin script.
 *
 * Two host entry points live here, and both exist for the same reason — the plugin script and the
 * host must not disagree about what a plugin declared:
 * - [`swan`] is the canonical plugin API surface; [`foxy`] stays as a compatibility alias.
 * - [`__run`] invokes one plugin lifecycle callback and returns its emitted events.
 * - [`__describe`] runs `configure(ui, locale)` and returns the declared settings as JSON, so the
 *   settings screen and the session both read the *same* declaration instead of duplicating it.
 *
 * Secrets are never handed to the script: `connect`/`post` forward the *field name* only, and the
 * host resolves and injects the value when it opens the connection. Crypto requests similarly
 * name a secret field; only the resulting signature is returned to JavaScript.
 */
internal object JsPrelude {
    val SOURCE: String = """
var __events = [];
var __fields = [];
var __plugin = null;
var __apps = {};
var __pending = null;
var __native = {
  locale: __n_locale, wsConnect: __n_wsConnect, wsWaitOpen: __n_wsWaitOpen, wsSend: __n_wsSend,
  wsSendBinary: __n_wsSendBinary, wsSendGzipFrame: __n_wsSendGzipFrame, wsClose: __n_wsClose,
  httpPost: __n_httpPost, concatBase64: __n_concatBase64, wavBase64: __n_wavBase64, utf8Base64: __n_utf8Base64,
  base64Utf8: __n_base64Utf8, byteLength: __n_byteLength, sliceBase64: __n_sliceBase64,
  readUInt8: __n_readUInt8, readInt32BE: __n_readInt32BE, gunzipUtf8: __n_gunzipUtf8,
  hmacSha1Base64: __n_hmacSha1Base64
};
function __str(value) { return value === undefined || value === null ? "" : String(value); }
var swan = {
  locale: __native.locale(),
  plugin: {
    register: function (p) { __plugin = p; return p; }
  },
  app: {
    sleep: function (milliseconds) { return __n_appSleep(Number(milliseconds)); },
    register: function (app) {
      if (!app || !/^[a-z0-9][a-z0-9._-]{0,63}$/.test(app.id) ||
          typeof app.onOpen !== "function" || __apps[app.id]) throw new Error("Invalid or duplicate app ID");
      __apps[app.id] = app;
    }
  },
  editor: {
    getSelection: function () { return __n_appEditor("getSelection", ""); },
    getText: function () { return __n_appEditor("getText", ""); },
    setComposition: function (text) { return __n_appEditor("setComposition", __str(text)); },
    clearComposition: function () { return __n_appEditor("clearComposition", ""); },
    commit: function (text) { return __n_appEditor("commit", __str(text)); }
  },
  credentials: { list: function () { return JSON.parse(__n_appCredentials()); } },
  ui: { dialog: {
    show: function (spec) { return spec; },
    update: function (spec) { return __n_appUi("update", JSON.stringify(spec)); },
    setValue: function (id, value) { return __n_appUi("setValue", JSON.stringify({id: id, value: value})); },
    setEnabled: function (id, enabled) { return __n_appUi("setEnabled", JSON.stringify({id: id, enabled: enabled})); },
    setLoading: function (loading) { return __n_appUi("setLoading", JSON.stringify({loading: loading})); },
    setError: function (message) { return __n_appUi("setError", JSON.stringify({message: message})); },
    close: function () { return __n_appUi("close", "{}"); }
  } },
  voice: {
    ready: function () { __events.push({ ready: true }); },
    partial: function (text) { __events.push({ partial: String(text) }); },
    final: function (text) { __events.push({ final: String(text) }); },
    error: function (message) { __events.push({ error: String(message) }); },
    ended: function () { __events.push({ ended: true }); }
  },
  websocket: {
    connect: function (url, secretField, authHeader, authPrefix, headersJson) {
      return __native.wsConnect(
        __str(url), __str(secretField), __str(authHeader), __str(authPrefix), __str(headersJson));
    },
    waitOpen: function (socket) { return __native.wsWaitOpen(__str(socket)); },
    send: function (socket, text) { return __native.wsSend(__str(socket), String(text)); },
    sendBinary: function (socket, base64) { return __native.wsSendBinary(__str(socket), __str(base64)); },
    sendGzipFrame: function (socket, prefixB64, payloadB64) {
      return __native.wsSendGzipFrame(__str(socket), __str(prefixB64), __str(payloadB64));
    },
    close: function (socket) { return __native.wsClose(__str(socket)); }
  },
  http: {
    supportsConfiguredEndpoints: true,
    request: function (options) { return __n_appHttp(JSON.stringify(options)); },
    post: function (url, secretField, jsonBody, headersJson) {
      return __native.httpPost(__str(url), __str(secretField), String(jsonBody), __str(headersJson));
    }
  },
  audio: {
    wavBase64: function (pcmBase64, sampleRate) {
      return __native.wavBase64(__str(pcmBase64), __str(sampleRate));
    }
  },
  crypto: {
    hmacSha1Base64: function (secretField, text) {
      return __native.hmacSha1Base64(__str(secretField), __str(text));
    }
  },
  binary: {
    concatBase64: function (chunks) { return __native.concatBase64(JSON.stringify(chunks)); },
    utf8Base64: function (text) { return __native.utf8Base64(__str(text)); },
    base64Utf8: function (b64) { return __native.base64Utf8(__str(b64)); },
    byteLength: function (b64) { return __native.byteLength(__str(b64)); },
    readUInt8: function (b64, index) { return __native.readUInt8(__str(b64), __str(index)); },
    readInt32BE: function (b64, offset) { return __native.readInt32BE(__str(b64), __str(offset)); },
    sliceBase64: function (b64, offset, length) {
      return __native.sliceBase64(__str(b64), __str(offset), __str(length));
    },
    gunzipUtf8: function (b64) { return __native.gunzipUtf8(__str(b64)); }
  }
};
// Compatibility alias for plugins written against the upstream Foxy API.
var foxy = swan;

function __field(kind, key, label, defaultValue, settings, options) {
  var s = settings || {};
  return {
    kind: kind,
    key: __str(key),
    label: __str(label),
    description: __str(s.description),
    "default": __str(defaultValue),
    options: options || [],
    allowCustom: s.allowCustom === true,
    visibleWhen: s.visibleWhen || null,
    defaultWhen: s.defaultWhen || null
  };
}
function __options(list) {
  var out = [];
  if (!list) return out;
  for (var i = 0; i < list.length; i++) {
    var item = list[i];
    if (item === null || item === undefined) continue;
    if (typeof item === "object") {
      out.push({ label: __str(item.label !== undefined ? item.label : item.value), value: __str(item.value) });
    } else {
      out.push({ label: __str(item), value: __str(item) });
    }
  }
  return out;
}

// Runs configure(ui, locale) and returns the declared settings. A plugin that fails to configure
// yields an empty list rather than breaking the settings screen.
function __describe(locale) {
  __fields = [];
  var ui = {
    text: function (key, label, defaultValue, settings) {
      __fields.push(__field("text", key, label, defaultValue, settings, null));
      return defaultValue;
    },
    secret: function (key, label, settings) {
      __fields.push(__field("secret", key, label, "", settings, null));
      return "";
    },
    select: function (key, label, options, defaultValue, settings) {
      __fields.push(__field("select", key, label, defaultValue, settings, __options(options)));
      return defaultValue;
    },
    switch: function (key, label, defaultValue, settings) {
      __fields.push(__field("switch", key, label, defaultValue === true ? "true" : "false", settings, null));
      return defaultValue;
    },
    number: function (key, label, defaultValue, settings) {
      __fields.push(__field("number", key, label, defaultValue, settings, null));
      return defaultValue;
    }
  };
  try {
    if (__plugin && typeof __plugin.configure === "function") __plugin.configure(ui, __str(locale));
  } catch (e) {
    __fields = [];
  }
  return JSON.stringify(__fields);
}

// 返回插件 register() 里的完整元信息。导入流程用它判断「这是不是一个本宿主能跑的插件」，
// 并把脚本自报的 id / 名称 / 版本 / 域名摆给用户确认——域名白名单是外部脚本唯一的真实边界，
// 不能让用户看不见。
function __meta() {
  var out = { id: "", name: "", version: "", hosts: [], features: [], apps: [] };
  try {
    var p = __plugin;
    if (!p) return JSON.stringify(out);
    out.id = __str(p.id);
    out.name = __str(p.name);
    out.version = __str(p.version);
    Object.keys(__apps).forEach(function (id) {
      var app = __apps[id];
      out.apps.push({id: id, label: __str(app.label), permissions: app.permissions || []});
    });
    var hosts = p.hosts;
    if (hosts) for (var i = 0; i < hosts.length; i++) out.hosts.push(__str(hosts[i]));
    var features = p.features;
    if (features) for (var j = 0; j < features.length; j++) {
      var f = features[j];
      if (!f) continue;
      out.features.push({
        type: __str(f.type), id: __str(f.id), label: __str(f.label), sampleRate: f.sampleRate
      });
    }
  } catch (e) {}
  return JSON.stringify(out);
}

// 返回插件在 register() 里声明的 hosts。宿主用它核对「脚本想连的」是否都被清单授权，
// 否则连接会在 net 层被拒，用户只看到一句「Host is not declared」，根本不知道是脚本换了。
function __hosts() {
  var out = [];
  try {
    var list = __plugin && __plugin.hosts;
    if (list) for (var i = 0; i < list.length; i++) out.push(__str(list[i]));
  } catch (e) {}
  return JSON.stringify(out);
}

function __takeEvents() { var out = __events; __events = []; return out; }
function __errorText(e) {
  if (e === null || e === undefined) return "";
  return __str(e.message !== undefined ? e.message : e);
}
function __invoke(name, ctx) {
  if (name === "app") return __apps[ctx.appId].onOpen(ctx);
  if (name === "start") return __plugin.start(ctx);
  if (name === "audio") return __plugin.audio(ctx);
  if (name === "stop") return __plugin.stop(ctx);
  if (name === "onMessage") return __plugin.onMessage(ctx);
  throw new Error("Unknown plugin callback: " + name);
}

// 回调分两拍：__run 立即给出「结果」或「还在等」的标记，__pump 之后反复拍一次。
// 原因见 JsInvoker：quickjs-kt 的 evaluate 只等待求值时仍未落地的 Promise，
// 插件回调在一次微任务里就 resolve 时它会把 Promise 对象本身按字符串返回。
function __run(name, argJson) {
  __events = [];
  __pending = null;
  var ctx = JSON.parse(argJson);
  var out;
  try {
    out = __invoke(name, ctx);
  } catch (e) {
    return JSON.stringify({ error: __errorText(e), events: __takeEvents() });
  }
  if (out !== null && out !== undefined && typeof out.then === "function") {
    var slot = { done: false, value: undefined, error: null };
    __pending = slot;
    out.then(
      function (value) { slot.done = true; slot.value = value; },
      function (reason) { slot.done = true; slot.error = reason; });
    return JSON.stringify({ pending: true, events: __takeEvents() });
  }
  return JSON.stringify({ result: out === undefined ? null : out, events: __takeEvents() });
}

function __pump() {
  var slot = __pending;
  if (slot === null) return JSON.stringify({ result: null, events: __takeEvents() });
  if (!slot.done) return JSON.stringify({ pending: true, events: __takeEvents() });
  __pending = null;
  if (slot.error !== null && slot.error !== undefined) {
    return JSON.stringify({ error: __errorText(slot.error), events: __takeEvents() });
  }
  return JSON.stringify({ result: slot.value === undefined ? null : slot.value, events: __takeEvents() });
}
""".trimIndent()
}
