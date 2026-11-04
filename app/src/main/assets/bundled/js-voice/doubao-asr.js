// Doubao (Volcengine) bigmodel ASR plugin for Foxy — mirrors the qwen-asr.js voice.asr contract.
//
// Protocol: Volcengine bigmodel WebSocket binary framing (docs/6561/1354869).
//   Frame = [Header 4B][Sequence 4B?][PayloadSize 4B][Payload]
//   Header byte0 = (protoVer<<4)|headerSize = 0x11
//            byte1 = (messageType<<4)|flags: 0x10 full request, 0x20 audio, 0x22 audio last,
//                    server 0x91/0x93 response, 0xF0 error
//            byte2 = (serialization<<4)|compression: 0x11 JSON+Gzip, 0x01 raw+Gzip
//   Host API used:
//     foxy.websocket.connect(endpoint, secretKeyName, authHeaderName, authPrefix, extraHeadersJson)
//     foxy.websocket.sendGzipFrame(socket, prefixBase64, payloadBase64)
//       -> host gzips payload and sends prefix || be32(gzipLen) || gzip(payload)
//     binary frames arrive on onMessage as message.binaryBase64
//
// Streaming:  wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async
// Single-shot: wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream (streaming input, one result)

const DEFAULTS = {
  streamEndpoint: "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
  flashEndpoint: "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream",
  resourceIds: {
    apiKey: "volc.seedasr.sauc.duration",
    legacy: "volc.bigasr.sauc.duration"
  }
};

const STREAM_MODELS = ["bigmodel", "doubao-seed-asr-2.0"];
const FLASH_MODELS = ["bigmodel", "doubao-seed-asr-2.0"];

const RESOURCE_IDS = {
  "volc.bigasr.sauc.duration": "1.0 小时版",
  "volc.bigasr.sauc.concurrent": "1.0 并发版",
  "volc.seedasr.sauc.duration": "2.0 小时版",
  "volc.seedasr.sauc.concurrent": "2.0 并发版"
};

const STRINGS = {
  en: {
    name: "Doubao ASR",
    apiKey: "Volcengine API key",
    apiKeyDescription: "Stored securely by Foxy; never exposed to the plugin script.",
    authMode: "Auth mode",
    apiKeyMode: "X-Api-Key (new console)",
    legacyAuthMode: "App ID + Access Token (legacy console)",
    appId: "App ID",
    appIdDescription: "Sent as X-Api-App-Key; the Access Token is injected securely.",
    accessToken: "Access Token",
    authModeDescription: "Choose the credential type issued by the Volcengine console.",
    resourceId: "Resource ID",
    resourceIdDescription: "Changing auth mode selects 2.0 hourly for X-Api-Key or 1.0 hourly for legacy auth. You can then choose another resource granted to your key.",
    mode: "Mode",
    streaming: "Streaming (WebSocket)",
    flash: "Single-shot (nostream)",
    modeDescription: "Streaming shows text as you speak; single-shot returns one result after release.",
    streamEndpoint: "Streaming endpoint",
    streamEndpointDescription: "Keep the default for Volcengine bigmodel_async.",
    streamModel: "Streaming model",
    streamModelDescription: "Choose a preset or enter a model name.",
    enableItn: "Normalize spoken numbers and dates",
    enablePunc: "Enable punctuation",
    enableDdc: "Semantic smoothing",
    enableNonstream: "Second-pass refinement (async only)",
    resultType: "Result type",
    resultFull: "Full (cumulative)",
    resultSingle: "Single (incremental)",
    showUtterances: "Return sentence segmentation",
    stripTrailingPunc: "Remove trailing punctuation",
    stripTrailingPuncDescription: "Trims the closing punctuation mark from recognition results.",
    stripTrailingPuncChars: "Custom trailing punctuation",
    stripTrailingPuncCharsDescription: "Characters stripped from the end of results. Pre-filled with the defaults; edits take effect.",
    flashEndpoint: "Single-shot endpoint",
    flashEndpointDescription: "Keep the default for Volcengine bigmodel_nostream.",
    flashModel: "Single-shot model",
    flashModelDescription: "Choose a preset or enter a model name.",
    errorMissingAppId: "App ID is required for legacy auth",
    errorEmptyAudio: "No audio captured"
  },
  "zh-CN": {
    name: "豆包语音识别",
    apiKey: "火山引擎 API Key",
    apiKeyDescription: "由 Foxy 加密保存，不会传给插件脚本。",
    authMode: "鉴权方式",
    apiKeyMode: "X-Api-Key（新版控制台）",
    legacyAuthMode: "App ID + Access Token（旧版控制台）",
    appId: "App ID",
    appIdDescription: "以 X-Api-App-Key 发送；Access Token 由系统安全注入。",
    accessToken: "Access Token",
    authModeDescription: "按火山引擎控制台签发的凭据类型选择。",
    resourceId: "资源 ID",
    resourceIdDescription: "切换鉴权时，新版自动选用 2.0 小时版，旧版选用 1.0 小时版；之后可按密钥授权手动调整。",
    mode: "识别模式",
    streaming: "实时流式识别（WebSocket）",
    flash: "单次识别（nostream）",
    modeDescription: "流式模式边说边显示文字；单次模式在松开后返回整段结果。",
    streamEndpoint: "流式识别地址",
    streamEndpointDescription: "保持默认值即可使用火山引擎 bigmodel_async。",
    streamModel: "流式识别模型",
    streamModelDescription: "可选预设模型，也可输入模型名称。",
    enableItn: "规范化口语数字和日期",
    enablePunc: "启用标点",
    enableDdc: "语义顺滑",
    enableNonstream: "二遍识别（仅 async 模式）",
    resultType: "结果类型",
    resultFull: "全量（累计）",
    resultSingle: "增量（单句）",
    showUtterances: "返回分句信息",
    stripTrailingPunc: "去除句末标点",
    stripTrailingPuncDescription: "识别结果末尾的标点符号会被去掉。",
    stripTrailingPuncChars: "自定义句末标点",
    stripTrailingPuncCharsDescription: "识别结果末尾会被去除的字符，已预填默认句末标点，修改后按新内容生效。",
    flashEndpoint: "单次识别地址",
    flashEndpointDescription: "保持默认值即可使用火山引擎 bigmodel_nostream。",
    flashModel: "单次识别模型",
    flashModelDescription: "可选预设模型，也可输入模型名称。",
    errorMissingAppId: "旧版鉴权需要填写 App ID",
    errorEmptyAudio: "未采集到音频"
  },
  "zh-TW": {
    name: "豆包語音辨識",
    apiKey: "火山引擎 API Key",
    apiKeyDescription: "由 Foxy 加密儲存，不會傳給外掛指令碼。",
    authMode: "鑑權方式",
    apiKeyMode: "X-Api-Key（新版控制台）",
    legacyAuthMode: "App ID + Access Token（舊版控制台）",
    appId: "App ID",
    appIdDescription: "以 X-Api-App-Key 傳送；Access Token 由系統安全注入。",
    accessToken: "Access Token",
    authModeDescription: "按火山引擎控制台簽發的憑據類型選擇。",
    resourceId: "資源 ID",
    resourceIdDescription: "切換驗證方式時，新版自動選用 2.0 小時版，舊版選用 1.0 小時版；之後可按金鑰授權手動調整。",
    mode: "辨識模式",
    streaming: "即時串流辨識（WebSocket）",
    flash: "單次辨識（nostream）",
    modeDescription: "串流模式邊說邊顯示文字；單次模式在放開後回傳整段結果。",
    streamEndpoint: "串流辨識端點",
    streamEndpointDescription: "保持預設值即可使用火山引擎 bigmodel_async。",
    streamModel: "串流辨識模型",
    streamModelDescription: "可選預設模型，也可輸入模型名稱。",
    enableItn: "正規化口語數字和日期",
    enablePunc: "啟用標點",
    enableDdc: "語義順滑",
    enableNonstream: "二遍辨識（僅 async 模式）",
    resultType: "結果類型",
    resultFull: "全量（累計）",
    resultSingle: "增量（單句）",
    showUtterances: "回傳分句資訊",
    stripTrailingPunc: "去除句末標點",
    stripTrailingPuncDescription: "辨識結果末尾的標點符號會被去掉。",
    stripTrailingPuncChars: "自訂句末標點",
    stripTrailingPuncCharsDescription: "辨識結果末尾會被去除的字元，已預填預設句末標點，修改後依新內容生效。",
    flashEndpoint: "單次辨識端點",
    flashEndpointDescription: "保持預設值即可使用火山引擎 bigmodel_nostream。",
    flashModel: "單次辨識模型",
    flashModelDescription: "可選預設模型，也可輸入模型名稱。",
    errorMissingAppId: "舊版鑑權需要填寫 App ID",
    errorEmptyAudio: "未採集到音訊"
  }
};

function stringsFor(locale) {
  const tag = String(locale || "en").toLowerCase().replace(/_/g, "-");
  if (tag.includes("hant") || tag.startsWith("zh-tw") || tag.startsWith("zh-hk") || tag.startsWith("zh-mo")) {
    return STRINGS["zh-TW"];
  }
  if (tag.startsWith("zh")) return STRINGS["zh-CN"];
  return STRINGS.en;
}

// ---- Volcengine binary framing helpers ----

const B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function bytesToBase64(bytes) {
  let out = "";
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i];
    const b1 = i + 1 < bytes.length ? bytes[i + 1] : undefined;
    const b2 = i + 2 < bytes.length ? bytes[i + 2] : undefined;
    out += B64_ALPHABET[b0 >> 2];
    out += B64_ALPHABET[((b0 & 0x03) << 4) | (b1 === undefined ? 0 : b1 >> 4)];
    out += b1 === undefined ? "=" : B64_ALPHABET[((b1 & 0x0f) << 2) | (b2 === undefined ? 0 : b2 >> 6)];
    out += b2 === undefined ? "=" : B64_ALPHABET[b2 & 0x3f];
  }
  return out;
}

// headerSize=1 (4 bytes), protocol version 1
function volcPrefix(messageType, flags, serialization, compression) {
  return bytesToBase64([
    0x11,
    ((messageType & 0x0f) << 4) | (flags & 0x0f),
    ((serialization & 0x0f) << 4) | (compression & 0x0f),
    0x00
  ]);
}

function uuid() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x03) | 0x08;
    return v.toString(16);
  });
}

function boolOf(value, fallback) {
  if (value === true || value === "true") return true;
  if (value === false || value === "false") return false;
  return !!fallback;
}

// Default trailing punctuation (CJK + ASCII + fullwidth); user can override via stripTrailingPuncChars.
const DEFAULT_STRIP_CHARACTERS = "。．.！？!?；;，,、~～…";

// Drop trailing marks listed in `chars`, keep inner punctuation. Empty `chars` strips nothing.
function stripTrailingPunc(text, chars) {
  const set = chars == null ? DEFAULT_STRIP_CHARACTERS : String(chars);
  const value = String(text || "");
  if (!set) return value;
  let end = value.length;
  while (end > 0 && set.indexOf(value.charAt(end - 1)) !== -1) end--;
  return value.slice(0, end);
}

// Full client request: type=1, flags=0 (no sequence), JSON, Gzip. Host appends be32(gzipLen)+gzip(json).
function sendFullRequest(socket, payload) {
  return foxy.websocket.sendGzipFrame(
    socket,
    volcPrefix(0x01, 0x00, 0x01, 0x01),
    foxy.binary.utf8Base64(JSON.stringify(payload))
  );
}

// Audio-only packet: type=2, flags=0 (more coming) or 0x02 (last packet), raw, Gzip.
// pcmBase64 is the raw PCM bytes already base64-encoded; the host gzips them.
function sendAudioPacket(socket, pcmBase64, isLast) {
  return foxy.websocket.sendGzipFrame(
    socket,
    volcPrefix(0x02, isLast ? 0x02 : 0x00, 0x00, 0x01),
    pcmBase64 || ""
  );
}

function buildRequestPayload(context, model) {
  return {
    user: { uid: "foxy" },
    audio: {
      format: "pcm",
      rate: Number(context.sampleRate) || 16000,
      bits: 16,
      channel: 1,
      codec: "raw"
    },
    request: {
      model_name: model || "bigmodel",
      enable_itn: boolOf(context.enableItn, true),
      enable_punc: boolOf(context.enablePunc, true),
      enable_ddc: boolOf(context.enableDdc, false),
      enable_nonstream: boolOf(context.enableNonstream, false),
      show_utterances: boolOf(context.showUtterances, true),
      result_type: context.resultType === "single" ? "single" : "full"
    }
  };
}

function connectVolc(endpoint, context) {
  const defaultResource = context.authMode === "legacy" ? DEFAULTS.resourceIds.legacy : DEFAULTS.resourceIds.apiKey;
  const extras = {
    "X-Api-Resource-Id": String(context.resourceId || defaultResource),
    "X-Api-Request-Id": uuid(),
    "X-Api-Connect-Id": uuid(),
    "X-Api-Sequence": "-1"
  };
  if (context.authMode === "legacy") {
    if (context.appId) extras["X-Api-App-Key"] = String(context.appId);
    // Access Token is the secret: injected as X-Api-Access-Key by the host.
    return foxy.websocket.connect(endpoint, "accessToken", "X-Api-Access-Key", "", JSON.stringify(extras));
  }
  // New console: secret injected as X-Api-Key (empty prefix).
  return foxy.websocket.connect(endpoint, "apiKey", "X-Api-Key", "", JSON.stringify(extras));
}

// Parse one server binary frame from message.binaryBase64 (fallback message.message).
// onMessage is invoked with only the message argument, so the strip options are captured by the
// plugin through `settings` set in start().
let settings = { stripTrailingPunc: false, stripChars: DEFAULT_STRIP_CHARACTERS };

function parseVolcFrame(message) {
  const raw = message.binaryBase64 || message.message;
  if (!raw) return {};
  const total = foxy.binary.byteLength(raw);
  if (!total || total < 4) return {};

  const b1 = foxy.binary.readUInt8(raw, 1);
  const b2 = foxy.binary.readUInt8(raw, 2);
  const messageType = (b1 >> 4) & 0x0f;
  const flags = b1 & 0x0f;
  const compression = b2 & 0x0f;
  const isFinal = (flags & 0x02) !== 0;

  // Error frame: [Header 4B][code 4B][msgSize 4B][msg UTF-8] — no payload-size field.
  if (messageType === 0x0f) {
    let detail = "Volc ASR error";
    let code = 0;
    if (total >= 12) {
      code = foxy.binary.readInt32BE(raw, 4);
      const msgSize = foxy.binary.readInt32BE(raw, 8);
      if (msgSize > 0 && 12 + msgSize <= total) {
        detail = foxy.binary.base64Utf8(foxy.binary.sliceBase64(raw, 12, msgSize)) || detail;
      }
    }
    return { error: (code ? code + ": " : "") + detail };
  }
  // Full server response: type=9, flags 0b0001 (seq) / 0b0011 (final, negative seq).
  if (messageType !== 0x09) return {};

  const seqBytes = (flags & 0x01) !== 0 ? 4 : 0;
  const sizeOffset = 4 + seqBytes;
  if (total < sizeOffset + 4) return isFinal ? { ended: true } : {};
  const payloadSize = foxy.binary.readInt32BE(raw, sizeOffset);
  if (payloadSize <= 0 || sizeOffset + 4 + payloadSize > total) return isFinal ? { ended: true } : {};

  const payload = foxy.binary.sliceBase64(raw, sizeOffset + 4, payloadSize);
  let text;
  try {
    text = compression === 0x01 ? foxy.binary.gunzipUtf8(payload) : foxy.binary.base64Utf8(payload);
  } catch (e) {
    return { error: "Failed to decode Volc ASR response" };
  }
  let json;
  try {
    json = JSON.parse(text);
  } catch (e) {
    return isFinal ? { ended: true } : {};
  }
  const result = json && json.result;
  let transcript =
    result && typeof result.text === "string"
      ? result.text
      : typeof json.text === "string"
        ? json.text
        : "";
  if (settings.stripTrailingPunc) transcript = stripTrailingPunc(transcript, settings.stripChars);
  if (!transcript) return isFinal ? { ended: true } : {};
  return isFinal ? { final: transcript, ended: true } : { partial: transcript };
}

foxy.plugin.register({
  id: "doubao-asr",
  name: stringsFor(foxy.locale).name,
  version: "1.2.0",
  hosts: ["openspeech.bytedance.com"],
  features: [{ type: "voice.asr", id: "doubao-asr", label: "Doubao ASR", sampleRate: 16000 }],
  configure(ui, locale) {
    const t = stringsFor(locale);
    ui.secret("apiKey", t.apiKey, {
      visibleWhen: { key: "authMode", value: "apiKey" },
      description: t.apiKeyDescription
    });
    ui.select("authMode", t.authMode, [
      { label: t.apiKeyMode, value: "apiKey" },
      { label: t.legacyAuthMode, value: "legacy" }
    ], "apiKey", { description: t.authModeDescription });
    ui.text("appId", t.appId, "", {
      visibleWhen: { key: "authMode", value: "legacy" },
      description: t.appIdDescription
    });
    ui.secret("accessToken", t.accessToken, {
      visibleWhen: { key: "authMode", value: "legacy" }
    });
    ui.select("resourceId", t.resourceId, Object.keys(RESOURCE_IDS).map(function (v) {
      return { label: RESOURCE_IDS[v], value: v };
    }), DEFAULTS.resourceIds.apiKey, {
      allowCustom: true,
      defaultWhen: { key: "authMode", values: DEFAULTS.resourceIds },
      description: t.resourceIdDescription
    });
    ui.select("mode", t.mode, [
      { label: t.streaming, value: "streaming" },
      { label: t.flash, value: "flash" }
    ], "streaming", { description: t.modeDescription });

    ui.text("streamEndpoint", t.streamEndpoint, DEFAULTS.streamEndpoint, {
      visibleWhen: { key: "mode", value: "streaming" },
      description: t.streamEndpointDescription
    });
    ui.select("streamModel", t.streamModel, STREAM_MODELS, "bigmodel", {
      visibleWhen: { key: "mode", value: "streaming" },
      allowCustom: true,
      description: t.streamModelDescription
    });
    ui.select("resultType", t.resultType, [
      { label: t.resultFull, value: "full" },
      { label: t.resultSingle, value: "single" }
    ], "full", { visibleWhen: { key: "mode", value: "streaming" } });

    ui.text("flashEndpoint", t.flashEndpoint, DEFAULTS.flashEndpoint, {
      visibleWhen: { key: "mode", value: "flash" },
      description: t.flashEndpointDescription
    });
    ui.select("flashModel", t.flashModel, FLASH_MODELS, "bigmodel", {
      visibleWhen: { key: "mode", value: "flash" },
      allowCustom: true,
      description: t.flashModelDescription
    });

    // Slider-style (toggle) options grouped together and placed last.
    ui.switch("enableItn", t.enableItn, true, { visibleWhen: { key: "mode", value: "streaming" } });
    ui.switch("enablePunc", t.enablePunc, true, { visibleWhen: { key: "mode", value: "streaming" } });
    ui.switch("enableDdc", t.enableDdc, false, { visibleWhen: { key: "mode", value: "streaming" } });
    ui.switch("enableNonstream", t.enableNonstream, false, { visibleWhen: { key: "mode", value: "streaming" } });
    ui.switch("showUtterances", t.showUtterances, true, { visibleWhen: { key: "mode", value: "streaming" } });
    ui.switch("stripTrailingPunc", t.stripTrailingPunc, false, {
      description: t.stripTrailingPuncDescription
    });
    ui.text("stripTrailingPuncChars", t.stripTrailingPuncChars, DEFAULT_STRIP_CHARACTERS, {
      visibleWhen: { key: "stripTrailingPunc", value: "true" },
      description: t.stripTrailingPuncCharsDescription
    });
  },
  async start(context) {
    const t = stringsFor(foxy.locale);
    const isFlash = context.mode === "flash";
    settings.stripTrailingPunc = boolOf(context.stripTrailingPunc, false);
    settings.stripChars = context.stripTrailingPuncChars != null
      ? String(context.stripTrailingPuncChars)
      : DEFAULT_STRIP_CHARACTERS;
    if (context.authMode === "legacy" && !context.appId) {
      throw new Error(t.errorMissingAppId);
    }
    const endpoint = isFlash
      ? (context.flashEndpoint || DEFAULTS.flashEndpoint)
      : (context.streamEndpoint || DEFAULTS.streamEndpoint);
    const model = isFlash
      ? (context.flashModel || "bigmodel")
      : (context.streamModel || "bigmodel");

    const socket = connectVolc(endpoint, context);
    await foxy.websocket.waitOpen(socket);
    await sendFullRequest(socket, buildRequestPayload(context, model));
    foxy.voice.ready();
    return socket;
  },
  async audio(context) {
    await sendAudioPacket(context.socket, context.pcm, false);
  },
  async stop(context) {
    // Last packet flag 0x22 closes the audio stream; the server then returns the final result.
    await sendAudioPacket(context.socket, "", true);
  },
  onMessage(message) {
    return parseVolcFrame(message);
  }
});
