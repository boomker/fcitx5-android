// LLM Assistant — based on the supplied v1.23 script, adapted for 中州鹅.
// Common providers have editable default API addresses; the custom provider has no default.
// JS 负责三种协议的请求与结果解析；App 从配置提取主机名、询问授权并注入密钥。
// 本适配版依赖中州鹅的动态端点授权，不适用于仅允许静态 hosts 声明的宿主。

const PROVIDERS = [
  {
    value: "deepseek",
    label: "DeepSeek",
    url: "https://api.deepseek.com",
    model: "deepseek-flash",
    credential: "deepseekKey"
  },
  {
    value: "openai",
    label: "OpenAI",
    url: "https://api.openai.com/v1",
    model: "gpt-4o-mini",
    credential: "openaiKey"
  },
  {
    value: "qwen",
    label: "通义千问 / Qwen",
    shortKey: "qwenShort",
    url: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    model: "qwen-plus",
    credential: "qwenKey"
  },
  {
    value: "anthropic",
    label: "Anthropic / Claude",
    url: "https://api.anthropic.com/v1",
    model: "claude-sonnet-4-6",
    credential: "anthropicKey",
    protocol: "anthropic"
  },
  {
    value: "custom",
    label: "Custom API",
    labelKey: "providerCustom",
    shortKey: "providerCustomShort",
    url: "",
    model: "gpt-4o-mini",
    credential: "customKey"
  }
];

const PROTOCOLS = [
  {value: "chat", label: "OpenAI Chat Completions", path: "/chat/completions"},
  {value: "anthropic", label: "Anthropic Messages", path: "/messages"},
  {value: "responses", label: "OpenAI Responses", path: "/responses"}
];

const PURPOSE_IDS = ["short", "polish", "proofread", "tone", "idiom", "classical", "toEnglish", "martian"];

const STRINGS = {
  en: {
    name: "AI Assistant",
    provider: "Provider",
    providerDescription: "The selected provider's default API address is filled in automatically. Edit it for a relay or custom endpoint; configured services can be used as fallback.",
    providerCustom: "Custom (relay)",
    providerCustomShort: "Custom",
    qwenShort: "Qwen",
    defaultPurposeLabel: "Default task",
    defaultPurposeDescription: "The assistant starts with this task on open; temporary choices in its dialog do not change this setting.",
    baseUrl: "Base URL",
    baseUrlDescription: "Enter a base URL (including /v1 if required) or a complete endpoint. The path follows the selected protocol.",
    protocol: "API protocol",
    protocolDescription: "Choose the protocol supported by this endpoint. It controls the path, authentication and request format.",
    maxTokens: "Maximum output tokens",
    maxTokensDescription: "For Messages and Responses: 1–131072, subject to model limits. Responses also counts reasoning tokens.",
    apiVersion: "Anthropic API version",
    apiVersionDescription: "Defaults to 2023-06-01 for relay compatibility; change if required by your service.",
    invalidTokenLimit: "Maximum output tokens must be an integer from 1 to 131072",
    incompleteResult: "The response is incomplete; increase the output limit or retry",
    refusedResult: "The model declined this request",
    customUrlDescription: "Enter the relay base URL or full endpoint, then select its API protocol. This input method asks for host authorization on first use; editing JS is unnecessary.",
    model: "Model",
    modelDescription: "Any model name that endpoint accepts.",
    apiKey: "API key",
    apiKeyDescription: "Encrypted by the input method; the plugin never receives the key.",
    purpose: "Task",
    inputLabel: "Text",
    inputPlaceholder: "Enter the text to process",
    clear: "Clear",
    close: "Close",
    confirm: "Insert",
    send: "Send to AI",
    regenerate: "Retry",
    outputLabel: "AI reply",
    emptyInput: "Enter the text to send to AI first",
    assistantTitle: "AI assistant",
    resultTitle: "Result · %1 · confirm to insert or clear",
    purposeShort: "Answer",
    purposePolish: "Polish",
    purposeProofread: "Proofread",
    purposeTone: "Change tone",
    prefixShort: "Answer the following briefly and accurately. Return only the answer:\n",
    prefixPolish: "Polish the following text while keeping its meaning. Return only the rewritten text:\n",
    prefixProofread: "Fix only the typos and obvious grammar issues in the following text. Return only the corrected text:\n",
    prefixTone: "Rewrite the following text in a more natural and polite tone. Return only the rewritten text:\n",
    purposeIdiom: "Idiom chain",
    purposeClassical: "Plain to classical",
    purposeToEnglish: "Chinese to English",
    purposeMartian: "Martian text",
    prefixIdiom: "Idiom chain game: take the last character of the Chinese idiom below (a homophone is fine) and reply with exactly one new Chinese idiom. Return only that idiom, no explanation:\n",
    prefixClassical: "Rewrite the following plain Chinese text in literary classical Chinese, keeping the original meaning. Return only the rewritten text:\n",
    prefixToEnglish: "Translate the following Chinese text into natural, idiomatic English. Return only the translation:\n",
    prefixMartian: "Martian-text conversion: rewrite the following Chinese text in the Chinese-internet \"Martian\" style, mixing homophones, look-alike rare characters, Japanese kana and symbols (for example 我→涐, 你→伱, 的→の, 是→湜). Keep the sentence readable and keep its meaning. Return only the converted text, no explanation:\n",
    systemRole: "You follow the instruction strictly and output only the requested result, with no explanation and no small talk.",
    missingKey: "Add the API key for %1 in the plugin settings (field %2)",
    notSet: "not set",
    keyReady: "key set",
    keyMissing: "no key",
    missingConfig: "Complete the %1 service configuration first",
    invalidEndpoint: "Enter a valid HTTPS API address in plugin settings",
    emptyResult: "The AI returned an empty result",
    requestFailed: "Request failed: %1",
    retriedNote: " (retried %1x)",
    modelFixed: " (model auto-corrected to %1)",
    availableModels: "Available models: %1",
    sentAs: " [request: %1]"
  },
  "zh-CN": {
    name: "AI 助手",
    provider: "服务商",
    providerDescription: "会按所选服务商自动填入默认 API 地址，也可改为中转站或自定义地址；已配置的服务可作为备用。",
    providerCustom: "自定义（中转站）",
    providerCustomShort: "自定义",
    qwenShort: "通义千问",
    defaultPurposeLabel: "默认用途",
    defaultPurposeDescription: "每次打开助手使用此默认用途；窗口里的临时选择不会覆盖此设置。",
    baseUrl: "基础地址",
    baseUrlDescription: "填写基础地址（需要时含 /v1）或完整接口地址，接口路径随所选协议调整。",
    protocol: "API 协议",
    protocolDescription: "选择此端点支持的协议，决定接口路径、认证方式和请求格式。",
    maxTokens: "最大输出 Token 数",
    maxTokensDescription: "用于 Messages 和 Responses：1–131072，以模型上限为准。Responses 的推理 Token 也计入此额度。",
    apiVersion: "Anthropic API 版本",
    apiVersionDescription: "默认 2023-06-01 以兼容中转站，可按服务要求修改。",
    invalidTokenLimit: "最大输出 Token 数须为 1–131072 之间的整数",
    incompleteResult: "响应未完整生成，请提高输出上限或重试",
    refusedResult: "模型拒绝了此请求",
    customUrlDescription: "填写中转站基础地址或完整接口地址，再选择对应 API 协议。本输入法首次访问时会请求域名授权，无需编辑 JS。",
    model: "模型",
    modelDescription: "填对方支持的任意模型名。",
    apiKey: "API 密钥",
    apiKeyDescription: "由输入法 App 加密保存，插件不会拿到密钥内容。",
    purpose: "用途",
    inputLabel: "文本",
    inputPlaceholder: "输入要处理的内容",
    clear: "清空",
    close: "关闭",
    confirm: "确认上屏",
    send: "发送 AI",
    regenerate: "重新生成",
    outputLabel: "AI 回复",
    emptyInput: "请先输入需要交给 AI 处理的文本",
    assistantTitle: "AI 助手",
    resultTitle: "结果 · %1 · 确认上屏或清空",
    purposeShort: "简答",
    purposePolish: "润色",
    purposeProofread: "纠正错别字",
    purposeTone: "调整语气",
    prefixShort: "请简洁准确地回答下面的内容，只返回答案：\n",
    prefixPolish: "请润色下面的文本，保留原意，只返回改写后的文本：\n",
    prefixProofread: "请只纠正下面文本中的错别字和明显语病，保留原意，只返回修正后的文本：\n",
    prefixTone: "请将下面的文本调整为更自然、礼貌的语气，只返回改写后的文本：\n",
    purposeIdiom: "成语接龙",
    purposeClassical: "白话转文言",
    purposeToEnglish: "中译英",
    purposeMartian: "火星文",
    prefixIdiom: "成语接龙：请接住下面成语的最后一个字（同音字也可以），只返回你接上的那一个成语，不要任何解释：\n",
    prefixClassical: "请把下面的白话文改写成文言文，保留原意，只返回改写后的文言文：\n",
    prefixToEnglish: "请把下面的中文翻译成地道、自然的英文，只返回译文：\n",
    prefixMartian: "火星文转换：请把下面的文字改写成火星文，混用同音字、形近生僻字、日文假名和符号（例如 我→涐、你→伱、的→の、是→湜），整句要读得懂、意思不变。只返回转换后的文本，不要解释：\n",
    systemRole: "你严格按指令执行，只输出被要求的结果本身，不解释、不寒暄。",
    missingKey: "请先在插件设置中为「%1」填写 API 密钥（字段 %2）",
    notSet: "未设置",
    keyReady: "已配密钥",
    keyMissing: "缺密钥",
    missingConfig: "请先完成 %1 的服务配置",
    invalidEndpoint: "请在插件设置中填写有效的 HTTPS API 地址",
    emptyResult: "AI 返回了空内容",
    requestFailed: "请求失败：%1",
    retriedNote: "（已自动重试 %1 次）",
    modelFixed: "（模型已自动更正为 %1）",
    availableModels: "该地址可用模型：%1",
    sentAs: "【请求配置：%1】"
  },
  "zh-TW": {
    name: "AI 助手",
    provider: "服務商",
    providerDescription: "會依所選服務商自動填入預設 API 位址，也可改為中轉站或自訂位址；已設定的服務可作為備用。",
    providerCustom: "自訂（中轉站）",
    providerCustomShort: "自訂",
    qwenShort: "通義千問",
    defaultPurposeLabel: "預設用途",
    defaultPurposeDescription: "每次開啟助手使用此預設用途；視窗裡的暫時選擇不會覆蓋此設定。",
    baseUrl: "基礎位址",
    baseUrlDescription: "填寫基礎位址（需要時含 /v1）或完整介面位址，介面路徑隨所選協定調整。",
    protocol: "API 協定",
    protocolDescription: "選擇此端點支援的協定，決定介面路徑、驗證方式和請求格式。",
    maxTokens: "最大輸出 Token 數",
    maxTokensDescription: "用於 Messages 和 Responses：1–131072，以模型上限為準。Responses 的推理 Token 也計入此額度。",
    apiVersion: "Anthropic API 版本",
    apiVersionDescription: "預設 2023-06-01 以相容中轉站，可按服務要求修改。",
    invalidTokenLimit: "最大輸出 Token 數須為 1–131072 之間的整數",
    incompleteResult: "回應未完整產生，請提高輸出上限或重試",
    refusedResult: "模型拒絕了此請求",
    customUrlDescription: "填寫中轉站基礎位址或完整介面位址，再選擇對應 API 協定。本輸入法首次存取時會請求網域授權，無需編輯 JS。",
    model: "模型",
    modelDescription: "填對方支援的任意模型名稱。",
    apiKey: "API 金鑰",
    apiKeyDescription: "由輸入法 App 加密儲存，外掛不會取得金鑰內容。",
    purpose: "用途",
    inputLabel: "文字",
    inputPlaceholder: "輸入要處理的內容",
    clear: "清除",
    close: "關閉",
    confirm: "確認上屏",
    send: "傳送 AI",
    regenerate: "重新產生",
    outputLabel: "AI 回覆",
    emptyInput: "請先輸入需要交給 AI 處理的文字",
    assistantTitle: "AI 助手",
    resultTitle: "結果 · %1 · 確認送出或清除",
    purposeShort: "簡答",
    purposePolish: "潤色",
    purposeProofread: "校正錯別字",
    purposeTone: "調整語氣",
    prefixShort: "請簡潔準確地回答下列內容，只回傳答案：\n",
    prefixPolish: "請潤色下列文字，保留原意，只回傳改寫後的文字：\n",
    prefixProofread: "請只校正下列文字中的錯別字和明顯語病，保留原意，只回傳修正後的文字：\n",
    prefixTone: "請將下列文字調整為更自然、禮貌的語氣，只回傳改寫後的文字：\n",
    purposeIdiom: "成語接龍",
    purposeClassical: "白話轉文言",
    purposeToEnglish: "中譯英",
    purposeMartian: "火星文",
    prefixIdiom: "成語接龍：請接住下面成語的最後一個字（同音字也可以），只回傳你接上的那一個成語，不要任何解釋：\n",
    prefixClassical: "請把下面的白話文改寫成文言文，保留原意，只回傳改寫後的文言文：\n",
    prefixToEnglish: "請把下面的中文翻譯成道地、自然的英文，只回傳譯文：\n",
    prefixMartian: "火星文轉換：請把下面的文字改寫成火星文，混用同音字、形近生僻字、日文假名和符號（例如 我→涐、你→伱、的→の、是→湜），整句要讀得懂、意思不變。只回傳轉換後的文字，不要解釋：\n",
    systemRole: "你嚴格按指令執行，只輸出被要求的結果本身，不解釋、不寒暄。",
    missingKey: "請先在外掛設定中為「%1」填寫 API 金鑰（欄位 %2）",
    notSet: "未設定",
    keyReady: "已設金鑰",
    keyMissing: "缺金鑰",
    missingConfig: "請先完成 %1 的服務設定",
    invalidEndpoint: "請在外掛設定中填寫有效的 HTTPS API 位址",
    emptyResult: "AI 回傳了空內容",
    requestFailed: "請求失敗：%1",
    retriedNote: "（已自動重試 %1 次）",
    modelFixed: "（模型已自動更正為 %1）",
    availableModels: "該位址可用模型：%1",
    sentAs: "【請求設定：%1】"
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

function format(template, ...values) {
  let output = template;
  values.forEach((value, index) => {
    output = output.replace("%" + (index + 1), value);
  });
  return output;
}

const PURPOSE_KEYS = {
  short: {label: "purposeShort", prefix: "prefixShort"},
  polish: {label: "purposePolish", prefix: "prefixPolish"},
  proofread: {label: "purposeProofread", prefix: "prefixProofread"},
  tone: {label: "purposeTone", prefix: "prefixTone"},
  idiom: {label: "purposeIdiom", prefix: "prefixIdiom"},
  classical: {label: "purposeClassical", prefix: "prefixClassical"},
  toEnglish: {label: "purposeToEnglish", prefix: "prefixToEnglish"},
  martian: {label: "purposeMartian", prefix: "prefixMartian"}
};

function purposeOptions(t) {
  return PURPOSE_IDS.map((value, order) => ({
    value,
    label: t[PURPOSE_KEYS[value].label],
    order
  }))
    .sort((left, right) => Array.from(left.label).length - Array.from(right.label).length || left.order - right.order)
    .map(({value, label}) => ({value, label}));
}

function providerLabel(provider, t) {
  return (provider.labelKey && t[provider.labelKey]) || provider.label;
}

// 宿主可能回传 select 的 value，也可能回传显示文本（本地化名称，甚至带“· 域名 · 状态”后缀），
// 这里全部兼容；实在认不出来时保留上一次的选择，绝不静默跳回第一家。
function providerNames(provider) {
  const names = [provider.value, provider.label];
  [provider.labelKey, provider.shortKey].forEach(key => {
    if (!key) return;
    Object.keys(STRINGS).forEach(locale => {
      const text = STRINGS[locale][key];
      if (text) names.push(text);
    });
  });
  return names.filter(Boolean);
}

// 对话框下拉用短名，避免在窄列里被截成“自定义（”。
function providerShortLabel(provider, t) {
  if (provider.shortKey && t[provider.shortKey]) return t[provider.shortKey];
  return providerLabel(provider, t);
}

function dialogProviderOptions() {
  const t = stringsFor(foxy.locale);
  return PROVIDERS.map(provider => ({value: provider.value, label: providerShortLabel(provider, t)}));
}

function findProvider(value) {
  const raw = String(value === undefined || value === null ? "" : value).trim();
  if (!raw) {
    return PROVIDERS.find(item => item.value === currentProvider) || PROVIDERS[0];
  }
  const exact = PROVIDERS.find(item => providerNames(item).indexOf(raw) !== -1);
  if (exact) return exact;
  // 兼容 "自定义（中转站） · 端点主机名 · 已配密钥" 这类带后缀的显示文本
  const prefixed = PROVIDERS.find(item => providerNames(item).some(name => raw.indexOf(name) === 0));
  if (prefixed) return prefixed;
  return PROVIDERS.find(item => item.value === currentProvider) || PROVIDERS[0];
}

function findPurpose(value, t) {
  const id = PURPOSE_IDS.find(item => item === value)
    || PURPOSE_IDS.find(item => t[PURPOSE_KEYS[item].label] === value)
    || "short";
  return {id, label: t[PURPOSE_KEYS[id].label], prefix: t[PURPOSE_KEYS[id].prefix]};
}

function configuredProviders(configuredSecrets) {
  const secrets = Array.isArray(configuredSecrets) ? configuredSecrets : [];
  return PROVIDERS.filter(provider => secrets.includes(provider.credential));
}

function providerUrl(provider, settings) {
  return String((settings || {})[provider.value + "Url"] || "").trim();
}

// Keep the last temporary choice only while it still has an address and a configured key.
function pickProvider(settings, configuredSecrets) {
  const available = configuredProviders(configuredSecrets).filter(provider => providerUrl(provider, settings));
  const remembered = available.find(provider => provider.value === lastProvider);
  if (remembered) return remembered;
  const preferred = findProvider(settings.defaultProvider);
  return available.find(provider => provider.value === preferred.value) || available[0] || preferred;
}

function providerProtocol(provider, settings) {
  const value = String((settings || {})[provider.value + "Protocol"] || provider.protocol || "chat");
  if (!PROTOCOLS.some(protocol => protocol.value === value)) throw new Error("Unsupported API protocol: " + value);
  return value;
}

// A complete endpoint can be switched between protocols without duplicating path segments.
// Preserve relay prefixes and query strings; the configured base decides where /v1 belongs.
function endpointUrl(base, protocol = "chat") {
  const definition = PROTOCOLS.find(item => item.value === protocol);
  if (!definition) throw new Error("Unsupported API protocol: " + protocol);
  const raw = String(base || "").replace(/[\u200b-\u200d\ufeff]/g, "").trim();
  if (!raw) return "";
  const clean = raw.split("#")[0].replace(/\s+/g, "");
  const queryIndex = clean.indexOf("?");
  const path = (queryIndex === -1 ? clean : clean.slice(0, queryIndex)).replace(/\/+$/, "");
  const query = queryIndex === -1 ? "" : clean.slice(queryIndex);
  if (!path) return "";
  return path.replace(/\/(?:chat\/completions|messages|responses)$/i, "") + definition.path + query;
}

function outputTokenLimit(provider, settings, t) {
  const raw = String(settings[provider.value + "MaxTokens"] || "4096").trim();
  const value = Number(raw);
  if (!/^\d+$/.test(raw) || !Number.isSafeInteger(value) || value < 1 || value > 131072) {
    throw new Error(t.invalidTokenLimit);
  }
  return value;
}

// A credential alias is passed to the host; the script never handles the key itself.
function protocolTransport(protocol, provider, settings) {
  if (protocol === "anthropic") {
    return {
      credential: provider.credential,
      authHeader: "x-api-key",
      authPrefix: "",
      headers: {
        "Accept": "application/json",
        "anthropic-version": String(settings[provider.value + "ApiVersion"] || "2023-06-01").trim()
      }
    };
  }
  return {credential: provider.credential, authHeader: "Authorization", authPrefix: "Bearer ",
    headers: {"Accept": "application/json"}};
}

function textRequest(provider, settings, model, purpose, input, t) {
  const protocol = providerProtocol(provider, settings);
  const prompt = purpose.prefix + input;
  const body = {model, stream: false};
  if (protocol === "anthropic") {
    body.max_tokens = outputTokenLimit(provider, settings, t);
    body.system = t.systemRole;
    body.messages = [{role: "user", content: prompt}];
  } else if (protocol === "responses") {
    body.max_output_tokens = outputTokenLimit(provider, settings, t);
    body.instructions = t.systemRole;
    body.input = [{role: "user", content: [{type: "input_text", text: prompt}]}];
    body.store = false;
  } else {
    body.messages = [{role: "system", content: t.systemRole}, {role: "user", content: prompt}];
  }
  const transport = protocolTransport(protocol, provider, settings);
  return {
    url: endpointUrl(settings[provider.value + "Url"] || provider.url, protocol),
    method: "POST",
    ...transport,
    headers: {...transport.headers, "Content-Type": "application/json"},
    body: JSON.stringify(body)
  };
}

function hostOf(url) {
  const match = /^https?:\/\/([^\/?#]+)/i.exec(String(url || "").trim());
  if (!match) return "";
  return match[1].split("@").pop().split(":")[0].toLowerCase();
}

// Tolerates the usual reply shapes: OpenAI, relays that mirror it, and
// endpoints that answer with choice.text or a top-level output_text/result.
function extractContent(data) {
  if (!data || typeof data !== "object") return "";
  const choices = data.choices || (data.data && data.data.choices) || null;
  const choice = choices && choices[0] ? choices[0] : null;
  let content = "";
  if (choice) {
    const message = choice.message || choice.delta || null;
    if (message) {
      if (typeof message.content === "string") {
        content = message.content;
      } else if (Array.isArray(message.content)) {
        content = message.content.map(part => {
          if (typeof part === "string") return part;
          if (part && typeof part.text === "string" &&
              (!part.type || part.type === "text" || part.type === "output_text")) return part.text;
          return "";
        }).join("");
      }
    }
    if (!content && typeof choice.text === "string") content = choice.text;
  }
  if (!content && typeof data.output_text === "string") content = data.output_text;
  if (!content && typeof data.result === "string") content = data.result;
  return String(content || "").trim();
}

// Native responses contain reasoning/tool blocks as well as visible text. Only return final text.
function extractProtocolContent(data, protocol, t) {
  if (!data || typeof data !== "object") return "";
  if (data.error) throw new Error(extractError(data, t.emptyResult));
  if (protocol === "anthropic") {
    if (data.stop_reason === "refusal") throw new Error(t.refusedResult);
    if (["max_tokens", "tool_use", "pause_turn"].includes(data.stop_reason)) throw new Error(t.incompleteResult);
    return (Array.isArray(data.content) ? data.content : [])
      .filter(block => block && block.type === "text" && typeof block.text === "string")
      .map(block => block.text).join("").trim();
  }
  if (protocol === "responses") {
    if (data.status && data.status !== "completed") {
      const reason = data.incomplete_details && data.incomplete_details.reason;
      throw new Error(t.incompleteResult + (reason ? " (" + reason + ")" : ""));
    }
    const messages = (Array.isArray(data.output) ? data.output : [])
      .filter(item => item && item.type === "message" && item.role === "assistant");
    const blocks = messages.reduce((all, item) => all.concat(Array.isArray(item.content) ? item.content : []), []);
    if (blocks.some(block => block && block.type === "refusal")) throw new Error(t.refusedResult);
    if (messages.some(message => message.status && message.status !== "completed")) throw new Error(t.incompleteResult);
    const text = blocks.filter(block => block && block.type === "output_text" && typeof block.text === "string")
      .map(block => block.text).join("");
    return (text || (typeof data.output_text === "string" ? data.output_text : "")).trim();
  }
  const choices = data.choices || (data.data && data.data.choices);
  const choice = Array.isArray(choices) ? choices[0] : null;
  if (choice) {
    const message = choice.message || choice.delta || {};
    if (choice.finish_reason === "content_filter" || message.refusal ||
        (Array.isArray(message.content) && message.content.some(part => part && part.type === "refusal"))) {
      throw new Error(t.refusedResult);
    }
    if (["length", "tool_calls", "function_call"].includes(choice.finish_reason) ||
        (Array.isArray(message.tool_calls) && message.tool_calls.length) || message.function_call) {
      throw new Error(t.incompleteResult);
    }
  }
  return extractContent(data);
}

function extractError(data, fallback) {
  if (data && data.error) {
    if (typeof data.error === "string") return data.error;
    if (typeof data.error.message === "string") return data.error.message;
    if (typeof data.error.msg === "string") return data.error.msg;
  }
  if (data && typeof data.message === "string" && data.message) return data.message;
  if (data && typeof data.msg === "string" && data.msg) return data.msg;
  return String(fallback || "");
}

function safeParse(text) {
  try {
    return JSON.parse(String(text || ""));
  } catch (error) {
    return null;
  }
}

// 中转站的上游通道经常对热门模型做速率限制，会秒回 400/429/5xx。
// 这类失败重试通常就过去了，所以这里做有限次自动重试（总预算控制在 60 秒内）。
const RETRY_ATTEMPTS = 3;
const RETRY_STEP_MS = 900;
const RETRY_TOTAL_BUDGET_MS = 60000;
const RETRYABLE_PATTERN = /throttl|rate.?limit|too many requests|quota|overload|busy|try again|temporar|timeout|timed out/i;

function sleep(ms) {
  return foxy.app.sleep(ms);
}

// 这些错误重试多少次都不会好（模型不存在、密钥无效、余额不足），直接报错。
const FATAL_PATTERN = /model_not_found|no available channel|invalid.?api.?key|invalid token|unauthorized|forbidden|insufficient|permission[^\n]*(?:denied|revoked)|credential[^\n]*(?:unavailable|undeclared|not configured)|余额|欠费|无可用渠道/i;

function isRetryable(status, text) {
  const source = String(text || "");
  if (FATAL_PATTERN.test(source)) return false;
  if (status >= 500 || status === 429 || status === 408) return true;
  return RETRYABLE_PATTERN.test(source);
}

async function postWithRetry(options, t, protocol) {
  const startedAt = Date.now();
  let attempts = 0;
  let lastError = null;
  while (attempts < RETRY_ATTEMPTS) {
    attempts += 1;
    const attemptStartedAt = Date.now();
    const remaining = RETRY_TOTAL_BUDGET_MS - (attemptStartedAt - startedAt);
    if (remaining <= 0) throw lastError || new Error(t.incompleteResult);
    const retryDelay = RETRY_STEP_MS * attempts;
    const exhausted = () => attempts >= RETRY_ATTEMPTS || Date.now() - attemptStartedAt > 20000 ||
      Date.now() - startedAt + retryDelay >= RETRY_TOTAL_BUDGET_MS;
    let response = null;
    try {
      response = JSON.parse(await foxy.http.request({...options, timeoutMs: remaining}));
    } catch (transportError) {
      lastError = transportError instanceof Error ? transportError : new Error(String(transportError));
      lastError.attempts = attempts;
      if (FATAL_PATTERN.test(lastError.message) || exhausted()) throw lastError;
      await sleep(retryDelay);
      continue;
    }
    const body = String(response.body || "");
    const data = safeParse(body);
    if (response.ok) {
      const result = extractProtocolContent(data, protocol, t);
      if (result) return {result, attempts};
      lastError = new Error(extractError(data, t.emptyResult).slice(0, 160));
    } else {
      lastError = new Error("HTTP " + response.status + " " + extractError(data, body).slice(0, 160));
    }
    lastError.attempts = attempts;
    if (exhausted()) throw lastError;
    if (!isRetryable(response.status, body)) throw lastError;
    await sleep(retryDelay);
  }
  throw lastError || new Error(t.requestFailed);
}

// 各家中转站对模型名大小写/空格的规矩不一样，这里做统一清洗。
const MODEL_ERROR_PATTERN = /model_not_found|not supported by any configured account|no available channel|invalid model|model.*not.*exist|不存在的模型|无可用渠道/i;

function cleanModelName(value) {
  return String(value || "").replace(/[\u200b-\u200d\ufeff]/g, "").trim();
}

// Keep the relay prefix/query and use the same protocol authentication for model discovery.
function modelsUrl(url) {
  const parts = String(url || "").split("?");
  return parts[0].replace(/\/(?:chat\/completions|messages|responses)$/i, "/models") +
    (parts.length > 1 ? "?" + parts.slice(1).join("?") : "");
}

async function listModels(url, provider, settings, protocol) {
  const base = modelsUrl(url);
  const candidates = [base];
  const alt = base.replace(/\/models(?=\?|$)/, "/v1/models");
  if (alt !== base) candidates.push(alt);
  for (const target of candidates) {
    const host = hostOf(target);
    if (!host) continue;
    try {
      const raw = await foxy.http.request({
        url: target,
        method: "GET",
        ...protocolTransport(protocol, provider, settings)
      });
      const response = JSON.parse(raw);
      if (!response.ok) continue;
      const data = safeParse(String(response.body || ""));
      const list = data && Array.isArray(data.data) ? data.data : null;
      if (!list) continue;
      const names = list.map(item => cleanModelName(item && (item.id || item.name))).filter(Boolean);
      if (names.length) return names;
    } catch (error) {
      // 探测失败就算了，不影响主流程。
    }
  }
  return [];
}

// 只在「纯大小写不同」时才自动更正，避免把用户搞混。
function matchModelCase(list, model) {
  const wanted = String(model || "").toLowerCase();
  const hits = list.filter(name => name.toLowerCase() === wanted);
  return hits.length === 1 ? hits[0] : "";
}

let currentProvider = "deepseek";
let lastResult = "";
let lastResultRequest = "";
let lastPurposeId = "";
let lastProvider = "";
let activeSettings = {};
let activeSecrets = [];

// 把 http(s):// 去掉，标题栏里省地方。
function briefUrl(url) {
  return String(url || "").replace(/^https?:\/\//i, "");
}

// 每个服务商实际会用哪个地址、哪个模型，直接显示在界面上，
// 避免“设置改了 A，界面用的却是 B 的旧值”这种错觉。
function providerEndpoint(provider, settings) {
  const source = settings || {};
  const protocol = providerProtocol(provider, source);
  const url = endpointUrl(source[provider.value + "Url"] || provider.url, protocol);
  const model = cleanModelName(source[provider.value + "Model"] || provider.model);
  return {url, model, protocol};
}

// settings / configuredSecrets 传 null 时只显示纯名称（设置页读不到已存值）。
function providerOptions(settings) {
  const t = stringsFor(foxy.locale);
  const secrets = Array.isArray(arguments[1]) ? arguments[1] : null;
  return PROVIDERS.map(provider => {
    const parts = [providerLabel(provider, t)];
    if (settings) {
      const endpoint = providerEndpoint(provider, settings);
      parts.push(endpoint.url ? briefUrl(hostOf(endpoint.url) || endpoint.url) : t.notSet);
    }
    if (secrets) parts.push(secrets.indexOf(provider.credential) === -1 ? t.keyMissing : t.keyReady);
    return {value: provider.value, label: parts.join(" · ")};
  });
}

function endpointSummary(provider, settings, secrets) {
  const t = stringsFor(foxy.locale);
  const endpoint = providerEndpoint(provider, settings);
  const keyState = Array.isArray(secrets)
    ? (secrets.indexOf(provider.credential) === -1 ? t.keyMissing : t.keyReady)
    : "";
  if (!endpoint.url) return t.notSet + (keyState ? " · " + keyState : "");
  const parts = ["POST " + briefUrl(endpoint.url), PROTOCOLS.find(item => item.value === endpoint.protocol).label, endpoint.model || t.notSet];
  if (keyState) parts.push(keyState);
  return parts.join(" · ");
}

function dialogSpec(t, title, purposeId, input, messageTone = "") {
  const endpoint = providerEndpoint(findProvider(currentProvider), activeSettings);
  const compactEndpoint = endpoint.url ? [hostOf(endpoint.url), endpoint.model].filter(Boolean).join(" · ") : t.notSet;
  return {
    maxWidth: 360,
    maxHeight: 460,
    layout: {
      type: "column",
      gap: 8,
      children: [
        {type: "header", gap: 8, children: [
          {type: "text", text: t.assistantTitle, size: 20, bold: true, icon: "assistant", weight: 1},
          {type: "button", id: "close", label: t.close, style: "text", icon: "close", iconOnly: true}
        ]},
        ...(title !== t.assistantTitle ? [{
          type: "text", text: title, size: 12, muted: true, tone: messageTone, maxLines: 3
        }] : []),
        {
          type: "row",
          gap: 12,
          children: [
            {
              type: "select",
              id: "purpose",
              label: t.purpose,
              labelPlacement: "top",
              options: purposeOptions(t),
              value: purposeId,
              weight: 1,
              height: 48
            },
            {
              type: "select",
              id: "provider",
              label: t.provider,
              labelPlacement: "top",
              options: dialogProviderOptions(),
              value: currentProvider,
              weight: 1,
              height: 48
            }
          ]
        },
        {
          type: "text",
          text: compactEndpoint,
          description: endpointSummary(findProvider(currentProvider), activeSettings, activeSecrets),
          size: 12,
          muted: true,
          maxLines: 1
        },
        {
          type: "input",
          id: "input",
          label: t.inputLabel,
          labelPlacement: "top",
          multiline: true,
          value: input,
          placeholder: t.inputPlaceholder,
          height: lastResult ? 56 : 88
        },
        ...(lastResult ? [{
          type: "input", id: "result", label: t.outputLabel, labelPlacement: "top",
          multiline: true, readOnly: true, value: lastResult, height: 96
        }] : []),
        {
          type: "actions",
          children: [
            {type: "button", id: "clear", label: t.clear, style: "text"},
            ...(lastResult ? [
              {type: "button", id: "send", label: t.regenerate, style: "tonal"},
              {type: "button", id: "confirm", label: t.confirm, style: "filled", icon: "check"}
            ] : [{type: "button", id: "send", label: t.send, style: "filled", icon: "send"}])
          ]
        }
      ]
    }
  };
}

foxy.plugin.register({
  id: "llm-assistant",
  name: stringsFor(foxy.locale).name,
  version: "1.23.5",
  hosts: [],
  features: [],
  configure(ui, locale) {
    const t = stringsFor(locale);
    ui.select("defaultPurpose", t.defaultPurposeLabel, purposeOptions(t), "short", {
      description: t.defaultPurposeDescription
    });
    ui.select("defaultProvider", t.provider, providerOptions(null), "deepseek", {
      description: t.providerDescription
    });
    PROVIDERS.forEach(provider => {
      const when = {key: "defaultProvider", value: provider.value};
      const custom = provider.value === "custom";
      const urlSettings = {visibleWhen: when, description: t.baseUrlDescription};
      if (custom) urlSettings.description = t.customUrlDescription;
      ui.text(provider.value + "Url", t.baseUrl, provider.url, urlSettings);
      ui.secret(provider.value + "Key", t.apiKey, {
        visibleWhen: when,
        description: t.apiKeyDescription
      });
      ui.select(provider.value + "Protocol", t.protocol, PROTOCOLS.map(({value, label}) => ({value, label})),
        provider.protocol || "chat", {visibleWhen: when, description: t.protocolDescription});
      // Keep each provider's existing Url/Model/Key field IDs and values intact.
      ui.number(provider.value + "MaxTokens", t.maxTokens, "4096", {
        visibleWhen: when,
        description: t.maxTokensDescription
      });
      ui.text(provider.value + "ApiVersion", t.apiVersion, "2023-06-01", {
        visibleWhen: {all: [when, {key: provider.value + "Protocol", value: "anthropic"}]},
        description: t.apiVersionDescription
      });
      ui.text(provider.value + "Model", t.model, provider.model, custom
        ? {visibleWhen: when, description: t.modelDescription}
        : {visibleWhen: when});
    });
  }
});

foxy.app.register({
  id: "llm_assistant",
  label: stringsFor(foxy.locale).name,
  permissions: [
    "editor.read_selection",
    "editor.composition",
    "editor.commit",
    "network.request",
    "credentials.list"
  ],
  async onOpen(context) {
    // 任何意外异常都必须显示出来，绝不静默失败。
    try {
      return await handleOpen(context);
    } catch (error) {
      lastResult = "";
      lastResultRequest = "";
      const t = stringsFor(foxy.locale);
      activeSettings = (context && context.settings) || {};
      const values = (context && context.values) || {};
      const message = String(error && error.message ? error.message : error).slice(0, 160);
      return foxy.ui.dialog.show(dialogSpec(
        t,
        format(t.requestFailed, message || "unknown error"),
        findPurpose(values.purpose, t).id,
        values.input || ""
      ));
    }
  }
});

function resultRequestKey(input, provider, purpose, settings) {
  return JSON.stringify([input, provider.value, purpose.id, settings]);
}

async function clearResult() {
  lastResult = "";
  lastResultRequest = "";
  await foxy.editor.clearComposition();
}

async function handleOpen(context) {
    const t = stringsFor(foxy.locale);
    if (context.event === "dismiss") {
      lastResult = "";
      lastResultRequest = "";
      return {close: true};
    }
    const settings = context.settings || {};
    const configuredSecrets = context.configuredSecrets || [];
    activeSettings = settings;
    activeSecrets = configuredSecrets;

    if (context.event === "open") {
      lastResult = "";
      lastResultRequest = "";
      if (!lastPurposeId) lastPurposeId = findPurpose(settings.defaultPurpose, t).id;
      currentProvider = pickProvider(settings, configuredSecrets).value;
      lastProvider = currentProvider;
      const selected = await foxy.editor.getSelection();
      return foxy.ui.dialog.show(dialogSpec(t, t.assistantTitle, lastPurposeId, selected));
    }

    const values = context.values || {};
    const purpose = findPurpose(values.purpose, t);
    const provider = findProvider(values.provider);
    const input = String(values.input || "");
    const requestKey = resultRequestKey(input, provider, purpose, settings);
    if (lastResult && lastResultRequest !== requestKey) await clearResult();
    currentProvider = provider.value;
    lastPurposeId = purpose.id;
    lastProvider = provider.value;

    // 宿主把下拉的 id 当作事件名回传；切换用途/服务商时重画对话框，
    // 让下面那行 “POST 地址 · 模型 · 密钥” 实时跟着变。
    if (context.event === "purpose" || context.event === "provider") {
      return foxy.ui.dialog.show(dialogSpec(t, t.assistantTitle, purpose.id, values.input || ""));
    }
    const providerName = providerLabel(provider, t);

    if (context.event === "clear") {
      await clearResult();
      return foxy.ui.dialog.show(dialogSpec(t, t.assistantTitle, purpose.id, ""));
    }
    if (context.event === "close" || context.event === "cancel") {
      await clearResult();
      return {close: true};
    }
    if (context.event === "confirm" && lastResult) {
      await foxy.editor.commit(lastResult);
      lastResult = "";
      lastResultRequest = "";
      return {close: true};
    }
    if (context.event !== "send" && context.event !== "confirm") {
      return foxy.ui.dialog.show(dialogSpec(t, t.assistantTitle, purpose.id, input));
    }

    // A legacy confirm without a current result means generate, never dismiss.
    // Invalidate earlier previews before every generation, including validation failures.
    await clearResult();
    if (!input.trim()) {
      return foxy.ui.dialog.show(dialogSpec(t, t.emptyInput, purpose.id, input, "error"));
    }
    if (configuredSecrets.indexOf(provider.credential) === -1) {
      return foxy.ui.dialog.show(dialogSpec(
        t,
        format(t.missingKey, providerName, provider.credential),
        purpose.id,
        values.input || "", "error"
      ));
    }
    const {url, model, protocol} = providerEndpoint(provider, settings);
    if (!url || !model) {
      return foxy.ui.dialog.show(dialogSpec(
        t,
        format(t.missingConfig, providerName),
        purpose.id,
        values.input || "", "error"
      ));
    }
    const host = hostOf(url);
    if (!host || !/^https:\/\//i.test(url)) {
      return foxy.ui.dialog.show(dialogSpec(
        t,
        t.invalidEndpoint,
        purpose.id,
        values.input || "", "error"
      ));
    }

    const requestOptions = usedModel => textRequest(provider, settings, usedModel, purpose, values.input || "", t);

    let usedModel = model;
    let fixedNote = "";
    try {
      let outcome;
      try {
        outcome = await postWithRetry(requestOptions(usedModel), t, protocol);
      } catch (error) {
        // 模型名不被接受时，去问一下这个地址到底有哪些模型；
        // 如果只是大小写不同，就直接用正确写法重发一次，用户无感。
        const message = String(error && error.message ? error.message : error);
        if (!MODEL_ERROR_PATTERN.test(message)) throw error;
        const list = await listModels(url, provider, settings, protocol);
        const corrected = matchModelCase(list, usedModel);
        if (corrected && corrected !== usedModel) {
          usedModel = corrected;
          fixedNote = format(t.modelFixed, corrected);
          outcome = await postWithRetry(requestOptions(usedModel), t, protocol);
        } else if (list.length) {
          error.message = message + " · " + format(t.availableModels, list.slice(0, 8).join(", "));
          throw error;
        } else {
          throw error;
        }
      }
      await foxy.editor.setComposition(outcome.result);
      lastResult = outcome.result;
      lastResultRequest = requestKey;
      const note = (outcome.attempts > 1 ? format(t.retriedNote, outcome.attempts - 1) : "") + fixedNote;
      return foxy.ui.dialog.show(dialogSpec(
        t,
        format(t.resultTitle, purpose.label) + note,
        purpose.id,
        values.input || ""
      ));
    } catch (error) {
      lastResult = "";
      lastResultRequest = "";
      const note = (error && error.attempts > 1 ? format(t.retriedNote, error.attempts - 1) : "") + fixedNote;
      const trace = format(t.sentAs, "POST " + url + " · model=" + JSON.stringify(usedModel));
      return foxy.ui.dialog.show(dialogSpec(
        t,
        format(t.requestFailed, String(error.message || error)) + note + trace,
        purpose.id,
        values.input || "", "error"
      ));
    }
}
