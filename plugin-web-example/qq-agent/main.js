'use strict';

/**
 * QQ 助手 · Node 侧（跑在 Electron `utilityProcess` 里，P1）
 *
 * <h3>它做什么</h3>
 * 连 NapCat（OneBot 11 over WS）→ 收私聊 → 四层闸门 → 调平台 `/agent/run` → 拟人化发送。
 *
 * <h3>它不做什么（P1 范围）</h3>
 * 群聊、看图、语音、主动发言、改资料都在后续阶段；本文件只保证「私聊 + 白名单 + 人格 + 拟人化」跑通，
 * 因为这几步跑通之前，后面任何能力都没有意义。
 *
 * <h3>配置为什么放文件而不是 manifest.config</h3>
 * 界面插件的 `manifest.config` 值存在**浏览器 localStorage**，而这段代码跑在
 * **Node 子进程**里，读不到 localStorage。所以配置放 `api.dataDir/config.json`（本机文件），
 * 由插件自己的设置面板通过 `methods.saveConfig` 写入。
 */
const fs = require('node:fs');
const path = require('node:path');
const { WebSocket } = require('ws');

// ------------------------------------------------------------------ 状态

let api = null;
let ws = null;
let cfg = null;
let stopping = false;
let reconnectTimer = null;
let reconnectDelay = 1000;

/** 对外的运行状态（前端状态球与 methods.status 都用它）。 */
const stats = {
  connected: false,
  uin: 0,
  nickname: '',
  received: 0,
  replied: 0,
  skipped: 0,
  lastSkipReason: '',
  lastError: '',
  lastAt: 0,
  silenceUntil: 0,
};

/** 全局静默截止时间（毫秒时间戳）—— 这段时间内**不回任何人**（真人也这样）。 */
let silenceUntil = 0;
/** 当前静默期内是否已经抽过值（避免每来一条都重抽）。 */
let silencePrimed = false;

// ------------------------------------------------------------------ P3 状态

/**
 * 自我管理的冷却截止时间（毫秒时间戳）—— **必须落盘**。
 *
 * 不落盘的话每次重启都会重新抽冷却值，于是"刚换过头像又被换一次"；
 * 这种行为模式比"从不换头像"更容易被判定为非真人。
 */
const selfState = {
  /** 各类资料的冷却截止时间（毫秒时间戳）。 */
  avatar: 0,
  signature: 0,
  nickname: 0,
  /** 上次"自我检视"时间 —— 防止模型说"不改"之后每 10 分钟又问一次。 */
  lastCheck: 0,
  /** 上次主动发言时间。 */
  lastProactiveAt: 0,
  /** 当前昵称 / 签名（给模型看"现状"，它才知道要不要改）。 */
  nicknameValue: '',
  signatureValue: '',
  /*
   * 下面三个**必须在声明里出现**：`loadSelfState` 是按 `Object.keys(selfState)`
   * 回填的，动态加上的属性重启后就丢了（冷却会被重置 → 行为变得可疑）。
   */
  /** 上次写日记的时间。 */
  lastDiaryAt: 0,
  /** 上次发说说的时间（F9 冷却）。 */
  lastQzoneAt: 0,
  /** 上次设置的在线状态（只在变化时才调 API）。 */
  onlineStatus: 0,
  };

/** 最近互动过的人：peer → { lastAt, isPrivate }（只存内存，重启后重新积累）。 */
const recentContacts = new Map();

/**
 * 每个群最近的消息（2026-10-21）。
 *
 * <p><b>为什么存全量、而不是只存"通过闸门"的</b>：模型要判断"这句话该不该接"，
 * 就必须知道**群里刚才在聊什么**。只给它一条 @ 消息，它只能瞎猜；
 * 给它上面 8 条，它能看出"他们在互相讨论版本问题"和"他们在叫我"的区别。</p>
 *
 * <p>只存内存：重启后重新积累。存盘没意义 —— 上次的群聊对现在没有参考价值。</p>
 */
const groupBuffer = new Map();
const GROUP_BUFFER_MAX = 20;

/** 取纯文本（不解析引用、不下载图片）—— 缓冲区要快，不能每条都打 API。 */
function quickText(e) {
  const segs = Array.isArray(e.message) ? e.message : [];
  return segs
    .filter((s) => s && s.type === 'text')
    .map((s) => s.data?.text || '')
    .join('')
    .trim();
}

/**
 * 拼「群里刚才在聊」这一段。
 *
 * <p>排除当前这条（它本身会作为 user 消息发出去，重复出现会让模型以为说了两遍）。</p>
 */
function groupContextBlock(gid) {
  const buf = groupBuffer.get(Number(gid)) || [];
  const previous = buf.slice(-9, -1);   // 最近 8 条，不含刚进来的这条
  if (previous.length === 0) {
    return '';
  }
  const lines = previous.map((m) => `${m.sender}：${m.text}`);
  return `【群里刚才在聊】\n${lines.join('\n')}\n\n`;
}

/** 心跳定时器（自我管理 + 主动发言共用）。 */
let heartbeat = null;

const DEFAULTS = {
  // 总开关：默认关。装完不会自动回消息，必须先明确启用。
  enabled: false,
  napcatWs: 'ws://127.0.0.1:3001',
  napcatToken: '',
  selfUin: 0,
  // 用哪个智能体跑（平台里的 agentId）
  agentId: '',
  // 白名单：空数组 = 全部忽略（默认安全）。只有列出的才处理。
  privateWhitelist: [],
  // 回复概率：私聊 0.9（几乎都回，但不是 100%），群聊 0.55（经常不理）
  replyPrivate: 0.9,
  replyGroup: 0.55,
  /**
   * 沉默期开关（2026-10-20 改为显式开关，**默认关闭**）。
   *
   * <p>原来只有"概率 30%"这一道，而 `silencePrimed` 在**每次重启 / 每次保存配置**时
   * 都会重置 —— 调试期频繁重启，几乎每隔几次就会撞上 20~90 分钟不回话。
   * 正式使用时它确实更像真人，但**默认不该是开着的**。</p>
   */
  silenceEnabled: false,
  silenceMinMinutes: 20,
  silenceMaxMinutes: 90,
  // 打字节奏
  firstDelayMs: [1000, 4000],
  charDelayMs: [30, 90],
  segmentGapMs: [800, 2500],
  maxSegments: 3,
  maxSegmentChars: 60,
  typing: true,
  // 每轮请求带的一段额外人格补充（可留空）
  systemPromptPrefix: '',
  // 表情包（2026-10-20，P2）：本地图片目录，回复后按概率附一张
  stickerDir: '',
  stickerChance: 0.25,
  // 语音（2026-10-20，P2）：走 OpenAI 兼容的 /audio/speech 接口
  ttsBaseUrl: '',
  ttsApiKey: '',
  ttsModel: 'tts-1',
  ttsVoice: 'alloy',
  /** 用语音回复的概率（0 = 全文字）。只在回答较短时触发 —— 长文念出来没人听。 */
  voiceChance: 0,
  /** 超过这个字数就不转语音。 */
  voiceMaxChars: 60,
  /** 看图（2026-10-20，P2）：收到图片时交给平台的多模态模型。 */
  visionEnabled: true,
  /**
   * 消息接收窗口期（秒，2026-10-20）。
   *
   * 收到消息后先等这么久；期间再来消息就**重置计时并合并**，直到安静满窗才回复。
   * 真人也这样：对方连发三条，你不会逐条回三次。0 = 关闭。
   */
  debounceSeconds: 3,
  /** 分段用的正则（匹配式，不是分割式）；写错时自动回落到默认值。 */
  segmentRegex: '.*?[。？！~…]+|.+$',
  /**
   * 把 Markdown 转成纯文本（默认开）。
   *
   * QQ 不渲染 Markdown，平台提示词教出来的 `**粗体**` / `- 列表` / `| 表格 |`
   * 在那里全是字面符号。关掉它就原样发（比如你换成了支持 Markdown 的平台）。
   */
  stripMarkdown: true,

  // ---------------- 自我管理（P3，文档 §5.5） ----------------
  /** 总开关：改头像 / 昵称 / 签名。默认关 —— 会真的动账号资料。 */
  selfManageEnabled: false,
  /** 头像池目录（本机图片）：模型给出画面描述后，按关键词在这里找图。 */
  avatarDir: '',
  /**
   * 自我检视间隔（小时）—— 表单里是两个数字，代码里拼成区间随机。
   *
   * 检视 = 问一次模型"要不要改资料"。**必须节流**：模型多数时候会说"不改"，
   * 若每 10 分钟问一次，一天就是 144 次无谓的模型调用。
   */
  selfCheckHoursMin: 2,
  selfCheckHoursMax: 6,
  /** 冷却天数区间（随机取值并落盘，避免"一天改三次"这种异常行为）。 */
  avatarCooldownDays: [7, 30],
  signatureCooldownDays: [3, 14],
  nicknameCooldownDays: [30, 90],

  // ---------------- 主动发言（P3，文档 §9.2） ----------------
  /** 总开关。默认关 —— 开着就像"AI 突然找你聊天"。 */
  proactiveEnabled: false,
  /**
   * 两次主动发言的检查间隔（小时，区间随机）。
   *
   * 参考值来自同类项目实测：45~110 分钟检查一次，而不是 12~48 小时"发一次"。
   * 检查不等于发送 —— 真正会不会发由下面的概率与"安静时长"决定。
   * 固定间隔会让它像闹钟一样准点出现，所以必须随机。
   */
  proactiveGapHours: [0.75, 1.8],
  /** 对方必须安静这么久（分钟）才考虑主动 —— 正在聊天时不要打断。 */
  proactiveQuietMinutes: 15,
  /** 每次检查真正发送的概率（0~1）。0.35 意味着多数检查都会放弃。 */
  proactiveChance: 0.35,
  /** 只对最近这么多小时内互动过的人主动。 */
  proactiveWithinHours: 48,
  /** 主动发言时给模型的引导语。 */
  proactivePrompt: '（现在是你主动发起对话。请像朋友一样，结合我们之前聊过的内容，主动说一句自然的话。不要问我有什么事，也不要解释你为什么发消息。）',
  /**
   * 主动发言前先看一眼「最近有什么值得聊的」（2026-10-21）。
   *
   * 开启后会先调 `bilibili_hot` 拿几条热门，塞进提示词让模型挑一个当话题。
   * **默认关**：每次主动发言都会多一次抓取 + 更长上下文，成本翻倍，
   * 而且"硬找话题"本身就像营销号 —— 只在你想让它更"有生活"时开。
   */
  proactivePeek: false,
  /** 拉取几条（B 站热门用）。 */
  proactivePeekCount: 5,
  /** 日记：让她自己写点什么（3~12 小时随机检查，30% 概率真写）。 */
  diaryEnabled: true,
  /** 印象：定期用一句话概括「这个人是什么样」。 */
  impressionEnabled: true,

  // ---------------- 安全与限流（2026-10-21） ----------------
  /**
   * 风险已确认（N1）。
   *
   * 没勾这个之前**拒绝连接** —— 接管个人号违反 QQ 用户协议、有封号风险，
   * 用户必须先看到这句话并明确同意，不能让它悄悄开始跑。
   */
  riskAcknowledged: false,
  /** 每个会话每分钟最多几次模型调用（N2 成本护栏）。0 = 不限制。 */
  rateLimitPerMinute: 6,
  /** 群内发送人白名单（QQ 号）；为空表示不限制成员。 */
  groupMemberWhitelist: [],

  // ---------------- 说说与状态（2026-10-21） ----------------
  /** 允许发说说（F9）。默认关 —— 一发就是所有人都能看到。 */
  qzoneEnabled: false,
  /** 说说的可见范围：0 = 公开，1 = 仅自己（默认安全）。 */
  qzoneUgcRight: 1,
  /** 两次说说之间至少隔多少小时。 */
  qzoneCooldownHours: 24,
  /** 自动切换在线状态（工作时段在线 / 夜间离开）。默认关。 */
  selfStatusEnabled: false,
};

/** 默认分段正则（外部配置写坏时的兜底）。 */
const DEFAULT_SEGMENT_REGEX = '.*?[。？！~…]+|.+$';

// ------------------------------------------------------------------ 工具

const sleep = (ms) => new Promise((r) => setTimeout(r, Math.max(0, ms)));
const rand = ([a, b]) => a + Math.random() * (b - a);

function log(level, ...args) {
  try {
    api?.logger?.[level]?.(...args);
  } catch {
    /* 日志失败不影响主流程 */
  }
}

/**
 * 落盘诊断（2026-10-20）。
 *
 * <p>为什么需要：插件跑在 `utilityProcess` 里，它的 `logger` 输出经主进程转发，
 * 而**主进程的 console 不一定被重定向进 `app.log`** —— 于是"为什么不回消息"这类问题
 * 在日志里一片空白。`catch` 吞掉异常是必要的（不能因为发一条消息失败就停掉整个插件），
 * 但吞掉之后必须有别的地方能看见。</p>
 */
const DIAG_MAX_BYTES = 200 * 1024;

function diag(line) {
  try {
    const f = path.join(api.dataDir, 'diag.log');
    try {
      if (fs.statSync(f).size > DIAG_MAX_BYTES) {
        fs.writeFileSync(f, '', 'utf8'); // 超限就清空，避免无限增长
      }
    } catch {
      /* 文件还不存在 */
    }
    fs.appendFileSync(f, `${new Date().toISOString()} ${line}\n`, 'utf8');
  } catch {
    /* 磁盘写不进去也不能影响主流程 */
  }
}

// ------------------------------------------------------------------ 配置

function configPath() {
  return path.join(api.dataDir, 'config.json');
}

function loadConfig() {
  const p = configPath();
  if (!fs.existsSync(p)) {
    // 首次启动生成模板：用户照着改，比猜字段名强
    fs.writeFileSync(p, JSON.stringify(DEFAULTS, null, 2), 'utf8');
    log('info', `已生成配置模板：${p}`);
    return { ...DEFAULTS };
  }
  try {
    const raw = JSON.parse(fs.readFileSync(p, 'utf8'));
    return { ...DEFAULTS, ...raw };
  } catch (e) {
    log('warn', `配置解析失败，使用默认值：${e.message}`);
    return { ...DEFAULTS };
  }
}

function saveConfig(patch) {
  // 只接受有值的字段：表单可能传 `undefined`（用户清空了某个数字框），
  // 直接合并会把默认值抹成 undefined，后续 `Number(undefined)` 全是 NaN。
  /*
   * 两道过滤：
   * ① 丢掉 `undefined` —— 表单里清空数字框会传 undefined，直接合并会把默认值抹掉；
   * ② 只接受 DEFAULTS 里声明过的 key —— 防止别的调用方把 `ok` / `data` 这类
   *    无关字段顺手写进配置文件（实际发生过，config.json 里长出了 `ok` 和 `data`）。
   */
  const clean = {};
  for (const [k, v] of Object.entries(patch || {})) {
    if (v !== undefined && Object.prototype.hasOwnProperty.call(DEFAULTS, k)) {
      clean[k] = v;
    }
  }
  cfg = { ...cfg, ...clean };
  fs.writeFileSync(configPath(), JSON.stringify(cfg, null, 2), 'utf8');
  return cfg;
}

// ------------------------------------------------------------------ NapCat（OneBot 11）

let seq = 0;
const pending = new Map();

function connect() {
  if (stopping || !cfg || !cfg.enabled) return;
  /*
   * 风险确认（N1）：没勾选就**拒绝连接**。
   *
   * 接管个人号违反 QQ 用户协议、有封号风险。这件事必须用户**明确同意**，
   * 不能因为它"已经填了 QQ 号"就默认开始跑。
   */
  if (!cfg.riskAcknowledged) {
    log('warn', '未确认账号风险，拒绝连接：请到插件设置勾选「我已了解风险」');
    diag('refuse: risk not acknowledged');
    return;
  }
  const url = cfg.napcatToken
    ? `${cfg.napcatWs}?access_token=${encodeURIComponent(cfg.napcatToken)}`
    : cfg.napcatWs;

  log('info', `连接 NapCat：${url}`);
  try {
    ws = new WebSocket(url);
  } catch (e) {
    stats.lastError = e.message;
    scheduleReconnect();
    return;
  }

  ws.on('open', () => {
    stats.connected = true;
    stats.lastError = '';
    reconnectDelay = 1000;
    log('info', 'NapCat 已连接');
    void call('get_login_info', {}, 8000)
      .then((info) => {
        stats.uin = Number(info?.user_id || 0);
        stats.nickname = String(info?.nickname || '');
        log('info', `登录账号：${stats.uin} ${stats.nickname}`);
      })
      .catch(() => {});
  });

  ws.on('message', (raw) => {
    let d;
    try {
      d = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (d.echo && pending.has(d.echo)) {
      const { resolve, reject } = pending.get(d.echo);
      pending.delete(d.echo);
      if (d.status === 'ok' || d.retcode === 0) resolve(d.data);
      else reject(new Error(`retcode=${d.retcode} ${d.wording || d.message || ''}`));
      return;
    }
    if (d.post_type === 'message') {
      void onMessage(d);
    }
  });

  ws.on('error', (e) => {
    stats.lastError = e.message || String(e);
    log('warn', `WS 错误：${stats.lastError}`);
  });

  ws.on('close', () => {
    stats.connected = false;
    if (!stopping) scheduleReconnect();
  });
}

function scheduleReconnect() {
  if (stopping || !cfg || !cfg.enabled) return;
  if (reconnectTimer) return;
  const delay = reconnectDelay;
  reconnectDelay = Math.min(reconnectDelay * 2, 60_000);
  log('info', `${delay}ms 后重连`);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, delay);
}

function call(action, params = {}, timeoutMs = 30_000) {
  return new Promise((resolve, reject) => {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      reject(new Error('WS 未连接'));
      return;
    }
    const echo = `q${++seq}`;
    pending.set(echo, { resolve, reject });
    ws.send(JSON.stringify({ action, params, echo }));
    setTimeout(() => {
      if (pending.has(echo)) {
        pending.delete(echo);
        reject(new Error(`调用超时：${action}`));
      }
    }, timeoutMs);
  });
}

// ------------------------------------------------------------------ 闸门

function parseIds(v) {
  if (Array.isArray(v)) return v.map(Number).filter((n) => Number.isFinite(n) && n > 0);
  return String(v || '')
    .split(/[,，\s]+/)
    .map((s) => Number(s.trim()))
    .filter((n) => Number.isFinite(n) && n > 0);
}

/** 返回 null = 放行；返回字符串 = 拦截原因（写日志用）。 */
function gate(e) {
  if (!cfg.enabled) return '总开关未启用';

  if (e.message_type === 'private') {
    const wl = parseIds(cfg.privateWhitelist);
    if (!wl.includes(Number(e.user_id))) return `不在私聊白名单（${e.user_id}）`;
    return null;
  }

  // 群聊：P1 只认「被 @」，且群必须在白名单里
  if (e.message_type === 'group') {
    const gw = parseIds(cfg.groupWhitelist);
    if (!gw.includes(Number(e.group_id))) return `群不在白名单（${e.group_id}）`;
    if (!e.atme && !e.at_me) return '群聊里没有 @ 我';
    /*
     * 群成员白名单（2026-10-21）：为空表示"群内不限制发送人"。
     * 它挡的是"群对了但人不对"——比如工作群里只想回应同事，不理会客户。
     */
    const members = parseIds(cfg.groupMemberWhitelist);
    if (members.length > 0 && !members.includes(Number(e.user_id))) {
      return `发送者不在群成员白名单（${e.user_id}）`;
    }
    return null;
  }

  return '未知消息类型';
}

// ------------------------------------------------------------------ 行为模型

function currentSilenceWindow() {
  return { min: Number(cfg.silenceMinMinutes), max: Number(cfg.silenceMaxMinutes) };
}

/** 是否处于静默期；不在则按概率决定要不要开启一段新静默（未启用时永远返回 false）。 */
function inSilence() {
  if (!cfg.silenceEnabled) {
    return false;
  }
  const now = Date.now();
  if (now < silenceUntil) return true;
  if (!silencePrimed) {
    silencePrimed = true;
    // 每轮静默结束后，30% 概率进入下一段（不是每次必进，否则太规律）
    if (Math.random() < 0.3) {
      const { min, max } = currentSilenceWindow();
      const mins = min + Math.random() * Math.max(0, max - min);
      silenceUntil = now + mins * 60_000;
      stats.silenceUntil = silenceUntil;
      log('info', `进入静默 ${mins.toFixed(0)} 分钟（期间不回任何人）`);
      return true;
    }
  }
  return false;
}

/**
 * 按**可配置的正则**切分，模拟真人分多条发送（2026-10-20 改为配置项）。
 *
 * <p>正则语义是「**匹配一个句子**」而不是「在分隔符处分割」—— 默认值
 * `.*?[。？！~…]+|.+$` 会把标点留在句尾，这正是聊天里自然的样子。
 * 正则写坏时回落到默认值：**分段难看总比整条发不出去好**。</p>
 */
function splitText(text, maxSeg, maxChars) {
  const clean = String(text || '').trim();
  if (!clean) return [];

  const src = (cfg && cfg.segmentRegex) || DEFAULT_SEGMENT_REGEX;
  let parts = [];
  try {
    /*
     * ★ 必须补 `m` 标志（2026-10-21 定位到的大坑）。
     *
     * 用户给的默认正则 `.*?[。？！~…]+|.+$` 里，`$` 在**没有 m 标志时只匹配字符串末尾**，
     * 而 `.` 又不匹配换行 —— 于是 `. +$` **只能匹配到最后一行**。
     * 模型输出「1+1 = 2\n2+2 = 4」（没有句号，第一个分支不命中）时，
     * 分段结果就只有 `2+2 = 4`，**第一行被静默丢弃**。
     *
     * 表现极具欺骗性：模型明明答了两道，QQ 上只收到一道，看起来像"模型只答了最后一题"，
     * 于是前面几轮我一直在改合并格式 —— 方向全错。
     *
     * `g` 同理：matchAll 要求全局，用户写正则时未必会带。
     */
    const flags = (src.includes('g') ? '' : 'g') + (src.includes('m') ? '' : 'm');
    const re = new RegExp(src, flags);
    for (const m of clean.matchAll(re)) {
      const s = (m[0] || '').trim();
      if (s) parts.push(s);
    }
  } catch (e) {
    diag(`segmentRegex invalid (${src}): ${e.message}, fallback`);
    parts = [];
  }
  if (parts.length === 0) {
    // 兜底：按常见标点切，保证一定有内容
    parts = clean.split(/(?<=[。！？!?…~～])/).map((s) => s.trim()).filter(Boolean);
  }

  const segs = [];
  for (const p of parts) {
    if (p.length <= maxChars) {
      segs.push(p);
    } else {
      for (let i = 0; i < p.length; i += maxChars) {
        segs.push(p.slice(i, i + maxChars));
      }
    }
  }
  return segs.slice(0, Math.max(1, maxSeg));
}

/**
 * 时段系数（2026-10-21）：深夜和午休打字更慢。
 *
 * <p>没有这一条，凌晨三点秒回是最容易被判定为机器人的特征 ——
 * 真人夜里打字本来就慢，而且常常打一半停下。</p>
 */
function paceFactor() {
  const h = new Date().getHours();
  if (h >= 23 || h < 7) {
    return 1.8;
  }
  if (h >= 12 && h < 14) {
    return 1.3;
  }
  return 1;
}

/**
 * 当前时间上下文（2026-10-21）。
 *
 * <p>模型的训练数据里没有"现在几点"，也不记得上一次对话发生在多久以前。
 * 不告诉它这些，它永远说不出「你今天起这么早」（因为平时你中午才回）
 * 这种真正像人的话。</p>
 *
 * @param peer 联系人 QQ（用来看"距上次聊天多久"）；无则只给时间
 */
function timeContext(peer, isGroup) {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][now.getDay()];
  const hh = now.getHours();
  // 时段词比裸数字更有用：模型能据此调整语气（凌晨别太热情）
  const period = hh < 6 ? '凌晨' : hh < 9 ? '早上' : hh < 12 ? '上午'
    : hh < 14 ? '中午' : hh < 18 ? '下午' : hh < 23 ? '晚上' : '深夜';

  const lines = [
    `【现在】${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} `
      + `${pad(hh)}:${pad(now.getMinutes())} ${week}（${period}）`,
  ];

  const rec = peer ? recentContacts.get(Number(peer)) : null;
  /*
   * 用 `prevAt`（**上一条消息之前的**时间），不是 `lastAt` ——
   * 后者在收到本条消息时刚被更新，永远是"0 分钟前"，等于没信息。
   */
  const prev = rec && rec.prevAt ? rec.prevAt : 0;
  if (prev > 0) {
    const mins = Math.floor((Date.now() - prev) / 60000);
    if (mins < 1) {
      lines.push('【距上次聊天】刚刚');
    } else if (mins < 60) {
      lines.push(`【距上次聊天】${mins} 分钟前`);
    } else if (mins < 1440) {
      lines.push(`【距上次聊天】${Math.floor(mins / 60)} 小时前`);
    } else {
      lines.push(`【距上次聊天】${Math.floor(mins / 1440)} 天前`);
    }
  }
  /*
   * 关于这个人的印象（2026-10-21）：
   * 事实记忆（他养了只猫）交给平台的四层记忆；这里给的是一句**判断**，
   * 让它的态度有连续性 —— 否则每次对话都是"第一次见你"。
   */
  const imp = peer ? impressions[String(peer)] : null;
  if (imp && imp.text) {
    lines.push(`【你对他/她的印象】${imp.text}`);
  }

  /* 最近写的日记：只带 2 条，多了会挤占上下文 */
  const diary = readDiary(2);
  if (diary.length > 0) {
    lines.push('【你最近写的日记】');
    for (const d of diary) {
      lines.push(`  ${d.time}${d.mood ? '（' + d.mood + '）' : ''} ${d.text}`);
    }
  }

  /* 她自己的相册（只有收藏过的才有） */
  const gallery = readGallery(2);
  if (gallery.length > 0) {
    lines.push(`【你的相册】${gallery.map((g) => g.reason || g.file).join('、')}`);
  }

  /*
   * 两个"可选动作"的提示（2026-10-21）。
   *
   * 必须写在上下文里才有效 —— 插件的系统提示词由平台智能体配置，插件改不了。
   * 每条都短，不然它会把提示当正文回出来。
   */
  /*
   * [SILENT] 只在群聊提示：私聊里对方就是冲你来的，
   * 提示它「不想接就别回」会让它变得爱搭不理。
   */
  lines.push(isGroup
    ? '【可选动作】不想接这句话就只回 [SILENT]（不会发出任何内容）；看到喜欢的图，在回复末尾加 [收藏 一句理由]。'
    : '【可选动作】看到喜欢的图想留着，在回复末尾加 [收藏 一句理由]。');

  return lines.join('\n') + '\n\n';
}

// ------------------------------------------------------------------ 限流 / 说说 / 发图（2026-10-21）

/** 每个会话的调用时间戳（滑窗用）。 */
const rateBuckets = new Map();

/**
 * 每分钟调用上限（N2 成本护栏）。
 *
 * <p>为什么按会话而不是全局：某个人刷屏不该把别人的正常对话饿死；
 * 但全局上限也需要 —— 见 {@link allowGlobalRate}。</p>
 */
function allowByRate(key, maxPerMinute) {
  if (!maxPerMinute || maxPerMinute <= 0) {
    return true;
  }
  const now = Date.now();
  const arr = (rateBuckets.get(key) || []).filter((t) => now - t < 60000);
  if (arr.length >= maxPerMinute) {
    rateBuckets.set(key, arr);
    return false;
  }
  arr.push(now);
  rateBuckets.set(key, arr);
  return true;
}

/**
 * 从回复里抽 `[说说 内容]`（F9）。
 *
 * <p>可见范围由 `qzoneUgcRight` 决定，**默认 1（仅自己可见）**。
 * 默认公开是不负责任的 —— 用户第一次开这个开关时，不该冒出几条所有人都能看到的动态。</p>
 */
function extractQzone(text) {
  const raw = String(text || '');
  const m = raw.match(/\[说说[：:\s]*([^\]]{1,300})\]/);
  if (!m) {
    return { text: raw, content: null };
  }
  return { text: raw.replace(m[0], '').trim(), content: m[1].trim() };
}

/** 从回复里抽 `[发图 关键词]`（她发自己相册里的图）。 */
function extractSendImage(text) {
  const raw = String(text || '');
  const m = raw.match(/\[发图[：:\s]*([^\]]{0,30})\]/);
  if (!m) {
    return { text: raw, keyword: null };
  }
  return { text: raw.replace(m[0], '').trim(), keyword: (m[1] || '').trim() };
}

/** 在相册里找一张（关键词匹配"理由"；没关键词就随机）。 */
function pickGalleryImage(keyword) {
  const index = readGallery(200);
  if (index.length === 0) {
    return null;
  }
  const pool = keyword
    ? index.filter((g) => String(g.reason || '').includes(keyword))
    : index;
  if (pool.length === 0) {
    return null;
  }
  const pick = pool[Math.floor(Math.random() * pool.length)];
  try {
    return fs.readFileSync(path.join(galleryDir(), pick.file)).toString('base64');
  } catch (e) {
    diag(`gallery read failed: ${e.message}`);
    return null;
  }
}

/**
 * 发一条 QQ 空间动态（F9）。
 *
 * <p>两道闸：`qzoneEnabled` 开关 + `qzoneCooldownHours` 冷却。
 * 可见范围默认 `ugc_right=1`（仅自己可见）—— 第一次开这个功能时，
 * 不该冒出几条所有人都能看到的动态。想公开就去设置里改成 0。</p>
 */
async function tryPostQzone(content) {
  if (!cfg.qzoneEnabled) {
    diag('qzone: 未启用（qzoneEnabled=false），跳过');
    return;
  }
  const now = Date.now();
  const cooldown = (Number(cfg.qzoneCooldownHours) || 24) * 3600000;
  if (now - (selfState.lastQzoneAt || 0) < cooldown) {
    const left = Math.ceil((cooldown - (now - (selfState.lastQzoneAt || 0))) / 3600000);
    diag(`qzone: 冷却中，还需 ${left} 小时`);
    return;
  }
  try {
    const r = await call('send_qzone_msg', {
      content,
      images: [],
      ugc_right: Number(cfg.qzoneUgcRight) === 0 ? 0 : 1,
      target_uins: [],
    }, 30000);
    selfState.lastQzoneAt = now;
    saveSelfState();
    diag(`qzone: 已发布（${Number(cfg.qzoneUgcRight) === 0 ? '公开' : '仅自己'}）tid=${r && r.tid ? r.tid : '-'}`);
  } catch (e) {
    diag(`qzone failed: ${e.message}`);
  }
}

/** 从相册发一张图（图库里存的 base64）。 */
async function sendImageMsg(isPrivate, peer, base64) {
  try {
    const message = [{ type: 'image', data: { file: 'base64://' + base64 } }];
    if (isPrivate) {
      await call('send_private_msg', { user_id: peer, message });
    } else {
      await call('send_group_msg', { group_id: peer, message });
    }
    diag(`gallery image sent -> ${peer}`);
  } catch (e) {
    diag(`gallery image send failed: ${e.message}`);
  }
}

/**
 * 自动切换在线状态（2026-10-21）。
 *
 * <p>只在**状态真的变化**时调 API —— 频繁切换在线状态本身就是异常行为特征。
 * 白天（8~23 点）在线，其余时间「离开」，与真人的作息接近。</p>
 */
async function maybeUpdateOnlineStatus() {
  if (!cfg.selfStatusEnabled || !stats.connected) {
    return;
  }
  const hour = new Date().getHours();
  const target = (hour >= 8 && hour < 23) ? 10 : 30;   // 10=在线，30=离开
  if (selfState.onlineStatus === target) {
    return;
  }
  try {
    await call('set_online_status', { status: target, ext_status: 0 }, 10000);
    selfState.onlineStatus = target;
    saveSelfState();
    diag(`online status -> ${target === 10 ? '在线' : '离开'}`);
  } catch (e) {
    diag(`online status failed: ${e.message}`);
  }
}

/** 回复是否表示"这次不接话"。 */
function isSilent(text) {
  const t = String(text || '').trim();
  if (!t) {
    return true;
  }
  return /\[(SILENT|静默|不接话)\]/i.test(t) && t.replace(/\[(SILENT|静默|不接话)\]/gi, '').trim().length === 0;
}

/** 打字耗时：字数 × 每字延迟 + 标点停顿 + 抖动。 */
function typingMs(text) {
  const base = text.length * rand(cfg.charDelayMs);
  const punct = (text.match(/[。！？!?…]/g) || []).length * rand([200, 600]);
  // 深夜/午休整体放慢（见 paceFactor）
  return (base + punct) * paceFactor() + rand([-300, 500]);
}

// ------------------------------------------------------------------ 调平台

/**
 * 从 OneBot 消息段里取出文字与图片（2026-10-20，P2 视觉；2026-10-21 加发言人/引用）。
 *
 * <p>`raw_message` 是 CQ 码拼的字符串，图在里面的样子随适配器版本而变；
 * 结构化读 `message[]` 才是稳的 —— 每段都有自己的 `type` 与 `data`。</p>
 *
 * <h3>群聊为什么要标发言人</h3>
 * 群消息全塞进同一个 `sessionId`，不标的话模型分不清"这句是谁说的"，
 * 会把 A 的私事当众回给 B。加了 `[昵称]` 前缀后它才有对话对象的概念。
 *
 * <h3>引用为什么要解析</h3>
 * 群里常见的场景是"引用别人的话再评论"。不解析的话模型会把这句当成
 * **直接对它说的**，于是对着一段引文自说自话。解析成 `[引用 某某：原文]`
 * 之后，它才判断得出"这句话其实不是冲我来的"。
 */
async function extractContent(e) {
  const segs = Array.isArray(e.message) ? e.message : [];
  const texts = [];
  const images = [];
  let quotedId = '';

  for (const s of segs) {
    if (!s || typeof s !== 'object') {
      continue;
    }
    const d = s.data || {};
    if (s.type === 'text') {
      texts.push(d.text || '');
    } else if (s.type === 'image') {
      // url 是 QQ 图床地址（跨部署可用）；file 往往是 NapCat 本机缓存路径
      images.push({ url: d.url || '', file: d.file || '' });
    } else if (s.type === 'face') {
      texts.push('[表情]');
    } else if (s.type === 'record') {
      texts.push('[语音]');
    } else if (s.type === 'at') {
      texts.push(`@${d.qq === 'all' ? '全体成员' : d.qq}`);
    } else if (s.type === 'reply') {
      quotedId = String(d.id || '');
    }
  }

  let prefix = '';
  if (e.message_type === 'group' && e.sender) {
    // card 是群名片（有人会改），nickname 是 QQ 昵称，都没有才回退到号
    const name = e.sender.card || e.sender.nickname || String(e.sender.user_id || '');
    if (name) {
      prefix += `[${name}] `;
    }
  }
  if (quotedId) {
    const quoted = await fetchQuoted(quotedId);
    if (quoted) {
      prefix += `[引用 ${quoted.name}：${quoted.text}] `;
    }
  }

  return { text: (prefix + texts.join('').trim()).trim(), images };
}

/** 按 message_id 取被引用的那条（失败不影响主流程，只是少一段上下文）。 */
async function fetchQuoted(messageId) {
  try {
    const msg = await call('get_msg', { message_id: messageId }, 5000);
    const sender = msg?.sender?.card || msg?.sender?.nickname || '某人';
    const raw = String(msg?.raw_message || msg?.message || '').replace(/\s+/g, ' ').trim();
    if (!raw) {
      return null;
    }
    return { name: sender, text: raw.length > 60 ? raw.slice(0, 60) + '…' : raw };
  } catch (e) {
    diag(`get_msg failed (id=${messageId}): ${e.message}`);
    return null;
  }
}

/** 把一张图取成 base64（优先 URL，其次本机路径）。 */
async function fetchImageBase64(img) {
  const src = img.url || img.file;
  if (!src) {
    return null;
  }
  try {
    if (/^https?:\/\//i.test(src)) {
      const res = await fetch(src);
      if (!res.ok) {
        diag(`image http ${res.status}`);
        return null;
      }
      const buf = Buffer.from(await res.arrayBuffer());
      return { base64: buf.toString('base64'), mime: res.headers.get('content-type') || 'image/png' };
    }
    // NapCat 与本插件同机时才读得到（Docker 部署时 file 是容器内路径）
    if (fs.existsSync(src)) {
      return { base64: fs.readFileSync(src).toString('base64'), mime: 'image/png' };
    }
    diag(`image src not reachable: ${src.slice(0, 80)}`);
  } catch (e) {
    diag(`image fetch failed: ${e.message}`);
  }
  return null;
}

/** 上传 base64 到平台，换回 `fileId`（多模态消息只认它）。 */
async function uploadImage(b64, mime) {
  const r = await api.platform.request({
    path: '/api/v1/files/base64',
    method: 'POST',
    body: {
      fileName: `qq-${Date.now()}.png`,
      mimeType: mime,
      dataBase64: b64,
      type: 'image',
    },
  });
  const id = r && (r.fileId || (r.data && r.data.fileId));
  return id || null;
}

/**
 * Markdown → 纯文本（2026-10-20）。
 *
 * <p>平台的系统提示词会教模型用 Markdown（网页对话能渲染），但 **QQ 是纯文本客户端** ——
 * `**粗体**`、`- 列表`、`| 表格 |`、`` `代码` `` 全都变成字面符号，
 * 看起来就是"回了一堆乱七八糟的符号"。</p>
 *
 * <p>保留内容、去掉标记；列表项换成 `·` 以免几行粘成一坨。
 * 代码块只去围栏、不动内容（代码里的 `*` 和 `-` 是真实字符）。</p>
 */
function stripMarkdown(input) {
  let s = String(input || '');

  // 代码块：去掉围栏，保留内容（必须在其它星号/下划线规则之前处理）
  s = s.replace(/```[a-zA-Z0-9_-]*\n?([\s\S]*?)```/g, '$1');
  // 行内代码
  s = s.replace(/`([^`\n]+)`/g, '$1');

  // 标题 / 引用 / 分隔线
  s = s.replace(/^\s{0,3}#{1,6}\s+/gm, '');
  s = s.replace(/^\s{0,3}>\s?/gm, '');
  s = s.replace(/^\s{0,3}([-*_])\1{2,}\s*$/gm, '');

  // 强调
  s = s.replace(/\*\*([^*\n]+)\*\*/g, '$1');
  s = s.replace(/__([^_\n]+)__/g, '$1');
  s = s.replace(/\*([^*\n]+)\*/g, '$1');
  s = s.replace(/(^|[^A-Za-z0-9_])_([^_\n]+)_/g, '$1$2');
  s = s.replace(/~~([^~\n]+)~~/g, '$1');

  // 链接 / 图片：保留文字与地址
  s = s.replace(/!?\[([^\]]*)\]\(([^)\s]+)[^)]*\)/g, (_m, label, url) => (label ? `${label} ${url}` : url));

  /*
   * 列表：**直接删前缀，不做替换**（2026-10-20 修正）。
   *
   * 之前把 `- ` 换成 `· ` 是个错误 —— 用户看到的"一个符号"就是它：
   * 模型输出 `- 2+2=4`，我给它换成了 `· 2+2=4`，等于凭空加了 QQ 里本没有的符号。
   * 删掉前缀后换行仍在，多行内容依旧分行显示。
   */
  s = s.replace(/^\s{0,3}[-*+]\s+/gm, '');
  s = s.replace(/^\s{0,3}\d+[.)]\s+/gm, '');

  // 表格：去掉分隔行，单元格之间的竖线换成空格
  s = s.replace(/^\s*\|?[\s:|-]+\|[\s:|-]*$/gm, '');
  /*
   * ⚠️ 这里只能用 `[ \t]` 不能用 `\s`：`\s` 包含换行，
   * 会把 `| a |\n| b |` 里的换行一起吃掉，整张表被压成一行（实测过）。
   */
  s = s.replace(/[ \t]*\|[ \t]*/g, '  ');

  // 收尾：多余空行压掉，行尾空白清掉
  s = s.replace(/[ \t]+$/gm, '');
  s = s.replace(/\n{3,}/g, '\n\n');

  return s.trim();
}

async function askPlatform(sessionId, text, imageIds, peer) {
  /*
   * 时间上下文（2026-10-21）：告诉模型"现在几点、距上次聊天多久"。
   * 拼在用户消息最前面而不是系统提示词 —— 平台的人格/系统提示词属于智能体配置，
   * 插件改不了，也不该改。
   */
  const isGroup = String(sessionId).includes(':group:');
  /*
   * 群聊时把"刚才群里在聊什么"一起给它（2026-10-21，方案 B）。
   *
   * 这是"自主"最便宜的实现：不改平台、不开端口、不做跨进程 RPC ——
   * 只是把模型需要判断的上下文补齐。配合 [SILENT]，它就能"看到但不接话"。
   */
  const groupBlock = isGroup ? groupContextBlock(peer) : '';
  const timePrefix = groupBlock + timeContext(peer, isGroup);

  /*
   * 多模态消息：有图就用 `parts[]`（后端 `Message.content` 是 Object，两种形态都收）。
   * 纯文字仍传字符串 —— 少一层数组，历史上一直这么传，没必要改。
   */
  const content = imageIds && imageIds.length > 0
    ? [
        { type: 'text', text: timePrefix + (text || '（发来一张图片）') },
        ...imageIds.map((id) => ({ type: 'image', fileId: id })),
      ]
    : (timePrefix + text);

  const body = {
    agentId: cfg.agentId,
    sessionId,
    mode: 'agent',
    messages: [{ role: 'user', content }],
    metadata: { tenant_id: 'default', user_id: `qq-${stats.uin}` },
    /*
     * ★ 让模型能用平台的全部工具（2026-10-21）。
     *
     * `enabled: true` 才会启用 function calling；`allowed: []` 表示不限制（全部可用）。
     * 不开这个，模型手里一个工具都没有 —— 联网搜索（web_search）、图片生成（image_gen）
     * 全都调不到，表现就是"bot 什么都要我告诉它"。
     *
     * ⚠️ 权限：工具执行走的是**登录用户**的身份，能看到哪些工具、能调哪些，
     * 与网页对话里完全一致 —— 插件没有额外越权。
     */
    tools: { enabled: true, allowed: [] },
    /*
     * ★ 纯文本渠道：**不要注入「输出格式」指引**（2026-10-20）。
     *
     * 平台默认会往系统提示词尾部拼一段"回复会按 Markdown 渲染，请用标题/列表/表格"，
     * 那是给网页对话用的。QQ 不渲染 Markdown，模型照做就成了 `**粗体**`、`- 列表`、
     * `| 表格 |` 一堆符号。
     *
     * 传 false 后**模型直接输出纯文本**，从源头不产生符号 ——
     * 比在下游用正则擦标记干净得多（擦掉符号的同时，表格结构也毁了）。
     * `stripMarkdown` 保留作为兜底（模型不听话时仍会漏几个）。
     */
    renderGuide: false,
  };
  const resp = await api.platform.request({
    path: '/api/v1/agent/run',
    method: 'POST',
    body,
  });
  // /agent/run 返回裸 JSON；不同版本可能包一层 data，两种都兼容
  const out = resp?.output ?? resp?.data?.output;
  /*
   * 记录本轮的工具调用（2026-10-21）：没有这条，"模型到底搜没搜"只能靠看回答猜。
   * `tools.enabled=true` 之后，模型可能先调 web_search / image_gen 再回答。
   */
  const calls = resp?.toolCalls ?? resp?.data?.toolCalls;
  if (Array.isArray(calls) && calls.length > 0) {
    diag(`toolCalls(${calls.length}): ${calls.map((c) => c.name || c.tool || c.toolName || '?').join(', ')}`);
  }
  const raw = (out?.content || '').trim();
  // QQ 显示不了 Markdown：转成纯文本再发（可关闭，见 stripMarkdown 配置）
  return cfg.stripMarkdown === false ? raw : stripMarkdown(raw);
}

// ------------------------------------------------------------------ 主流程

/**
 * 消息去重（2026-10-20）。
 *
 * <p>NapCat 若同时开了 HTTP 与 WS 上报（或重复建了连接），**同一条消息会到达两次** ——
 * 表现是机器人对一句话回两遍。按 `message_id` 记最近 200 条，重复的直接丢弃。
 * 用 Set 的插入顺序当 LRU：超限删最早那个即可。</p>
 */
const recentIds = new Set();
const RECENT_MAX = 200;

function isDuplicate(id) {
  if (id === undefined || id === null || id === '') {
    return false; // 没有 id 就无从判断，交给下游处理
  }
  const key = String(id);
  if (recentIds.has(key)) {
    return true;
  }
  recentIds.add(key);
  if (recentIds.size > RECENT_MAX) {
    recentIds.delete(recentIds.values().next().value);
  }
  return false;
}

/** 会话键（私聊按人、群聊按群）—— 窗口期与消息合并都按它分桶。 */
function sessionKeyOf(e) {
  return e.message_type === 'private' ? `p:${e.user_id}` : `g:${e.group_id}`;
}

/**
 * 窗口期桶：key → { timer, items: [{ text, images }], sample }
 *
 * 名字不能叫 `pending` —— 文件上方已有同名的 Map（存 OneBot 的 action 响应回调），
 * 重名会让整个模块 `SyntaxError: Identifier 'pending' has already been declared`，
 * 表现是"插件 main.js 未加载"。**改完必须 `node --check` 一遍**。
 */
const debounceBuckets = new Map();

async function onMessage(e) {
  if (isDuplicate(e.message_id)) {
    diag(`dup message_id=${e.message_id}（同一消息被上报多次，已忽略）`);
    return;
  }
  stats.received++;
  stats.lastAt = Date.now();
  diag(`recv type=${e.message_type} from=${e.user_id} group=${e.group_id || '-'} atme=${!!e.atme} text=${String(e.raw_message || '').slice(0, 60)}`);

  /*
   * 群聊上下文缓存（2026-10-21）：**在闸门之前**记下来。
   * 被闸门拦掉的消息（没 @ 我、不在白名单）同样是上下文的一部分 ——
   * 正因为我"看到了但没说话"，等真正被 @ 时才知道前因后果。
   */
  if (e.message_type === 'group') {
    const gid = Number(e.group_id);
    const line = quickText(e);
    if (line) {
      const name = (e.sender && (e.sender.card || e.sender.nickname)) || String(e.user_id || '');
      const buf = groupBuffer.get(gid) || [];
      buf.push({ sender: name, text: line.slice(0, 80), at: Date.now() });
      if (buf.length > GROUP_BUFFER_MAX) {
        buf.shift();
      }
      groupBuffer.set(gid, buf);
    }
  }

  const reason = gate(e);
  if (reason) {
    stats.skipped++;
    stats.lastSkipReason = reason;
    log('info', `跳过：${reason}`);
    diag(`skip: ${reason}`);
    return;
  }

  const { text, images } = await extractContent(e);   // 现在是 async（要查被引用的消息）
  if (!text && images.length === 0) {
    stats.skipped++;
    stats.lastSkipReason = '空消息';
    diag('skip: empty content');
    return;
  }

  /*
   * 记录「最近互动过的人」—— 主动发言只从这里挑，不主动找陌生人。
   *
   * 同时保留 `prevAt`（**这一条之前的**互动时间）：时间上下文里的"距上次聊天多久"
   * 必须用它，否则 `lastAt` 刚被本条消息刷新，永远是"刚刚"。
   */
  if (e.message_type === 'private') {
    const uid = Number(e.user_id);
    const prev = recentContacts.get(uid);
    recentContacts.set(uid, {
      lastAt: Date.now(),
      prevAt: prev ? prev.lastAt : 0,
      // 累计消息数：印象更新的触发条件之一（聊够 20 句才有总结的意义）
      count: (prev && prev.count ? prev.count : 0) + 1,
      isPrivate: true,
    });
  }

  enqueue(e, text, images);
}

/**
 * 进窗口期（2026-10-20）。
 *
 * <p>每收到一条就**重置计时**并把内容累积起来；安静满 `debounceSeconds` 才真正处理。
 * 对方连发三条时只回一次、且用的是**合并后的上下文** —— 逐条回三次是最典型的机器人行为。</p>
 */
function enqueue(e, text, images) {
  const key = sessionKeyOf(e);
  let bucket = debounceBuckets.get(key);
  if (!bucket) {
    bucket = { timer: null, items: [], sample: e };
    debounceBuckets.set(key, bucket);
  }
  bucket.items.push({ text, images });

  if (bucket.timer) {
    clearTimeout(bucket.timer);
    bucket.timer = null;
  }
  const wait = Math.max(0, Number(cfg.debounceSeconds || 0) * 1000);
  if (wait === 0) {
    void flush(key);
    return;
  }
  bucket.timer = setTimeout(() => void flush(key), wait);
  diag(`debounce ${wait}ms，已累积 ${bucket.items.length} 条`);
}

/** 窗口期到点：合并这一窗的所有消息，只处理一次。 */
async function flush(key) {
  const bucket = debounceBuckets.get(key);
  if (!bucket) {
    return;
  }
  debounceBuckets.delete(key);
  if (bucket.timer) {
    clearTimeout(bucket.timer);
  }
  const texts = bucket.items.map((i) => i.text).filter(Boolean);
  const images = bucket.items.flatMap((i) => i.images);
  if (texts.length > 1) {
    diag(`merged ${texts.length} messages: ${texts.join(' | ').slice(0, 100)}`);
  }
  /*
   * 合并后的呈现方式（2026-10-20 修正）。
   *
   * 直接用 `\n` 拼起来是不行的：实测发「你分别计算一下 / 1+1 / 2+2 / 给我答案」四条，
   * 模型只回答了最后一个 `2+2` —— 它把后面几行当成了同一段话的补充说明。
   * 加编号 + 一句括号说明，模型才会逐条对待。
   */
  /*
   * 合并成**一句话**（2026-10-21，用户建议）。
   *
   * 前三版（换行 / 编号 / `---` 分隔）都出现"只答最后一条"。差别可能不在"一条还是多条消息"
   * ——`messages` 一直只有一条 —— 而在**内容是多行**：多行更容易被模型当成"多个独立段落"，
   * 于是只处理最后一段。
   *
   * 这里把几条消息用中文逗号连成**一句话**（上一条已经以标点结尾就不重复补），
   * 模型看到的是一个完整请求，而不是若干段落。
   */
  const merged = joinAsOneSentence(texts);
  await processMessage(bucket.sample, merged, images);
}

/**
 * 把连续几条消息拼成**一句话**（2026-10-21）。
 *
 * <p>上一条已经以标点结尾就直接接上，否则补一个中文逗号 —— 避免出现 `1+1，给我答案`
 * 这种把两条独立句子硬粘起来的怪句子。空项（纯图片消息）会被跳过。</p>
 */
function joinAsOneSentence(texts) {
  let out = '';
  for (const t of texts) {
    const s = String(t || '').trim();
    if (!s) {
      continue;
    }
    if (!out) {
      out = s;
    } else if (/[。！？!?…~～,，、；;：:]$/.test(out)) {
      out += s;
    } else {
      out += '，' + s;
    }
  }
  return out;
}

/** 真正处理一轮（静默判断 / 概率 / 图片上传 / 调平台 / 发送）。 */
async function processMessage(e, text, images) {
  /*
   * 成本护栏（N2）：每个会话每分钟最多 N 次模型调用。
   *
   * 按会话而不是全局：一个人刷屏不该把别人的正常对话饿死。
   * 被限流的消息**直接丢弃**（不排队）—— 排队会让风控更危险（积压后集中爆发）。
   */
  const rlKey = sessionKeyOf(e);
  if (!allowByRate(rlKey, Number(cfg.rateLimitPerMinute) || 0)) {
    stats.skipped++;
    stats.lastSkipReason = '限流';
    log('warn', `限流丢弃：${rlKey}`);
    diag(`skip: rate limited (${rlKey})`);
    return;
  }

  if (inSilence()) {
    stats.skipped++;
    stats.lastSkipReason = '静默期';
    log('info', '跳过：静默期');
    diag('skip: silence window');
    return;
  }

  const prob = e.message_type === 'private' ? Number(cfg.replyPrivate) : Number(cfg.replyGroup);
  if (Math.random() > prob) {
    stats.skipped++;
    stats.lastSkipReason = `概率未命中（${prob}）`;
    log('info', `跳过：回复概率 ${prob}`);
    diag(`skip: probability ${prob}`);
    return;
  }

  if (!cfg.agentId) {
    stats.lastError = '未配置 agentId';
    log('warn', '未配置 agentId，无法调用平台');
    return;
  }

  // 图片先上传换成 fileId（多模态消息只认它）；最多 3 张，避免 token 与带宽失控
  const imageIds = [];
  if (images.length > 0) {
    diag(`images=${images.length} vision=${cfg.visionEnabled !== false}`);
    if (cfg.visionEnabled !== false) {
      for (const img of images.slice(0, 3)) {
        const got = await fetchImageBase64(img);
        if (!got) {
          continue;
        }
        try {
          const id = await uploadImage(got.base64, got.mime);
          if (id) {
            imageIds.push(id);
            diag(`image uploaded fileId=${id} size=${Math.round(got.base64.length / 1024)}KB`);
          }
        } catch (err) {
          diag(`image upload failed: ${err.message}`);
        }
      }
    }
  }

  const isPrivate = e.message_type === 'private';
  const peer = isPrivate ? Number(e.user_id) : Number(e.group_id);
  // 每个联系人 / 每个群独立会话 —— 否则 A 群的上下文会跑到 B 群
  const sessionId = isPrivate
    ? `qq:private:${stats.uin}:${peer}`
    : `qq:group:${stats.uin}:${peer}`;

  try {
    log('info', `调平台：${sessionId}`);
    diag(`call platform agentId=${cfg.agentId} session=${sessionId}`);
    const answer = await askPlatform(sessionId, text, imageIds, peer);
    /*
     * 记录回复**原文前 80 字**：只看长度无法判断"为什么只答了一题"或"漏了哪个符号"，
     * 每次都要回头问用户截图，效率太低。
     */
    diag(`platform replied len=${answer ? answer.length : 0} preview=${JSON.stringify((answer || '').slice(0, 80))}`);
    if (!answer) {
      log('warn', '平台返回空内容，跳过发送');
      diag('skip: platform returned empty');
      return;
    }

    /*
     * 潜水（2026-10-21）：模型可以决定"这句话我不接"。
     *
     * 这是"自主"最核心的那部分 —— 群里 50 条闲聊，真人只回 1 条。
     * 判据很严：只有**整条回复就是 `[SILENT]`** 才算；说了别的话再带标记照常发。
     */
    if (isSilent(answer)) {
      stats.skipped++;
      stats.lastSkipReason = '模型选择不接话';
      log('info', '模型选择不接话（[SILENT]）');
      diag('skip: model chose [SILENT]');
      return;
    }

    /*
     * 图库（2026-10-21）：模型在回复里写了 `[收藏 理由]` → 把这条消息里的图存进她的相册。
     * 决定权在模型，不在代码 —— 参考同类项目那句"意义不在图，在于她挑"。
     */
    const collected = extractCollect(answer);
    if (collected.reason && images.length > 0) {
      const got = await fetchImageBase64(images[0]);
      if (got) {
        saveToGallery(got.base64, collected.reason);
      }
    }
    let body = (collected.text || '').trim();

    /* 说说（F9）：模型写了 `[说说 内容]` → 发动态（受开关与冷却约束） */
    const qz = extractQzone(body);
    body = qz.text;
    if (qz.content) {
      await tryPostQzone(qz.content);
    }

    /* 发图：模型写了 `[发图 关键词]` → 从她自己的相册里挑一张发出去 */
    const sendImg = extractSendImage(body);
    body = sendImg.text;

    if (!body && sendImg.keyword === null) {
      // 只做了收藏/说说，没有正文：不必再发一条空消息
      stats.replied++;
      diag('side-effect only, nothing to send');
      return;
    }
    if (body) {
      await humanSend(isPrivate, peer, body);
    }
    if (sendImg.keyword !== null) {
      const b64 = pickGalleryImage(sendImg.keyword);
      if (b64) {
        await sendImageMsg(isPrivate, peer, b64);
      } else {
        diag(`gallery: 没有匹配「${sendImg.keyword}」的图`);
      }
    }
    stats.replied++;
    diag(`sent ok -> ${peer}`);
  } catch (err) {
    stats.lastError = err.message || String(err);
    log('error', `处理失败：${stats.lastError}`);
    diag(`ERROR ${stats.lastError}`);
  }
}

/**
 * 从本地表情库里挑一张（2026-10-20，P2）。
 *
 * <p>文件名即标签：`开心_01.png` / `无语_02.jpg` 这样，按回答里的情绪词优先匹配，
 * 匹配不到就从全部里随机 —— 没有标签的目录也能用，只是"随机发表情"。</p>
 *
 * <p>返回 base64（不返回路径）：图片要在 `utilityProcess` 里读出再发给 NapCat，
 * 而 NapCat 对**本机绝对路径**的访问受它的运行身份限制（Docker 部署时根本读不到宿主路径）。</p>
 */
function pickSticker(moodWords) {
  const dir = cfg.stickerDir;
  if (!dir) {
    return null;
  }
  let files = [];
  try {
    files = fs.readdirSync(dir).filter((f) => /\.(png|jpe?g|gif|webp)$/i.test(f));
  } catch (e) {
    diag(`sticker dir unreadable: ${e.message}`);
    return null;
  }
  if (files.length === 0) {
    return null;
  }
  const tagged = moodWords.length
    ? files.filter((f) => moodWords.some((w) => f.includes(w)))
    : [];
  const pool = tagged.length > 0 ? tagged : files;
  const file = path.join(dir, pool[Math.floor(Math.random() * pool.length)]);
  try {
    return fs.readFileSync(file).toString('base64');
  } catch (e) {
    diag(`sticker read failed: ${e.message}`);
    return null;
  }
}

/**
 * 文本转语音（2026-10-20，P2）。
 *
 * <p>为什么插件自己调而不复用平台的 TTS 插件：**插件之间不能互相调用工具**，
 * 架构上也不该让一个插件依赖另一个插件（会被卸载、会被停用）。
 * 所以 TTS 的地址与密钥属于本插件自己的配置。</p>
 *
 * <p>返回 mp3 的 base64；未配置或失败返回 null（**静默退化为纯文本**，
 * 绝不能因为"语音发不出来"让整轮回复丢掉）。</p>
 */
async function textToSpeech(text) {
  if (!cfg.ttsBaseUrl || !cfg.ttsApiKey) {
    return null;
  }
  try {
    const url = `${String(cfg.ttsBaseUrl).replace(/\/+$/, '')}/audio/speech`;
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${cfg.ttsApiKey}`,
      },
      body: JSON.stringify({
        model: cfg.ttsModel || 'tts-1',
        input: text,
        voice: cfg.ttsVoice || 'alloy',
        response_format: 'mp3',
      }),
    });
    if (!res.ok) {
      const err = await res.text().catch(() => '');
      diag(`tts HTTP ${res.status}: ${err.slice(0, 120)}`);
      return null;
    }
    const buf = Buffer.from(await res.arrayBuffer());
    diag(`tts ok ${Math.round(buf.length / 1024)}KB`);
    return buf.toString('base64');
  } catch (e) {
    diag(`tts failed: ${e.message}`);
    return null;
  }
}

/** 从回答里粗判情绪（用于挑带标签的表情包）。 */
function moodOf(text) {
  const rules = [
    ['开心', ['哈哈', '嘿嘿', '笑死', '太好了', '开心', '高兴', '棒', '赞']],
    ['无语', ['无语', '算了', '行吧', '离谱', '服了']],
    ['难过', ['难过', '难受', '唉', '可惜', '遗憾']],
    ['生气', ['生气', '气死', '过分', '讨厌']],
  ];
  return rules.filter(([, words]) => words.some((w) => text.includes(w))).map(([tag]) => tag);
}

/** 拟人化发送：分段 + "正在输入" + 打字延迟 + 段间间隔。 */
async function humanSend(isPrivate, peer, text) {
  /*
   * 语音优先（2026-10-20）：概率命中且回答足够短时，整条转成语音发出去，
   * 不再走分段文字。真人也常这样 —— 一句"好呀"更可能用嘴说，而不是打字。
   */
  const voiceWanted = Math.random() < Number(cfg.voiceChance || 0)
    && text.length <= Number(cfg.voiceMaxChars || 60);
  if (voiceWanted) {
    const audio = await textToSpeech(text);
    if (audio) {
      if (cfg.typing) {
        try {
          await call('set_input_status', {
            user_id: isPrivate ? peer : undefined,
            group_id: isPrivate ? undefined : peer,
            event_type: 1,
          }, 5000);
        } catch {
          /* 不支持就算了 */
        }
      }
      await sleep(rand(cfg.firstDelayMs) / 2 + typingMs(text));
      try {
        if (isPrivate) {
          await call('send_private_msg', {
            user_id: peer,
            message: [{ type: 'record', data: { file: 'base64://' + audio } }],
          });
        } else {
          await call('send_group_msg', {
            group_id: peer,
            message: [{ type: 'record', data: { file: 'base64://' + audio } }],
          });
        }
        diag(`voice sent to=${peer} size=${Math.round(audio.length / 1024)}KB`);
        return;
      } catch (e) {
        // 语音发失败 → 退化为文字，别让这一轮白跑
        diag(`voice send FAILED: ${e.message}, fallback to text`);
      }
    }
  }

  const segs = splitText(text, Number(cfg.maxSegments), Number(cfg.maxSegmentChars));
  for (const seg of segs) {
    if (cfg.typing) {
      try {
        await call('set_input_status', {
          user_id: isPrivate ? peer : undefined,
          group_id: isPrivate ? undefined : peer,
          event_type: 1,
        }, 5000);
      } catch {
        // 不支持就算了，退化为纯延迟
      }
    }
    await sleep(rand(cfg.firstDelayMs) / 2 + typingMs(seg));
    if (cfg.typing) {
      try {
        await call('set_input_status', {
          user_id: isPrivate ? peer : undefined,
          group_id: isPrivate ? undefined : peer,
          event_type: 0,
        }, 5000);
      } catch {
        /* 同上 */
      }
    }
    try {
      if (isPrivate) {
        await call('send_private_msg', { user_id: peer, message: seg });
      } else {
        await call('send_group_msg', { group_id: peer, message: seg });
      }
      diag(`send ${isPrivate ? 'private' : 'group'} to=${peer} len=${seg.length} ok`);
    } catch (sendErr) {
      // 单条发送失败不该中断剩下的分段，但必须留下证据
      diag(`send FAILED to=${peer} len=${seg.length}: ${sendErr.message}`);
      throw sendErr;
    }
    await sleep(rand(cfg.segmentGapMs));
  }

  // 表情包：按概率附一张（真人也常常"说完了补个表情"）
  if (Math.random() < Number(cfg.stickerChance || 0)) {
    const b64 = pickSticker(moodOf(text));
    if (b64) {
      try {
        if (isPrivate) {
          await call('send_private_msg', {
            user_id: peer,
            message: [{ type: 'image', data: { file: 'base64://' + b64 } }],
          });
        } else {
          await call('send_group_msg', {
            group_id: peer,
            message: [{ type: 'image', data: { file: 'base64://' + b64 } }],
          });
        }
        diag(`sticker sent to=${peer} size=${Math.round(b64.length / 1024)}KB`);
      } catch (e) {
        diag(`sticker FAILED: ${e.message}`);
      }
    }
  }
}

// ------------------------------------------------------------------ 日记与印象（2026-10-21）

/** 日记文件里最多保留多少条（防无限增长；纯文本，grep / 手改都方便）。 */
const DIARY_MAX = 500;

function diaryPath() {
  return path.join(api.dataDir, 'diary.jsonl');
}

function impressionPath() {
  return path.join(api.dataDir, 'impressions.json');
}

function fmtNow() {
  const n = new Date();
  const p = (x) => String(x).padStart(2, '0');
  return `${n.getFullYear()}-${p(n.getMonth() + 1)}-${p(n.getDate())} ${p(n.getHours())}:${p(n.getMinutes())}`;
}

/** 读最近几条日记（新的在后）。 */
function readDiary(limit = 2) {
  try {
    const lines = fs.readFileSync(diaryPath(), 'utf8').split('\n').filter(Boolean);
    return lines.slice(-limit).map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return null;   // 手工改坏的行直接跳过，不让它影响整体
      }
    }).filter(Boolean);
  } catch {
    return [];   // 还没写过
  }
}

function appendDiary(entry) {
  try {
    fs.appendFileSync(diaryPath(), JSON.stringify(entry) + '\n', 'utf8');
    const all = fs.readFileSync(diaryPath(), 'utf8').split('\n').filter(Boolean);
    if (all.length > DIARY_MAX) {
      fs.writeFileSync(diaryPath(), all.slice(-DIARY_MAX).join('\n') + '\n', 'utf8');
    }
    return true;
  } catch (e) {
    diag(`diary write failed: ${e.message}`);
    return false;
  }
}

// ------------------------------------------------------------------ 图库（2026-10-21）

function galleryDir() {
  return path.join(api.dataDir, 'gallery');
}

function readGallery(limit = 2) {
  try {
    const lines = fs.readFileSync(path.join(galleryDir(), 'index.jsonl'), 'utf8').split('\n').filter(Boolean);
    return lines.slice(-limit).map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return null;
      }
    }).filter(Boolean);
  } catch {
    return [];
  }
}

/**
 * 存一张图进"她自己的相册"。
 *
 * <p>参考同类项目的做法：**意义不在图，在于"她挑"**。所以入口不是"收到图就存"，
 * 而是模型在回复里说 `[收藏 理由]` 才存 —— 决定权在它。</p>
 */
function saveToGallery(base64, reason) {
  try {
    const dir = galleryDir();
    fs.mkdirSync(dir, { recursive: true });
    const name = `img_${Date.now()}.png`;
    fs.writeFileSync(path.join(dir, name), Buffer.from(base64, 'base64'));
    fs.appendFileSync(
      path.join(dir, 'index.jsonl'),
      JSON.stringify({ file: name, reason: reason || '', time: fmtNow() }) + '\n',
      'utf8',
    );
    diag(`gallery: 收藏 ${name}（${reason || '无理由'}）`);
    return true;
  } catch (e) {
    diag(`gallery save failed: ${e.message}`);
    return false;
  }
}

/** 从回复里抽出 `[收藏 理由]`，返回去掉标记后的文本与理由。 */
function extractCollect(text) {
  const raw = String(text || '');
  const m = raw.match(/\[收藏[：:\s]*([^\]]{1,40})\]/);
  if (!m) {
    return { text: raw, reason: null };
  }
  return { text: raw.replace(m[0], '').trim(), reason: m[1].trim() };
}

/** 联系人 → { text, updatedAt, msgCount } */
let impressions = {};

function loadImpressions() {
  try {
    impressions = JSON.parse(fs.readFileSync(impressionPath(), 'utf8')) || {};
  } catch {
    impressions = {};
  }
}

function saveImpressions() {
  try {
    fs.writeFileSync(impressionPath(), JSON.stringify(impressions, null, 2), 'utf8');
  } catch (e) {
    diag(`save impressions failed: ${e.message}`);
  }
}

/**
 * 写日记（2026-10-21）。
 *
 * <p><b>为什么由心跳触发、而不是定时打卡</b>：参考同类项目的经验 ——
 * "每天 23:00 准时写一条，那叫打卡，不叫日记"。所以这里检查间隔是 3~12 小时随机，
 * 且只有 30% 的概率真的写，模型还可以直接回 `[不写]`。</p>
 *
 * <p><b>为什么用独立会话</b>：`qq:diary:<uin>` —— 独处时的自言自语不该写进任何
 * 联系人的对话历史，否则下次召回会把"她自己的心事"当成"跟某人说过的话"。</p>
 */
async function maybeWriteDiary() {
  if (!cfg.diaryEnabled || !stats.connected || !cfg.agentId) {
    return;
  }
  const now = Date.now();
  const gapHours = 3 + Math.random() * 9;   // 3~12 小时
  if (now - (selfState.lastDiaryAt || 0) < gapHours * 3600000) {
    return;
  }
  if (Math.random() > 0.3) {
    return;   // 多数时候不想写，这才正常
  }
  selfState.lastDiaryAt = now;
  saveSelfState();

  const prompt = [
    '（独处时刻，没人在跟你说话，这条也不会被任何人看到。）',
    '如果你想写点什么就写；不想写就只回 [不写]。',
    '第一行写心情（两三个字就行），第二行起写内容，总共不超过三句。',
    '不要写"今天真是充实的一天"这种总结句，也不要汇报你和谁说了几次话。',
  ].join('\n');

  try {
    const answer = await askPlatform(`qq:diary:${stats.uin}`, prompt, [], null);
    const text = String(answer || '').trim();
    if (!text || text.includes('[不写]')) {
      diag('diary: 这次不想写');
      return;
    }
    const lines = text.split('\n').map((s) => s.trim()).filter(Boolean);
    const mood = lines.length > 1 ? lines[0].slice(0, 10) : '';
    const body = (lines.length > 1 ? lines.slice(1).join(' ') : lines[0]).slice(0, 200);
    if (!body) {
      return;
    }
    appendDiary({ time: fmtNow(), mood, text: body });
    diag(`diary: 记了一条「${body.slice(0, 40)}」`);
  } catch (e) {
    diag(`diary failed: ${e.message}`);
  }
}

/**
 * 更新"印象"（2026-10-21）。
 *
 * <p>事实记忆（他养了只猫）由平台的四层记忆负责；这里记的是**判断**——
 * "话多但不烦人"这种几个字的印象。参考同类项目的提醒：**人会变，要定期重写**，
 * 否则三个月后还在用第一次见面的判断跟人说话。</p>
 *
 * <p>轮询式更新，一轮只处理一个联系人 —— 免得心跳里串行几十次模型调用。</p>
 */
async function maybeUpdateImpressions() {
  if (!cfg.impressionEnabled || !stats.connected || !cfg.agentId) {
    return;
  }
  const now = Date.now();
  const week = 7 * 86400000;

  for (const [peer, rec] of recentContacts.entries()) {
    if (!rec.isPrivate) {
      continue;
    }
    const count = rec.count || 0;
    if (count < 20) {
      continue;   // 聊得太少，总结出来也是瞎猜
    }
    const old = impressions[String(peer)];
    if (old && now - (old.updatedAt || 0) < week) {
      continue;   // 一周内不重写
    }
    if (old && count - (old.msgCount || 0) < 20) {
      continue;   // 又聊了不到 20 句，没必要重写
    }

    try {
      const sessionId = `qq:private:${stats.uin}:${peer}`;
      const answer = await askPlatform(
        sessionId,
        '（用**不超过 20 个字**概括你对这个人的印象，只输出那一句话。不要解释，不要写成小作文。）',
        [],
        peer,
      );
      const text = String(answer || '').replace(/\s+/g, ' ').trim().slice(0, 60);
      if (text) {
        impressions[String(peer)] = { text, updatedAt: now, msgCount: count };
        saveImpressions();
        diag(`impression updated for ${peer}: ${text}`);
      }
    } catch (e) {
      diag(`impression failed for ${peer}: ${e.message}`);
    }
    break;   // 一轮一个
  }
}

// ------------------------------------------------------------------ P3：自我管理 + 主动发言

function selfStatePath() {
  return path.join(api.dataDir, 'self-state.json');
}

function loadSelfState() {
  try {
    const raw = JSON.parse(fs.readFileSync(selfStatePath(), 'utf8'));
    for (const k of Object.keys(selfState)) {
      // 数字与字符串两种都要接受（时间是 number，昵称/签名是 string）
      if (typeof raw[k] === 'number' || typeof raw[k] === 'string') {
        selfState[k] = raw[k];
      }
    }
    diag(`self-state loaded: ${JSON.stringify(selfState)}`);
  } catch {
    /* 首次运行没有这个文件 */
  }
}

function saveSelfState() {
  try {
    fs.writeFileSync(selfStatePath(), JSON.stringify(selfState, null, 2), 'utf8');
  } catch (e) {
    diag(`save self state failed: ${e.message}`);
  }
}

const pickRandom = (arr) => arr[Math.floor(Math.random() * arr.length)];

/** 把 [min,max] 天转成随机毫秒。 */
const randomDays = (range) =>
  (range[0] + Math.random() * Math.max(0, range[1] - range[0])) * 86400000;

/**
 * 按模型给的「画面描述」从头像池里挑一张（2026-10-21）。
 *
 * <p>不做随机：模型说"想要一只橘猫"，就在文件名里找含"橘猫/猫"的图；
 * **找不到就不换**，而不是随便塞一张 —— 否则"模型决定"又退化成"随机"。</p>
 *
 * <p>为什么不用图片生成：插件之间不能互调工具（图片生成插件调不到），
 * 要生成就得让插件自己配一份生图密钥，那是另一层负担。用文件名当标签更轻。</p>
 */
function pickAvatarByHint(hint) {
  if (!hint || !cfg.avatarDir) {
    return null;
  }
  try {
    const files = fs.readdirSync(cfg.avatarDir).filter((f) => /\.(png|jpe?g|gif|webp)$/i.test(f));
    if (!files.length) {
      diag('avatar dir is empty');
      return null;
    }
    // 把描述拆成词，任一词出现在文件名里就算命中
    const words = String(hint)
      .split(/[,，、;；\s]+/)
      .map((w) => w.trim())
      .filter((w) => w.length >= 1);
    const hit = files.filter((f) => words.some((w) => f.includes(w)));
    if (!hit.length) {
      diag(`avatar hint "${hint}" 在池子里没有匹配的图，跳过换头像`);
      return null;
    }
    return fs.readFileSync(path.join(cfg.avatarDir, pickRandom(hit))).toString('base64');
  } catch (e) {
    diag(`avatar dir unreadable: ${e.message}`);
    return null;
  }
}

/**
 * 下载一张图并转 base64（2026-10-21）。
 *
 * <p>用于模型给出的图片直链：`image_gen` 生成结果、或 `web_search` 找到的图。
 * 转 base64 而不是把 URL 直接交给 NapCat —— NapCat 若在 Docker 里，
 * 它自己去拉外网图可能失败（DNS/代理不同），而插件这边是宿主网络，更可靠。</p>
 */
async function fetchImageAsBase64(url) {
  if (!url || !/^https?:\/\//i.test(url)) {
    return null;
  }
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(20000) });
    if (!res.ok) {
      diag(`avatar url HTTP ${res.status}: ${url.slice(0, 80)}`);
      return null;
    }
    const buf = Buffer.from(await res.arrayBuffer());
    if (buf.length === 0 || buf.length > 8 * 1024 * 1024) {
      diag(`avatar image size abnormal: ${buf.length} bytes`);
      return null;
    }
    diag(`avatar image downloaded: ${Math.round(buf.length / 1024)}KB`);
    return buf.toString('base64');
  } catch (e) {
    diag(`avatar url fetch failed: ${e.message}`);
    return null;
  }
}

/**
 * 让模型自己决定要不要改资料（2026-10-21，替代"随机抽池子"）。
 *
 * <p>随机抽取的问题：它没有"自我"。同一个模型上一秒叫"小白"、下一秒叫"小雾"，
 * 全靠 Math.random —— 那不叫拟人，叫换皮。让模型基于自己的人格与最近经历来决定，
 * 才有"这个号是一个人"的连续性。</p>
 *
 * <p><b>独立会话</b>：`qq:selfmanage:<uin>`，不写进任何对话历史 ——
 * 自我检视的独白不该污染某个联系人的上下文。</p>
 */
async function askSelfDecision() {
  const prompt = [
    '（这是一次自我检视，对方看不到这条消息，不需要回复任何人。）',
    `你现在的昵称是「${selfState.nicknameValue || '（未记录）'}」，`
      + `签名是「${selfState.signatureValue || '（空）'}」。`,
    '如果你此刻想换昵称 / 签名 / 头像，就给出新的；不想换就都留 null。',
    '像真人一样：资料很少变，绝大多数时候应该什么都不改。',
    '想换头像时，**你可以先调用 image_gen 工具生成一张**（把「你想要的画面」当提示词），',
    '然后用工具返回的图片直链填 avatarUrl。也可以用 web_search 找现成的图。',
    '如果你只是描述画面而不想调用工具，就把描述写进 avatarHint，我会去本地图库里找相近的。',
    '只输出一行 JSON，不要解释、不要代码块：',
    '{"nickname": "新昵称或 null", "signature": "新签名或 null", '
      + '"avatarUrl": "图片直链或 null", "avatarHint": "画面描述或 null"}',
  ].join('\n');

  const answer = await askPlatform(`qq:selfmanage:${stats.uin}`, prompt, [], null);
  diag(`self-manage decision raw: ${JSON.stringify((answer || '').slice(0, 120))}`);
  return parseDecision(answer);
}

/** 解析模型给的 JSON（宽松：容忍代码块围栏、前后废话、"null" 字符串）。 */
function parseDecision(text) {
  if (!text) {
    return null;
  }
  try {
    const m = String(text).match(/\{[\s\S]*\}/);
    if (!m) {
      return null;
    }
    const j = JSON.parse(m[0]);
    const pick = (v) => {
      const s = v == null ? '' : String(v).trim();
      return !s || s === 'null' || s === 'undefined' ? null : s;
    };
    return {
      nickname: pick(j.nickname),
      signature: pick(j.signature),
      avatarUrl: pick(j.avatarUrl),   // 模型用 image_gen / web_search 拿到的直链
      avatarHint: pick(j.avatarHint), // 只给描述，从本地图库找
    };
  } catch (e) {
    diag(`parse decision failed: ${e.message}`);
    return null;
  }
}

/**
 * 自我管理（文档 §5.5）：**冷却到期才动**，冷却值随机且落盘。
 *
 * <p>为什么不"模型想改就改"：让模型自由决定改资料，等于让它一天改三次 ——
 * 频繁改昵称/头像/签名是最典型的异常账号特征，比不改更容易被风控。</p>
 */
async function maybeSelfManage() {
  if (!cfg.selfManageEnabled || !stats.connected || !cfg.agentId) {
    return;
  }
  const now = Date.now();

  /*
   * 检视冷却（2~6 小时随机）：模型说"不改"之后不能每 10 分钟再问一次 ——
   * 那会白白烧掉一次模型调用。所以"问过"本身就推进这个计时。
   */
  const minH = Math.max(0.1, Number(cfg.selfCheckHoursMin) || 2);
  const maxH = Math.max(minH, Number(cfg.selfCheckHoursMax) || 6);
  const checkHours = minH + Math.random() * (maxH - minH);
  if (now - (selfState.lastCheck || 0) < checkHours * 3600000) {
    return;
  }

  // 三类都还在冷却里 → 连模型都不用问
  const avatarDue = now >= (selfState.avatar || 0);
  const signatureDue = now >= (selfState.signature || 0);
  const nicknameDue = now >= (selfState.nickname || 0);
  if (!avatarDue && !signatureDue && !nicknameDue) {
    return;
  }

  const decision = await askSelfDecision();
  selfState.lastCheck = now;
  if (!decision) {
    saveSelfState();
    diag('self-manage: 模型没给出可用决定，本轮跳过');
    return;
  }

  let changed = 0;

  if (nicknameDue && decision.nickname) {
    try {
      await call('set_qq_profile', { nickname: decision.nickname }, 15000);
      selfState.nickname = now + randomDays(cfg.nicknameCooldownDays || [30, 90]);
      selfState.nicknameValue = decision.nickname;
      changed++;
      diag(`self-manage: nickname -> ${decision.nickname}`);
    } catch (e) {
      diag(`self-manage nickname failed: ${e.message}`);
    }
  }

  if (signatureDue && decision.signature) {
    try {
      await call('set_self_longnick', { longNick: decision.signature }, 15000);
      selfState.signature = now + randomDays(cfg.signatureCooldownDays || [3, 14]);
      selfState.signatureValue = decision.signature;
      changed++;
      diag(`self-manage: signature -> ${decision.signature}`);
    } catch (e) {
      diag(`self-manage signature failed: ${e.message}`);
    }
  }

  if (avatarDue && (decision.avatarUrl || decision.avatarHint)) {
    /*
     * 优先用模型给的直链（它能调 image_gen 生成、或 web_search 找现成的），
     * 拿不到再用「画面描述」去本地图库里按关键词匹配。
     */
    let b64 = decision.avatarUrl ? await fetchImageAsBase64(decision.avatarUrl) : null;
    if (!b64 && decision.avatarHint) {
      b64 = pickAvatarByHint(decision.avatarHint);
    }
    if (b64) {
      try {
        await call('set_qq_avatar', { file: 'base64://' + b64 }, 30000);
        selfState.avatar = now + randomDays(cfg.avatarCooldownDays || [7, 30]);
        changed++;
        diag(`self-manage: avatar <- ${decision.avatarUrl ? decision.avatarUrl : '"' + decision.avatarHint + '"'}`);
      } catch (e) {
        diag(`self-manage avatar failed: ${e.message}`);
      }
    }
  }

  saveSelfState();
  diag(`self-manage: 本轮决定改动 ${changed} 项（模型原话已在上方记录）`);
}

/**
 * 主动发言（文档 §9.2）。
 *
 * <p>三个条件全部满足才发：开关打开 + 距上次超过随机间隔（12~48h）+ 存在"最近 48 小时内
 * 互动过"的联系人。**没人聊过就不主动找人** —— 否则就是随机骚扰。</p>
 *
 * <p>为什么不纯用定时器：定时器会变成"每 6 小时准点发一条"，那是最像机器人的行为。
 * 这里心跳只负责"检查条件"，真正的间隔是随机的，且必须有人近期聊过。</p>
 */
async function maybeProactive() {
  if (!cfg.proactiveEnabled || !stats.connected || !cfg.agentId) {
    return;
  }
  const now = Date.now();
  const r = cfg.proactiveGapHours || [12, 48];
  const gapMs = (r[0] + Math.random() * Math.max(0, r[1] - r[0])) * 3600000;
  if (now - (selfState.lastProactiveAt || 0) < gapMs) {
    return;
  }

  /*
   * 概率闸门（2026-10-21）：检查到了不等于真要发。
   * 参考同类项目的实测值 0.35 —— 多数检查都会放弃，行为才不像闹钟。
   */
  const chance = Number(cfg.proactiveChance);
  if (Math.random() > (Number.isFinite(chance) ? chance : 0.35)) {
    diag('proactive: 概率闸门未通过，本轮不发');
    return;
  }

  const within = (Number(cfg.proactiveWithinHours) || 48) * 3600000;
  const quietMs = (Number(cfg.proactiveQuietMinutes) || 15) * 60000;
  const cands = [...recentContacts.entries()].filter(([, v]) =>
    v.isPrivate
      && now - v.lastAt < within
      // 对方还在聊的时候不要主动插话，等他安静满 quietMinutes
      && now - v.lastAt >= quietMs);
  if (cands.length === 0) {
    diag('proactive: 没有「已安静够久」的联系人，跳过');
    return;
  }

  const [peer] = pickRandom(cands);
  selfState.lastProactiveAt = now;
  saveSelfState();

  try {
    const sessionId = `qq:private:${stats.uin}:${peer}`;

    /*
     * 可选：先看一眼最近的热门（2026-10-21）。
     *
     * 只在开关打开时执行，且**失败不影响主动发言** —— 拿不到内容就退化为
     * "凭记忆聊一句"，这比"因为抓取失败所以这次不发了"合理。
     */
    let peekBlock = '';
    if (cfg.proactivePeek && api.tools) {
      try {
        const r = await api.tools.execute('bilibili_hot', { limit: Number(cfg.proactivePeekCount) || 5 });
        const out = r && r.output;
        if (r && r.success !== false && out) {
          peekBlock = `\n\n【你刚看到的 B 站热门】\n${String(out).slice(0, 800)}`
            + '\n\n（挑一个你真正感兴趣的说，不要罗列榜单。）';
          diag('proactive peek: bilibili_hot ok');
        } else {
          diag(`proactive peek: 工具返回失败 ${(r && r.error) || ''}`);
        }
      } catch (e) {
        diag(`proactive peek failed: ${e.message}`);
      }
    }

    diag(`proactive: 向 ${peer} 发起${peekBlock ? '（含热门话题）' : ''}`);
    const answer = await askPlatform(sessionId, cfg.proactivePrompt + peekBlock, [], peer);
    if (!answer) {
      diag('proactive: 平台返回空，放弃本轮');
      return;
    }
    await humanSend(true, peer, answer);
    diag(`proactive sent -> ${peer}`);
  } catch (e) {
    diag(`proactive failed: ${e.message}`);
  }
}

/** 心跳：10 分钟一次；启动后 1 分钟先跑一次（别让重启后很久不检查）。 */
function startHeartbeat() {
  stopHeartbeat();
  const tick = () => {
    void maybeSelfManage();
    void maybeProactive();
    void maybeWriteDiary();
    void maybeUpdateImpressions();
    void maybeUpdateOnlineStatus();
  };
  heartbeat = setInterval(tick, 10 * 60 * 1000);
  setTimeout(tick, 60 * 1000);
}

function stopHeartbeat() {
  if (heartbeat) {
    clearInterval(heartbeat);
    heartbeat = null;
  }
}

// ------------------------------------------------------------------ 生命周期

module.exports = {
  /** 由主进程在加载后调用（api = { pluginId, dataDir, logger, platform }）。 */
  start(a) {
    api = a;
    cfg = loadConfig();
    loadSelfState();
    loadImpressions();
    log('info', `QQ 助手已加载，配置：${configPath()}`);
    log('info', `启用状态：${cfg.enabled ? '开' : '关（改 config.json 后调 methods.reload）'}`);
    log('info', `自我管理：${cfg.selfManageEnabled ? '开' : '关'}；主动发言：${cfg.proactiveEnabled ? '开' : '关'}`);
    if (cfg.enabled) {
      connect();
      startHeartbeat();
    }
  },

  /** 卸载时必须清干净：WS、重连定时器、静默定时器。留着会拖住应用退出。 */
  stop() {
    stopping = true;
    stopHeartbeat();
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    try {
      ws?.close();
    } catch {
      /* 已关闭 */
    }
    ws = null;
    stats.connected = false;
    log('info', 'QQ 助手已停止');
  },

  methods: {
    /** 运行状态（前端状态球轮询它）。 */
    status() {
      return { ...stats, configPath: configPath(), enabled: !!cfg?.enabled };
    },

    /** 读配置（设置面板用）。 */
    getConfig() {
      return { ...cfg };
    },

    /** 保存配置并热重连（改完不用重启应用）。 */
    saveConfig(patch) {
      saveConfig(patch || {});
      log('info', '配置已保存，按新配置重连');
      stopping = true;
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      try {
        ws?.close();
      } catch {
        /* ignore */
      }
      ws = null;
      stopping = false;
      silencePrimed = false;
      silenceUntil = 0;
      if (cfg.enabled) {
        connect();
      }
      return { ...cfg };
    },

    /** 重新读文件（手改 config.json 后调用）。 */
    reload() {
      cfg = loadConfig();
      return { ...cfg };
    },

    /**
     * 测试「插件侧工具通道」（2026-10-21）：直接调平台工具，**不经过模型**。
     *
     * <p>与"模型在对话里自己调工具"是两条路：这条是插件代码主动发起，
     * 用来验证 `api.tools.execute` 通不通、以及当前用户能看到哪些工具。</p>
     */
    async testTools() {
      if (!api || !api.tools) {
        return { ok: false, error: 'api.tools 不可用（pluginRuntime 版本过旧？需重启应用）' };
      }
      try {
        const list = await api.tools.list();
        const names = (Array.isArray(list) ? list : []).map((t) => t.name).filter(Boolean);
        diag(`tools available(${names.length}): ${names.join(', ')}`);

        /*
         * 优先精确取 `web_search` —— 注意 `search`（知识库检索）和 `github_search`
         * 名字里都带 search，正则随便匹配会把参数传错（实测踩过）。
         *
         * ⚠️ `web_search` 是**插件工具**：必须在「插件」页把「联网搜索」挂到本智能体才会出现；
         * 而 `fs_*` / `git_*` / `search` 是全局组件工具，无需挂载。
         */
        const search = names.includes('web_search')
          ? 'web_search'
          : (names.find((n) => /search/i.test(n)) || '');
        if (!search) {
          return { ok: false, tools: names, error: '没有可用的 search 类工具' };
        }
        const r = await api.tools.execute(search, { query: '今天哈尔滨天气' });
        const failed = !(r && r.success);
        diag(`tool ${search} -> success=${r && r.success} error=${(r && r.error) || '(empty)'}`);
        return {
          ok: !failed,
          tools: names,
          tool: search,
          result: r,
          // success=false 但没给 error 时，别显示"未知错误"——那对排查毫无帮助
          error: failed
            ? ((r && r.error)
              || (search === 'web_search'
                ? '调用失败（参数或后端问题）'
                : `「${search}」不是联网搜索工具，参数对不上；真正需要的是 web_search`))
            : '',
          hint: names.includes('web_search')
            ? ''
            : 'web_search 不可用：到「插件」页把「联网搜索」挂到本智能体（内置插件默认不挂载）',
        };
      } catch (e) {
        diag(`testTools failed: ${e.message}`);
        return { ok: false, error: e.message };
      }
    },
  },
};
