const HOST = "asr.cloud.tencent.com";
const PATH_PREFIX = "/asr/speech_translate/";
const SIGNATURE_TTL_SECONDS = 60 * 60;
const DEFAULT_SOURCE = "zh";
const DEFAULT_TARGET = "en";
const DEFAULT_MODEL = "hunyuan-translation-lite";
const MODEL_VALUES = ["hunyuan-translation-lite", "hunyuan-translation"];

const LANGUAGE_OPTIONS = [
  {value: "zh", label: "中文 · zh"},
  {value: "en", label: "English · en"},
  {value: "zh_en", label: "中英混合 · zh_en"},
  {value: "ja", label: "日本語 · ja"},
  {value: "ko", label: "한국어 · ko"},
  {value: "yue", label: "粵語 · yue"},
  {value: "id", label: "Bahasa Indonesia · id"},
  {value: "th", label: "ไทย · th"},
  {value: "ru", label: "Русский · ru"}
];

const TARGETS_BY_SOURCE = {
  zh: ["zh", "en", "ja", "ko", "yue", "id", "th"],
  en: ["zh", "en", "ja", "ko", "yue", "id", "th"],
  zh_en: ["zh_en", "zh", "en", "ja", "ko", "yue", "id", "th"],
  ja: ["zh", "en", "ja", "ko", "yue"],
  ko: ["zh", "en", "ja", "ko", "yue"],
  yue: ["zh", "en", "ja", "ko", "yue"],
  id: ["zh", "en", "id"],
  th: ["zh", "en", "th"],
  ru: ["zh", "en", "ru"]
};

const STRINGS = {
  en: {
    name: "Tencent Cloud Real-Time Translation",
    appId: "AppID",
    appIdDescription: "10-digit APPID from Console > Account Information, not the 12-digit UIN.",
    appIdRequired: "Tencent Cloud translation: AppID is required",
    appIdInvalid: "Tencent Cloud translation: use the 10-digit APPID, not the 12-digit account ID (UIN)",
    secretId: "SecretId",
    secretIdDescription: "From Console > API Key Management. It is part of the request signature.",
    secretIdRequired: "Tencent Cloud translation: SecretId is required",
    secretKey: "SecretKey",
    secretKeyDescription: "Stored by Foxy; the plugin can only ask Foxy to sign with it.",
    source: "Source language",
    sourceDescription: "Spoken language. Use zh_en for mixed Chinese and English.",
    target: "Target language",
    targetDescription: "Available targets depend on the source language.",
    transModel: "Translation model",
    transModelDescription: "Lite is cheaper and faster; standard is more accurate.",
    modelLite: "Lite · hunyuan-translation-lite",
    modelStandard: "Standard · hunyuan-translation",
    output: "Text to insert",
    outputDescription: "Choose whether the keyboard inserts the translation or the recognized speech.",
    outputTarget: "Translated text",
    outputSource: "Recognized text",
    pairInvalid: "Tencent Cloud translation: target language is not supported for this source",
    hotwordList: "Temporary hotwords",
    hotwordListDescription: "Format: 腾讯云|10,语音识别|5. Improves recognition of the source speech.",
    filterDirty: "Profanity filter",
    filterOff: "Off",
    filterOn: "Filter",
    filterReplace: "Replace with *",
    filterModal: "Filler word filter",
    filterPartial: "Partial",
    filterStrict: "Strict",
    convertNum: "Smart number conversion",
    convertNumbersNever: "Keep Chinese numerals",
    convertNumbersSmart: "Smart conversion",
    convertNumbersMath: "Math-aware conversion"
  },
  "zh-CN": {
    name: "腾讯云实时语音翻译",
    appId: "AppID",
    appIdDescription: "控制台「账号信息」中的 10 位 APPID，不是 12 位账号 ID（UIN）。",
    appIdRequired: "腾讯云语音翻译：请填写 AppID",
    appIdInvalid: "腾讯云语音翻译：请填写 10 位 APPID，不是 12 位账号 ID（UIN）",
    secretId: "SecretId",
    secretIdDescription: "在控制台「API 密钥管理」页面查看，参与请求签名。",
    secretIdRequired: "腾讯云语音翻译：请填写 SecretId",
    secretKey: "SecretKey",
    secretKeyDescription: "由 Foxy 加密保存，插件只能请求 Foxy 代为签名。",
    source: "源语言",
    sourceDescription: "说话使用的语言；中英混说请选择中英混合。",
    target: "目标语言",
    targetDescription: "可选目标语言取决于源语言。",
    transModel: "翻译模型",
    transModelDescription: "轻量版更快更便宜，标准版更准确。",
    modelLite: "轻量版 · hunyuan-translation-lite",
    modelStandard: "标准版 · hunyuan-translation",
    output: "上屏文本",
    outputDescription: "选择输入法插入译文还是识别原文。",
    outputTarget: "译文",
    outputSource: "原文",
    pairInvalid: "腾讯云语音翻译：该源语言不支持选定的目标语言",
    hotwordList: "临时热词",
    hotwordListDescription: "格式：腾讯云|10,语音识别|5。用于提升源语音的识别准确率。",
    filterDirty: "脏词过滤",
    filterOff: "关闭",
    filterOn: "过滤",
    filterReplace: "替换为 *",
    filterModal: "语气词过滤",
    filterPartial: "部分过滤",
    filterStrict: "严格过滤",
    convertNum: "数字智能转换",
    convertNumbersNever: "保留中文数字",
    convertNumbersSmart: "智能转换",
    convertNumbersMath: "数学相关转换"
  },
  "zh-TW": {
    name: "騰訊雲即時語音翻譯",
    appId: "AppID",
    appIdDescription: "主控台「帳號資訊」中的 10 位 APPID，不是 12 位帳號 ID（UIN）。",
    appIdRequired: "騰訊雲語音翻譯：請填寫 AppID",
    appIdInvalid: "騰訊雲語音翻譯：請填寫 10 位 APPID，不是 12 位帳號 ID（UIN）",
    secretId: "SecretId",
    secretIdDescription: "在主控台「API 金鑰管理」頁面查看，參與請求簽名。",
    secretIdRequired: "騰訊雲語音翻譯：請填寫 SecretId",
    secretKey: "SecretKey",
    secretKeyDescription: "由 Foxy 加密儲存，外掛只能請求 Foxy 代為簽名。",
    source: "來源語言",
    sourceDescription: "說話使用的語言；中英混說請選擇中英混合。",
    target: "目標語言",
    targetDescription: "可選目標語言取決於來源語言。",
    transModel: "翻譯模型",
    transModelDescription: "輕量版更快更便宜，標準版更準確。",
    modelLite: "輕量版 · hunyuan-translation-lite",
    modelStandard: "標準版 · hunyuan-translation",
    output: "上屏文字",
    outputDescription: "選擇輸入法插入譯文還是辨識原文。",
    outputTarget: "譯文",
    outputSource: "原文",
    pairInvalid: "騰訊雲語音翻譯：此來源語言不支援所選的目標語言",
    hotwordList: "臨時熱詞",
    hotwordListDescription: "格式：騰訊雲|10,語音辨識|5。用於提升來源語音的辨識準確率。",
    filterDirty: "髒詞過濾",
    filterOff: "關閉",
    filterOn: "過濾",
    filterReplace: "替換為 *",
    filterModal: "語氣詞過濾",
    filterPartial: "部分過濾",
    filterStrict: "嚴格過濾",
    convertNum: "數字智慧轉換",
    convertNumbersNever: "保留中文數字",
    convertNumbersSmart: "智慧轉換",
    convertNumbersMath: "數學相關轉換"
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

function newSessionState() {
  return {
    ready: false,
    stopping: false,
    endSent: false,
    pending: [],
    pendingBytes: 0,
    maxPendingBytes: 64000,
    sentences: [],
    output: "target",
    voiceId: ""
  };
}

let state = newSessionState();

function uuid() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, ch => {
    const random = Math.floor(Math.random() * 16);
    return (ch === "x" ? random : (random & 3) | 8).toString(16);
  });
}

function nonce() {
  return String(1000000000 + Math.floor(Math.random() * 9000000000));
}

function setting(context, key) {
  const value = context[key];
  return value === undefined || value === null ? "" : String(value).trim();
}

function addNumber(params, key, value) {
  const text = String(value === undefined || value === null ? "" : value).trim();
  if (text) params[key] = text;
}

function isChineseSource(source) {
  return source === "zh" || source === "zh_en";
}

function selectedSource(context) {
  const preferred = setting(context, "source");
  return TARGETS_BY_SOURCE[preferred] ? preferred : DEFAULT_SOURCE;
}

function selectedModel(context) {
  const preferred = setting(context, "transModel");
  return MODEL_VALUES.indexOf(preferred) !== -1 ? preferred : DEFAULT_MODEL;
}

function buildParams(context, source, target, model) {
  const now = Math.floor(Date.now() / 1000);
  const params = {
    secretid: setting(context, "secretId"),
    timestamp: String(now),
    expired: String(now + SIGNATURE_TTL_SECONDS),
    nonce: nonce(),
    voice_id: state.voiceId,
    voice_format: "1",
    source: source,
    target: target,
    trans_model: model
  };
  const hotwordList = setting(context, "hotwordList");
  if (hotwordList) params.hotword_list = hotwordList;
  if (isChineseSource(source)) {
    addNumber(params, "filter_dirty", setting(context, "filterDirty"));
    addNumber(params, "filter_modal", setting(context, "filterModal"));
    addNumber(params, "convert_num_mode", setting(context, "convertNum"));
  }
  return params;
}

function signSource(appId, params) {
  const canonical = Object.keys(params)
    .sort()
    .map(key => key + "=" + params[key])
    .join("&");
  return HOST + PATH_PREFIX + appId + "?" + canonical;
}

function buildUrl(appId, params, signature) {
  const query = Object.keys(params)
    .sort()
    .map(key => encodeURIComponent(key) + "=" + encodeURIComponent(params[key]))
    .join("&");
  return "wss://" + HOST + PATH_PREFIX + encodeURIComponent(appId) +
    "?" + query + "&signature=" + encodeURIComponent(signature);
}

function recordResult(packet) {
  const result = packet.result;
  if (!result || typeof result !== "object") return;
  const id = packet.sentence_id === undefined || packet.sentence_id === null
    ? ""
    : String(packet.sentence_id);
  let sentence = null;
  for (let index = 0; index < state.sentences.length; index++) {
    if (state.sentences[index].id === id) {
      sentence = state.sentences[index];
      break;
    }
  }
  if (!sentence) {
    sentence = {id: id, source: "", target: ""};
    state.sentences.push(sentence);
  }
  const sourceText = typeof result.source_text === "string" ? result.source_text.trim() : "";
  const targetText = typeof result.target_text === "string" ? result.target_text.trim() : "";
  if (sourceText) sentence.source = sourceText;
  if (targetText) sentence.target = targetText;
}

function composeText() {
  return joinTexts(state.sentences.map(sentence => state.output === "source" ? sentence.source : sentence.target));
}

function joinTexts(parts) {
  let output = "";
  parts.filter(Boolean).forEach(part => {
    const text = String(part).trim();
    if (!text) return;
    if (output && needsSpace(output[output.length - 1], text[0])) output += " ";
    output += text;
  });
  return output;
}

function needsSpace(previous, next) {
  const noSpaceBefore = "，。！？、；：,.!?;:)]}）】》";
  const noSpaceAfter = "([{（【《";
  if (/\s/.test(previous) || /\s/.test(next) || noSpaceBefore.includes(next) || noSpaceAfter.includes(previous)) return false;
  return !isCjk(previous) && !isCjk(next);
}

function isCjk(char) {
  const code = char.charCodeAt(0);
  return (code >= 0x2e80 && code <= 0x9fff) ||
    (code >= 0xf900 && code <= 0xfaff) ||
    (code >= 0x3040 && code <= 0x30ff) ||
    (code >= 0xac00 && code <= 0xd7af);
}

function describeError(packet) {
  const code = Number(packet.code || 0);
  const message = String(packet.message || "translation failed").trim();
  return "Tencent Cloud translation " + code + ": " + message;
}

function base64Bytes(value) {
  return Math.floor(String(value || "").length * 3 / 4);
}

async function flushPending(socket, budget) {
  while (state.pending.length && budget > 0) {
    const chunk = state.pending.shift();
    const size = base64Bytes(chunk);
    state.pendingBytes = Math.max(0, state.pendingBytes - size);
    budget -= size;
    await foxy.websocket.sendBinary(socket, chunk);
  }
}

function sendEnd(socket) {
  if (state.endSent) return Promise.resolve();
  state.endSent = true;
  return foxy.websocket.send(socket, JSON.stringify({type: "end"}));
}

foxy.plugin.register({
  id: "tencent-translate",
  name: stringsFor(foxy.locale).name,
  version: "1.0.0",
  hosts: [HOST],
  features: [{type: "voice.asr", id: "tencent-translate", label: stringsFor(foxy.locale).name, sampleRate: 16000}],
  configure(ui, locale) {
    const t = stringsFor(locale);
    ui.text("appId", t.appId, "", {description: t.appIdDescription});
    ui.text("secretId", t.secretId, "", {description: t.secretIdDescription});
    ui.secret("secretKey", t.secretKey, {description: t.secretKeyDescription});
    ui.select("source", t.source, LANGUAGE_OPTIONS, DEFAULT_SOURCE, {description: t.sourceDescription});
    ui.select("target", t.target, LANGUAGE_OPTIONS, DEFAULT_TARGET, {description: t.targetDescription});
    ui.select("transModel", t.transModel, [
      {value: "hunyuan-translation-lite", label: t.modelLite},
      {value: "hunyuan-translation", label: t.modelStandard}
    ], DEFAULT_MODEL, {description: t.transModelDescription});
    ui.select("output", t.output, [
      {value: "target", label: t.outputTarget},
      {value: "source", label: t.outputSource}
    ], "target", {description: t.outputDescription});
    ui.text("hotwordList", t.hotwordList, "", {description: t.hotwordListDescription});
    ui.select("filterDirty", t.filterDirty, [
      {label: t.filterOff, value: "0"},
      {label: t.filterOn, value: "1"},
      {label: t.filterReplace, value: "2"}
    ], "0");
    ui.select("filterModal", t.filterModal, [
      {label: t.filterOff, value: "0"},
      {label: t.filterPartial, value: "1"},
      {label: t.filterStrict, value: "2"}
    ], "0");
    ui.select("convertNum", t.convertNum, [
      {label: t.convertNumbersNever, value: "0"},
      {label: t.convertNumbersSmart, value: "1"},
      {label: t.convertNumbersMath, value: "3"}
    ], "1");
  },
  async start(context) {
    state = newSessionState();
    const t = stringsFor(foxy.locale);
    const appId = setting(context, "appId");
    const secretId = setting(context, "secretId");
    if (!appId) throw new Error(t.appIdRequired);
    if (!/^\d{10}$/.test(appId)) throw new Error(t.appIdInvalid);
    if (!secretId) throw new Error(t.secretIdRequired);
    const source = selectedSource(context);
    const allowedTargets = TARGETS_BY_SOURCE[source] || [];
    const requestedTarget = setting(context, "target");
    if (requestedTarget && allowedTargets.indexOf(requestedTarget) === -1) {
      throw new Error(t.pairInvalid);
    }
    const target = allowedTargets.indexOf(requestedTarget) !== -1 ? requestedTarget : DEFAULT_TARGET;
    state.voiceId = uuid();
    state.output = setting(context, "output") === "source" ? "source" : "target";
    state.maxPendingBytes = Math.max(1, Number(context.sampleRate) || 16000) * 2 * 2;

    const model = selectedModel(context);
    const params = buildParams(context, source, target, model);
    const signature = foxy.crypto.hmacSha1Base64("secretKey", signSource(appId, params));
    const socket = foxy.websocket.connect(buildUrl(appId, params, signature), "");
    await foxy.websocket.waitOpen(socket);
    return socket;
  },
  async audio(context) {
    const bytes = base64Bytes(context.pcm);
    state.pending.push(context.pcm);
    state.pendingBytes += bytes;
    if (!state.ready) {
      if (state.pendingBytes > state.maxPendingBytes) {
        throw new Error("Tencent Cloud translation handshake did not complete in time");
      }
      return;
    }
    // Drain the handshake backlog at most twice real time so the provider
    // never sees more than three seconds of audio within one second.
    await flushPending(context.socket, bytes * 2);
  },
  async stop(context) {
    state.stopping = true;
    if (!state.ready) return;
    await flushPending(context.socket, Infinity);
    await sendEnd(context.socket);
  },
  onMessage(event) {
    if (!event.message) return {};
    let packet;
    try {
      packet = JSON.parse(event.message);
    } catch (_) {
      return {};
    }
    if (Number(packet.code || 0) !== 0) return {error: describeError(packet)};

    recordResult(packet);
    const text = composeText();

    if (Number(packet.final || 0) >= 1) {
      return text ? {final: text, ended: true} : {ended: true};
    }

    if (!state.ready) {
      state.ready = true;
      if (state.stopping) {
        flushPending(event.socket, Infinity).then(() => sendEnd(event.socket)).catch(() => {});
      }
      return text ? {ready: true, partial: text} : {ready: true};
    }

    return text ? {partial: text} : {};
  }
});
