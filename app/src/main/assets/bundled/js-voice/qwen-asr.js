const DEFAULTS = {
  bailian: {
    streamEndpoint: "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
    streamModel: "qwen-audio-3.0-asr-flash-streaming",
    flashEndpoint: "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
    flashModel: "qwen-audio-3.0-asr-flash"
  },
  "qwen-ai": {
    streamEndpoint: "wss://cn-beijing.maas.qianwenaiapi.com/api-ws/v1/inference",
    streamModel: "qwen-audio-3.1-asr-flash-message",
    flashEndpoint: "https://cn-beijing.maas.qianwenaiapi.com/api/v1/services/aigc/multimodal-generation/generation",
    flashModel: "qwen-audio-3.1-asr-flash"
  }
};

const LEGACY_QWEN_ENDPOINTS = {
  streamEndpoint: "wss://maas.qianwenaiapi.com/api-ws/v1/inference",
  flashEndpoint: "https://maas.qianwenaiapi.com/api/v1/services/aigc/multimodal-generation/generation"
};

const STREAM_MODELS = [
  "qwen-audio-3.0-asr-flash-streaming",
  "qwen-audio-3.1-asr-flash-streaming",
  "qwen-audio-3.1-asr-flash-message",
  "paraformer-realtime-v2",
  "qwen3-asr-flash-realtime"
];

const FLASH_MODELS = [
  "qwen-audio-3.0-asr-flash",
  "qwen-audio-3.1-asr-flash"
];

const STRINGS = {
  en: {
    name: "Qwen ASR",
    apiKey: "Platform API key",
    apiKeyDescription: "Stored securely by Foxy; never exposed to the plugin script.",
    platform: "Platform",
    bailian: "Bailian / DashScope",
    qwenAi: "Qwen AI",
    platformDescription: "Each platform has its own default endpoints and models.",
    mode: "Mode",
    streaming: "Streaming (WebSocket)",
    flash: "Flash (HTTP)",
    modeDescription: "Streaming shows text as you speak; Flash transcribes after release.",
    streamEndpoint: "Streaming endpoint",
    streamEndpointDescription: "Keep the default to use the selected platform's endpoint.",
    streamModel: "Streaming model",
    streamModelDescription: "Choose a preset or enter a model ID.",
    heartbeat: "Keep connection during silence",
    flashEndpoint: "Flash endpoint",
    flashEndpointDescription: "Keep the default to use the selected platform's endpoint.",
    flashModel: "Flash model",
    flashModelDescription: "Choose a preset or enter a model ID.",
    itn: "Normalize spoken numbers and dates",
    stripPunctuation: "Strip trailing punctuation",
    stripCharacters: "Punctuation to strip",
    stripCharactersDescription: "Characters removed from the end of each final result."
  },
  "zh-CN": {
    name: "通义千问语音识别",
    apiKey: "平台 API 密钥",
    apiKeyDescription: "由 Foxy 加密保存，不会传给插件脚本。",
    platform: "平台",
    bailian: "百炼 / DashScope",
    qwenAi: "通义千问 AI",
    platformDescription: "各平台有各自的默认地址和模型。",
    mode: "识别模式",
    streaming: "实时流式识别（WebSocket）",
    flash: "Flash 单次识别（HTTP）",
    modeDescription: "流式模式边说边显示文字；Flash 模式在松开后识别。",
    streamEndpoint: "流式识别地址",
    streamEndpointDescription: "保持默认值即可使用所选平台的地址。",
    streamModel: "流式识别模型",
    streamModelDescription: "可选预设模型，也可输入模型 ID。",
    heartbeat: "静音时保持连接",
    flashEndpoint: "Flash 识别地址",
    flashEndpointDescription: "保持默认值即可使用所选平台的地址。",
    flashModel: "Flash 识别模型",
    flashModelDescription: "可选预设模型，也可输入模型 ID。",
    itn: "规范化口语数字和日期",
    stripPunctuation: "去除末尾标点",
    stripCharacters: "去除的标点字符",
    stripCharactersDescription: "从每次最终结果的末尾移除这些字符。"
  },
  "zh-TW": {
    name: "通義千問語音辨識",
    apiKey: "平台 API 金鑰",
    apiKeyDescription: "由 Foxy 加密儲存，不會傳給外掛指令碼。",
    platform: "平台",
    bailian: "百煉 / DashScope",
    qwenAi: "通義千問 AI",
    platformDescription: "各平台有各自的預設端點和模型。",
    mode: "辨識模式",
    streaming: "即時串流辨識（WebSocket）",
    flash: "Flash 單次辨識（HTTP）",
    modeDescription: "串流模式邊說邊顯示文字；Flash 模式在放開後辨識。",
    streamEndpoint: "串流辨識端點",
    streamEndpointDescription: "保持預設值即可使用所選平台的端點。",
    streamModel: "串流辨識模型",
    streamModelDescription: "可選預設模型，也可輸入模型 ID。",
    heartbeat: "靜音時保持連線",
    flashEndpoint: "Flash 辨識端點",
    flashEndpointDescription: "保持預設值即可使用所選平台的端點。",
    flashModel: "Flash 辨識模型",
    flashModelDescription: "可選預設模型，也可輸入模型 ID。",
    itn: "正規化口語數字和日期",
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

const MAX_FLASH_AUDIO_SECONDS = 30;
const DEFAULT_STRIP_CHARACTERS = ".,!?;:，。！？；：、…⋯~～";
let state = { mode: "streaming", chunks: [], bytes: 0, stripPunctuation: false, stripChars: "" };

function platform(context) {
  return context.platform === "qwen-ai" ? "qwen-ai" : "bailian";
}

function resolve(context, key) {
  const selected = platform(context);
  const own = context[key];
  if (selected === "qwen-ai" &&
      (!own || own === DEFAULTS.bailian[key] || own === LEGACY_QWEN_ENDPOINTS[key])) {
    return DEFAULTS["qwen-ai"][key];
  }
  return own || DEFAULTS[selected][key];
}

function finalText(text, stripPunctuation, stripChars) {
  const value = String(text || "").trim();
  if (!stripPunctuation || !stripChars) return value;
  let end = value.length;
  while (end > 0 && stripChars.indexOf(value[end - 1]) !== -1) end--;
  return end === value.length ? value : value.slice(0, end);
}

function parseTranscript(body) {
  const json = JSON.parse(body);
  const output = json.output || json;
  if (output.output && output.output.sentence && output.output.sentence.text) {
    return String(output.output.sentence.text).trim();
  }
  if (output.text) return String(output.text).trim();
  const choices = output.choices || [];
  const parts = [];
  for (const choice of choices) {
    const content = choice && choice.message && choice.message.content;
    if (typeof content === "string") {
      if (content.trim()) parts.push(content.trim());
    } else if (Array.isArray(content)) {
      for (const item of content) {
        if (item && item.text && item.text.trim()) parts.push(item.text.trim());
      }
    }
  }
  return parts.join("\n");
}

foxy.plugin.register({
  id: "qwen-asr",
  name: stringsFor(foxy.locale).name,
  version: "1.6.3",
  hosts: ["dashscope.aliyuncs.com", "maas.qianwenaiapi.com", "cn-beijing.maas.qianwenaiapi.com"],
  features: [{type: "voice.asr", id: "qwen-asr", label: stringsFor(foxy.locale).name, sampleRate: 16000}],
  configure(ui, locale) {
    const t = stringsFor(locale);
    ui.secret("apiKey", t.apiKey, {
      description: t.apiKeyDescription
    });
    ui.select("platform", t.platform, [
      {label: t.bailian, value: "bailian"},
      {label: t.qwenAi, value: "qwen-ai"}
    ], "bailian", {
      description: t.platformDescription
    });
    ui.select("mode", t.mode, [
      {label: t.streaming, value: "streaming"},
      {label: t.flash, value: "flash"}
    ], "streaming", {
      description: t.modeDescription
    });
    ui.switch("stripPunctuation", t.stripPunctuation, true);
    ui.text("stripCharacters", t.stripCharacters, DEFAULT_STRIP_CHARACTERS, {
      visibleWhen: {key: "stripPunctuation", value: "true"},
      description: t.stripCharactersDescription
    });
    ui.text("streamEndpoint", t.streamEndpoint, DEFAULTS.bailian.streamEndpoint, {
      visibleWhen: {key: "mode", value: "streaming"},
      description: t.streamEndpointDescription
    });
    ui.select("streamModel", t.streamModel, STREAM_MODELS, DEFAULTS.bailian.streamModel, {
      visibleWhen: {key: "mode", value: "streaming"},
      allowCustom: true,
      description: t.streamModelDescription
    });
    ui.switch("heartbeat", t.heartbeat, true, {
      visibleWhen: {key: "mode", value: "streaming"}
    });
    ui.text("flashEndpoint", t.flashEndpoint, DEFAULTS.bailian.flashEndpoint, {
      visibleWhen: {key: "mode", value: "flash"},
      description: t.flashEndpointDescription
    });
    ui.select("flashModel", t.flashModel, FLASH_MODELS, DEFAULTS.bailian.flashModel, {
      visibleWhen: {key: "mode", value: "flash"},
      allowCustom: true,
      description: t.flashModelDescription
    });
    ui.switch("itn", t.itn, false, {
      visibleWhen: {all: [
        {key: "mode", value: "flash"},
        {key: "platform", value: "qwen-ai"}
      ]}
    });
  },
  async start(context) {
    state = {
      mode: context.mode === "flash" ? "flash" : "streaming",
      chunks: [],
      bytes: 0,
      stripPunctuation: context.stripPunctuation !== "false",
      stripChars: context.stripCharacters != null ? String(context.stripCharacters) : DEFAULT_STRIP_CHARACTERS
    };
    if (state.mode === "flash") {
      foxy.voice.ready();
      return "flash";
    }

    const endpoint = resolve(context, "streamEndpoint");
    const model = resolve(context, "streamModel");
    const socket = foxy.websocket.connect(endpoint, "apiKey");
    await foxy.websocket.waitOpen(socket);
    const parameters = {
      sample_rate: context.sampleRate,
      format: "pcm",
      heartbeat: context.heartbeat !== "false"
    };
    if (model.toLowerCase().includes("message")) {
      parameters.intermediate_result_enabled = true;
    }
    await foxy.websocket.send(socket, JSON.stringify({
      header: {action: "run-task", task_id: context.taskId, streaming: "duplex"},
      payload: {
        task_group: "audio",
        task: "asr",
        function: "recognition",
        model: model,
        parameters: parameters,
        input: {}
      }
    }));
    return socket;
  },
  async audio(context) {
    if (state.mode === "flash") {
      const chunkBytes = foxy.binary.byteLength(context.pcm);
      const limit = Math.max(1, Number(context.sampleRate) || 16000) * 2 * MAX_FLASH_AUDIO_SECONDS;
      if (state.bytes + chunkBytes > limit) throw new Error("Flash recognition is limited to 30 seconds of audio");
      state.bytes += chunkBytes;
      state.chunks.push(context.pcm);
      return;
    }
    await foxy.websocket.sendBinary(context.socket, context.pcm);
  },
  async stop(context) {
    if (state.mode === "flash") {
      const pcm = foxy.binary.concatBase64(state.chunks);
      const stripPunctuation = state.stripPunctuation;
      const stripChars = state.stripChars;
      state = {mode: "streaming", chunks: [], bytes: 0, stripPunctuation: false, stripChars: ""};
      if (!pcm) return {ended: true};

      const endpoint = resolve(context, "flashEndpoint");
      const model = resolve(context, "flashModel");
      const selected = platform(context);
      const wav = foxy.audio.wavBase64(pcm, context.sampleRate);
      const dataUrl = "data:audio/wav;base64," + wav;
      const audioContent = selected === "qwen-ai"
        ? {audio: dataUrl}
        : {type: "input_audio", input_audio: {data: dataUrl}};
      const parameters = selected === "qwen-ai"
        ? {result_format: "message", asr_options: {enable_itn: context.itn === "true"}}
        : {format: "wav", sample_rate: String(context.sampleRate)};
      const body = await foxy.http.post(endpoint, "apiKey", JSON.stringify({
        model: model,
        input: {
          messages: [{
            role: "user",
            content: [audioContent]
          }]
        },
        parameters: parameters
      }), JSON.stringify({"X-DashScope-SSE": "disable"}));
      const text = finalText(parseTranscript(body), stripPunctuation, stripChars);
      return text ? {final: text, ended: true} : {ended: true};
    }

    await foxy.websocket.send(context.socket, JSON.stringify({
      header: {action: "finish-task", task_id: context.taskId, streaming: "duplex"},
      payload: {input: {}}
    }));
  },
  onMessage(message) {
    const packet = JSON.parse(message.message);
    const event = packet.header && packet.header.event;
    if (event === "task-started") return {ready: true};
    if (event === "task-finished") return {ended: true};
    if (event === "task-failed") return {error: packet.header.error_message || "Qwen ASR failed"};
    if (event !== "result-generated") return {};
    const sentence = packet.payload && packet.payload.output && packet.payload.output.sentence;
    if (!sentence || sentence.heartbeat || !sentence.text) return {};
    return sentence.sentence_end
      ? {final: finalText(sentence.text, state.stripPunctuation, state.stripChars)}
      : {partial: sentence.text};
  }
});
