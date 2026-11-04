const HOST = "asr.cloud.tencent.com";
const PATH_PREFIX = "/asr/v2/";
const DEFAULT_ENGINE = "16k_zh";
const SIGNATURE_TTL_SECONDS = 60 * 60;
const DEFAULT_STRIP_CHARACTERS = ".,!?;:，。！？；：、…⋯~～";

const ENGINES = [
  {value: "16k_zh", label: "中文（普通话） · 16k_zh"},
  {value: "16k_zh-TW", label: "中文（繁體） · 16k_zh-TW"},
  {value: "16k_yue", label: "粵語 · 16k_yue"},
  {value: "16k_en", label: "English · 16k_en"},
  {value: "16k_ja", label: "日本語 · 16k_ja"},
  {value: "16k_ko", label: "한국어 · 16k_ko"},
  {value: "16k_zh_edu", label: "Chinese · education · 16k_zh_edu"},
  {value: "16k_zh_medical", label: "Chinese · medical · 16k_zh_medical"},
  {value: "16k_zh_court", label: "Chinese · court · 16k_zh_court"},
  {value: "16k_en_game", label: "English · games · 16k_en_game"},
  {value: "16k_en_edu", label: "English · education · 16k_en_edu"},
  {value: "16k_th", label: "ไทย · 16k_th"},
  {value: "16k_id", label: "Bahasa Indonesia · 16k_id"},
  {value: "16k_vi", label: "Tiếng Việt · 16k_vi"},
  {value: "16k_ms", label: "Bahasa Melayu · 16k_ms"},
  {value: "16k_pt", label: "Português · 16k_pt"},
  {value: "16k_es", label: "Español · 16k_es"},
  {value: "16k_fr", label: "Français · 16k_fr"},
  {value: "16k_de", label: "Deutsch · 16k_de"}
];

const STRINGS = {
  en: {
    name: "Tencent Cloud Real-Time ASR (Standard)",
    appId: "AppID",
    appIdDescription: "10-digit APPID from Console > Account Information, not the 12-digit UIN.",
    appIdRequired: "Tencent Cloud ASR: AppID is required",
    appIdInvalid: "Tencent Cloud ASR: use the 10-digit APPID, not the 12-digit account ID (UIN)",
    secretId: "SecretId",
    secretIdDescription: "From Console > API Key Management. It is part of the request signature.",
    secretIdRequired: "Tencent Cloud ASR: SecretId is required",
    secretKey: "SecretKey",
    secretKeyDescription: "Stored by Foxy; the plugin can only ask Foxy to sign with it.",
    engine: "Recognition engine",
    engineDescription: "16 kHz standard engines. Specialty engines may be billed differently.",
    needVad: "Voice activity detection",
    needVadDescription: "Helps long recordings split cleanly. Leave off for short dictation.",
    hotwordList: "Temporary hotwords",
    hotwordListDescription: "Format: 腾讯云|10,语音识别|5. Overrides a hotword table ID.",
    hotwordId: "Hotword table ID",
    hotwordIdDescription: "Optional hotword table created in the Tencent Cloud console.",
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
    convertNumbersMath: "Math-aware conversion",
    stripPunctuation: "Strip trailing punctuation",
    stripCharacters: "Punctuation to strip",
    stripCharactersDescription: "Characters removed from the end of each final result."
  },
  "zh-CN": {
    name: "腾讯云实时语音识别（普通版）",
    appId: "AppID",
    appIdDescription: "控制台「账号信息」中的 10 位 APPID，不是 12 位账号 ID（UIN）。",
    appIdRequired: "腾讯云语音识别：请填写 AppID",
    appIdInvalid: "腾讯云语音识别：请填写 10 位 APPID，不是 12 位账号 ID（UIN）",
    secretId: "SecretId",
    secretIdDescription: "在控制台「API 密钥管理」页面查看，参与请求签名。",
    secretIdRequired: "腾讯云语音识别：请填写 SecretId",
    secretKey: "SecretKey",
    secretKeyDescription: "由 Foxy 加密保存，插件只能请求 Foxy 代为签名。",
    engine: "识别引擎",
    engineDescription: "均为 16k 普通版引擎；专业领域引擎的计费可能不同。",
    needVad: "人声检测切分",
    needVadDescription: "适合较长录音；短句听写建议关闭。",
    hotwordList: "临时热词",
    hotwordListDescription: "格式：腾讯云|10,语音识别|5。会覆盖热词表 ID。",
    hotwordId: "热词表 ID",
    hotwordIdDescription: "可选，控制台创建的热词表 ID。",
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
    convertNumbersMath: "数学相关转换",
    stripPunctuation: "去除末尾标点",
    stripCharacters: "去除的标点字符",
    stripCharactersDescription: "从每次最终结果的末尾移除这些字符。"
  },
  "zh-TW": {
    name: "騰訊雲即時語音辨識（普通版）",
    appId: "AppID",
    appIdDescription: "主控台「帳號資訊」中的 10 位 APPID，不是 12 位帳號 ID（UIN）。",
    appIdRequired: "騰訊雲語音辨識：請填寫 AppID",
    appIdInvalid: "騰訊雲語音辨識：請填寫 10 位 APPID，不是 12 位帳號 ID（UIN）",
    secretId: "SecretId",
    secretIdDescription: "在主控台「API 金鑰管理」頁面查看，參與請求簽名。",
    secretIdRequired: "騰訊雲語音辨識：請填寫 SecretId",
    secretKey: "SecretKey",
    secretKeyDescription: "由 Foxy 加密儲存，外掛只能請求 Foxy 代為簽名。",
    engine: "辨識引擎",
    engineDescription: "均為 16k 普通版引擎；專業領域引擎的計費可能不同。",
    needVad: "人聲偵測切分",
    needVadDescription: "適合較長錄音；短句聽寫建議關閉。",
    hotwordList: "臨時熱詞",
    hotwordListDescription: "格式：騰訊雲|10,語音辨識|5。會覆蓋熱詞表 ID。",
    hotwordId: "熱詞表 ID",
    hotwordIdDescription: "可選，主控台建立的熱詞表 ID。",
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
    convertNumbersMath: "數學相關轉換",
    stripPunctuation: "去除結尾標點",
    stripCharacters: "去除的標點字元",
    stripCharactersDescription: "從每次最終結果的結尾移除這些字元。"
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

let state = newSessionState();

function newSessionState() {
  return {
    ready: false,
    stopping: false,
    endSent: false,
    pending: [],
    pendingBytes: 0,
    maxPendingBytes: 96000,
    lastText: "",
    legacyFinals: {},
    legacyPartial: "",
    voiceId: "",
    stripPunctuation: false,
    stripChars: ""
  };
}

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

function selectedEngine(context) {
  const preferred = setting(context, "engine");
  return ENGINES.some(entry => entry.value === preferred) ? preferred : DEFAULT_ENGINE;
}

function isChineseEngine(engine) {
  return engine.startsWith("16k_zh");
}

function buildParams(context, engine) {
  const now = Math.floor(Date.now() / 1000);
  const params = {
    secretid: setting(context, "secretId"),
    timestamp: String(now),
    expired: String(now + SIGNATURE_TTL_SECONDS),
    nonce: nonce(),
    engine_model_type: engine,
    voice_id: state.voiceId,
    voice_format: "1"
  };
  if (setting(context, "needVad") === "true") params.needvad = "1";
  if (isChineseEngine(engine)) {
    if (state.stripPunctuation) params.filter_punc = "1";
    addNumber(params, "filter_dirty", setting(context, "filterDirty"));
    addNumber(params, "filter_modal", setting(context, "filterModal"));
    addNumber(params, "convert_num_mode", setting(context, "convertNum"));
  }
  const hotwordList = setting(context, "hotwordList");
  const hotwordId = setting(context, "hotwordId");
  if (hotwordList) params.hotword_list = hotwordList;
  else if (hotwordId) params.hotword_id = hotwordId;
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

function sentenceList(value) {
  if (!value || typeof value !== "object") return [];
  if (Array.isArray(value)) return value;
  if (Array.isArray(value.sentence_list)) return value.sentence_list;
  if (Array.isArray(value.speaker_sentences)) return value.speaker_sentences;
  if (Array.isArray(value.sentences)) return value.sentences;
  return [];
}

function extractText(packet) {
  const sentences = packet.sentences;
  if (sentences && typeof sentences === "object") {
    const list = sentenceList(sentences);
    if (list.length > 0) return joinSentenceTexts(list);
    if (typeof sentences.voice_text_str === "string") return sentences.voice_text_str.trim();
  }
  const result = packet.result;
  if (result && typeof result === "object") {
    const list = sentenceList(result);
    if (list.length > 0) return joinSentenceTexts(list);
    if (typeof result.voice_text_str === "string") return legacySliceText(result);
  }
  return "";
}

function joinSentenceTexts(list) {
  const items = [];
  list.forEach((item, index) => {
    if (!item || typeof item !== "object") return;
    const text = String(item.sentence || item.voice_text_str || item.text || "").trim();
    if (!text) return;
    const rawId = item.sentence_id;
    const id = rawId === undefined || rawId === null || rawId === "" ? null : Number(rawId);
    items.push({
      text: text,
      id: id !== null && isFinite(id) ? id : null,
      index: index
    });
  });
  items.sort((left, right) => {
    if (left.id !== null && right.id !== null && left.id !== right.id) return left.id - right.id;
    return left.index - right.index;
  });
  return joinTexts(items.map(item => item.text));
}

function legacySliceText(result) {
  const sliceType = Number(result.slice_type);
  const index = Number(result.index || 0);
  const text = String(result.voice_text_str || "").trim();
  if (sliceType === 0) {
    state.legacyFinals = {};
    state.legacyPartial = "";
  }
  if (sliceType === 2) {
    state.legacyFinals[index] = text;
    state.legacyPartial = "";
  } else {
    state.legacyPartial = text;
  }
  const parts = Object.keys(state.legacyFinals)
    .map(Number)
    .sort((left, right) => left - right)
    .map(key => state.legacyFinals[key])
    .filter(Boolean);
  if (state.legacyPartial) parts.push(state.legacyPartial);
  return joinTexts(parts);
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

function finalText(text, stripPunctuation, stripChars) {
  const value = String(text || "").trim();
  if (!stripPunctuation || !stripChars) return value;
  let end = value.length;
  while (end > 0 && stripChars.indexOf(value[end - 1]) !== -1) end--;
  return end === value.length ? value : value.slice(0, end);
}

function describeError(packet) {
  const code = Number(packet.code || 0);
  const message = String(packet.message || "recognition failed").trim();
  return "Tencent Cloud ASR " + code + ": " + message;
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
  id: "tencent-asr",
  name: stringsFor(foxy.locale).name,
  version: "1.0.7",
  hosts: [HOST],
  features: [{type: "voice.asr", id: "tencent-asr", label: stringsFor(foxy.locale).name, sampleRate: 16000}],
  configure(ui, locale) {
    const t = stringsFor(locale);
    ui.text("appId", t.appId, "", {description: t.appIdDescription});
    ui.text("secretId", t.secretId, "", {description: t.secretIdDescription});
    ui.secret("secretKey", t.secretKey, {description: t.secretKeyDescription});
    ui.select("engine", t.engine, ENGINES, DEFAULT_ENGINE, {description: t.engineDescription});
    ui.switch("needVad", t.needVad, false, {description: t.needVadDescription});
    ui.text("hotwordList", t.hotwordList, "", {description: t.hotwordListDescription});
    ui.text("hotwordId", t.hotwordId, "", {description: t.hotwordIdDescription});
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
    ui.switch("stripPunctuation", t.stripPunctuation, true);
    ui.text("stripCharacters", t.stripCharacters, DEFAULT_STRIP_CHARACTERS, {
      visibleWhen: {key: "stripPunctuation", value: "true"},
      description: t.stripCharactersDescription
    });
  },
  async start(context) {
    state = newSessionState();
    const t = stringsFor(foxy.locale);
    const appId = setting(context, "appId");
    const secretId = setting(context, "secretId");
    if (!appId) throw new Error(t.appIdRequired);
    if (!/^\d{10}$/.test(appId)) throw new Error(t.appIdInvalid);
    if (!secretId) throw new Error(t.secretIdRequired);
    state.voiceId = uuid();
    state.maxPendingBytes = Math.max(1, Number(context.sampleRate) || 16000) * 2 * 2;
    state.stripPunctuation = context.stripPunctuation !== "false";
    state.stripChars = context.stripCharacters != null
      ? String(context.stripCharacters)
      : DEFAULT_STRIP_CHARACTERS;

    const params = buildParams(context, selectedEngine(context));
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
        throw new Error("Tencent Cloud ASR handshake did not complete in time");
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

    const text = extractText(packet);
    if (text) state.lastText = text;

    if (Number(packet.final) === 1) {
      const final = finalText(
        text || state.lastText,
        state.stripPunctuation,
        state.stripChars
      );
      return final ? {final: final, ended: true} : {ended: true};
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
