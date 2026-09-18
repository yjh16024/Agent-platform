/**
 * DSH 皮肤自定义协议 —— **宿主侧**实现。
 *
 * <h3>为什么是这个形状（而不是"猜属性名"）</h3>
 * 真实皮肤的 `customization.ts` 是这样写的：
 *
 * ```ts
 * exposeSkinCustomization({
 *   protocol: SKIN_CUSTOMIZATION_PROTOCOL,   // v2
 *   skinId: 'orca-link',
 *   title: 'ORCA LINK',
 *   settings: [
 *     { key: 'character',    type: 'boolean', label: '显示左上角状态小人', defaultValue: true },
 *     { key: 'background',   type: 'boolean', label: '显示背景',           defaultValue: true },
 *     { key: 'pricingLight', type: 'boolean', label: '显示红绿灯定价指示', defaultValue: true },
 *     { key: 'sfwMode', type: 'visibility-schedule', label: '不那么二次元模式', ... },
 *   ],
 *   apply,   // 皮肤自己把 state 投影成 html[data-dsh-whale-orca-*] 之类的属性
 * })
 * ```
 *
 * 也就是说：**皮肤声明"有哪些开关"，宿主负责存储 + 渲染 UI + 把 state 回调给它**。
 * 属性名（`data-dsh-whale-orca-background`）是**皮肤自己的事**，宿主完全不需要知道。
 *
 * 所以正确做法是实现这套协议，而不是去猜 `data-dsh-whale-*`：
 * **只要皮肤遵守协议，任何皮肤的任何开关都能被我们管理**，无需为某个皮肤打补丁。
 *
 * <h3>协议要点（照搬 {@code skin-manager/src/protocol.ts}）</h3>
 * <ul>
 *   <li><b>事件</b>（v2）：`dsh:skin-customization-{register,unregister,ready}-v2`，
 *       register/unregister 是 CustomEvent，`detail = { token, definition }`。</li>
 *   <li><b>握手</b>：皮肤加载时**立即** register，并同时监听 ready；宿主挂载后 dispatch ready
 *       → 皮肤再 register 一次。**这样加载顺序无关**（无论是皮肤先还是宿主先）。</li>
 *   <li><b>释放</b>：`apply(null)` 表示交还所有它拥有的状态，必须幂等。</li>
 *   <li><b>visibility</b>：宿主为每个 `visibility-schedule` 设置算好"此刻到底该不该显示"，
 *       皮肤的 `apply` 直接读 `state.visibility[key]`（`!== false` 即视为显示）。</li>
 * </ul>
 */

// ---------------------------------------------------------------- 协议类型

export const LEGACY_PROTOCOL = 1;
export const CURRENT_PROTOCOL = 2;

type ProtocolVersion = typeof LEGACY_PROTOCOL | typeof CURRENT_PROTOCOL;

const EVENTS: Record<ProtocolVersion, { register: string; unregister: string; ready: string }> = {
  1: {
    register: 'dsh:skin-customization-register-v1',
    unregister: 'dsh:skin-customization-unregister-v1',
    ready: 'dsh:skin-customization-ready-v1',
  },
  2: {
    register: 'dsh:skin-customization-register-v2',
    unregister: 'dsh:skin-customization-unregister-v2',
    ready: 'dsh:skin-customization-ready-v2',
  },
};

export interface TimeRange {
  start: string;
  end: string;
}

export interface VisibilitySchedule {
  enabled: boolean;
  /** 时间段**之外**的可见性；时间段之内取其反。 */
  outside: 'visible' | 'hidden';
  ranges: TimeRange[];
}

export type SkinSettingValue = boolean | string | number | string[] | VisibilitySchedule;
export type SkinValues = Record<string, SkinSettingValue>;

export interface SkinSettingCondition {
  key: string;
  values: SkinSettingValue[];
}

/**
 * 至少满足其中一条即可显示。
 *
 * <p>{@code key}/{@code values} 是第一条的镜像，**必须存在** —— 老版本管理器直接读它们，
 * 否则会读到 undefined 并在渲染时抛错。所以我们在有 `anyOf` 时优先用它，没有就退回 `key/values`。</p>
 */
export interface SkinSettingConditionAnyOf extends SkinSettingCondition {
  anyOf: SkinSettingCondition[];
}

export type SkinSettingVisibility = SkinSettingCondition | SkinSettingConditionAnyOf;

export interface SelectOption {
  value: string;
  label: string;
  labelEn?: string;
}

interface SettingBase<T> {
  key: string;
  label: string;
  labelEn?: string;
  description?: string;
  descriptionEn?: string;
  defaultValue: T;
  disabledWhen?: string;
  visibleWhen?: SkinSettingVisibility;
  legacyValue?: { key: string; map: Record<string, T> };
}

export interface BooleanSetting extends SettingBase<boolean> {
  type: 'boolean';
}
export interface SelectSetting extends SettingBase<string> {
  type: 'select';
  options: SelectOption[];
}
export interface RangeSetting extends SettingBase<number> {
  type: 'range';
  min: number;
  max: number;
  step?: number;
  unit?: string;
}
export interface ColorSetting extends SettingBase<string> {
  type: 'color';
}
export interface CheckboxGroupSetting extends SettingBase<string[]> {
  type: 'checkbox-group';
  options: SelectOption[];
}
export interface VisibilityScheduleSetting extends SettingBase<VisibilitySchedule> {
  type: 'visibility-schedule';
}

export type SkinSetting =
  | BooleanSetting
  | SelectSetting
  | RangeSetting
  | ColorSetting
  | CheckboxGroupSetting
  | VisibilityScheduleSetting;

export interface SkinCustomizationState {
  values: SkinValues;
  /** 每个 `visibility-schedule` 设置"此刻是否可见"的结论。 */
  visibility: Record<string, boolean>;
}

export interface SkinCustomizationDefinition {
  protocol: ProtocolVersion;
  skinId: string;
  title: string;
  titleEn?: string;
  settings: SkinSetting[];
  /** null = 交还所有自定义状态（必须幂等）。 */
  apply(state: SkinCustomizationState | null): void;
}

interface Registration {
  token: object;
  definition: SkinCustomizationDefinition;
}

// ---------------------------------------------------------------- 宿主状态

/** 按 token 索引（皮肤卸载时会带同一个 token 来 unregister）。 */
const byToken = new Map<object, SkinCustomizationDefinition>();
/** 按 skinId 索引（界面按皮肤取）。 */
const bySkin = new Map<string, SkinCustomizationDefinition>();
const listeners = new Set<() => void>();

let installed = false;
let clockTimer: number | undefined;
/**
 * 收到过几次注册。
 *
 * <p>为什么要这个计数器：prod 构建会丢掉 `console.*`（drop_console），
 * 所以"皮肤到底喊没喊"不能靠翻日志，必须能被程序查询 —— 台架直接读它。</p>
 */
let registrationCount = 0;

function notify(): void {
  for (const fn of [...listeners]) {
    try {
      fn();
    } catch {
      /* 单个订阅者出错不影响其它 */
    }
  }
}

export function subscribeCustomization(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/**
 * 按 id 找定义。
 *
 * <p><b>为什么要做后缀匹配</b>：注册表是按皮肤**自报的** `skinId` 索引的（如 `orca-link`），
 * 而调用方手里通常是**市场 id**（如 `small-tailqwq.orca-link`）—— 市场 id 以前者结尾，
 * 这是市场里的实际命名约定。只按市场 id 精确查会永远查不到，
 * 表现为设置面板显示"这个皮肤没有声明设置项"，尽管皮肤明明注册过。</p>
 */
function resolveDef(id: string): SkinCustomizationDefinition | undefined {
  const exact = bySkin.get(id);
  if (exact) {
    return exact;
  }
  // 退化到后缀匹配时**必须取最长的那一个**，不能取"第一个满足的"。
  // 理由：`foo` 与 `barfoo` 都能被市场 id `b.barfoo` 以结尾匹配命中，
  // 而 `.find()` 按注册（加载）顺序返回**第一个**，于是面板会去管理**另一个皮肤**的设置，
  // 且改动会写进那个皮肤的存储键。皮肤装得越多、自报 id 越容易互为后缀，撞车概率越高。
  let best: SkinCustomizationDefinition | undefined;
  for (const d of bySkin.values()) {
    if (!d.skinId || !id.endsWith(d.skinId)) {
      continue;
    }
    if (!best || d.skinId.length > best.skinId.length) {
      best = d;
    }
  }
  return best;
}

/** 皮肤声明的定义（没有就返回 null）。 */
export function customizationOf(skinId: string): SkinCustomizationDefinition | null {
  return resolveDef(skinId) ?? null;
}

/** 所有已注册定义（界面用来列出"哪些皮肤有设置"）。 */
export function allCustomizations(): SkinCustomizationDefinition[] {
  return [...bySkin.values()];
}

// ---------------------------------------------------------------- 持久化

const prefsKey = (skinId: string) => `ap.skin.prefs.${skinId}`;

function readPrefs(skinId: string): Record<string, SkinSettingValue> {
  try {
    const raw = localStorage.getItem(prefsKey(skinId));
    return raw ? (JSON.parse(raw) as Record<string, SkinSettingValue>) : {};
  } catch {
    return {};
  }
}

function writePrefs(skinId: string, values: Record<string, SkinSettingValue>): void {
  try {
    localStorage.setItem(prefsKey(skinId), JSON.stringify(values));
  } catch {
    /* 存储不可用只影响记忆，不影响本次会话 */
  }
}

// ---------------------------------------------------------------- 取值 / 可见性

/** 默认值 + legacy 回退 + 已存值，合成皮肤真正该看到的 values。 */
export function resolveValues(def: SkinCustomizationDefinition): SkinValues {
  const stored = readPrefs(def.skinId);
  const out: SkinValues = {};
  for (const s of def.settings) {
    if (Object.prototype.hasOwnProperty.call(stored, s.key)) {
      out[s.key] = stored[s.key];
      continue;
    }
    // legacyValue：旧的映射键还在、且这个新键自己没存过值时，借旧键的值
    const legacy = s.legacyValue;
    if (legacy && Object.prototype.hasOwnProperty.call(stored, legacy.key)) {
      const mapped = legacy.map[String(stored[legacy.key])];
      if (mapped !== undefined) {
        out[s.key] = mapped;
        continue;
      }
    }
    out[s.key] = s.defaultValue;
  }
  return out;
}

/** 把 `"HH:MM"` 解析成当天分钟数；非法返回 null。 */
function minutesOf(hhmm: string): number | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(hhmm ?? '').trim());
  if (!m) {
    return null;
  }
  const h = Number(m[1]);
  const mi = Number(m[2]);
  if (h > 23 || mi > 59) {
    return null;
  }
  return h * 60 + mi;
}

/**
 * 时间段判定，支持跨零点（如 22:00–06:00）。
 * 非法区间一律视为"不在区间内"，宁可显示也不误藏。
 */
function inAnyRange(nowMinutes: number, ranges: TimeRange[]): boolean {
  for (const r of ranges ?? []) {
    const a = minutesOf(r.start);
    const b = minutesOf(r.end);
    if (a === null || b === null) {
      continue;
    }
    if (a === b) {
      continue; // 零长度区间无意义
    }
    if (a < b) {
      if (nowMinutes >= a && nowMinutes < b) {
        return true;
      }
    } else if (nowMinutes >= a || nowMinutes < b) {
      // 跨零点
      return true;
    }
  }
  return false;
}

/** 某个 visibility-schedule 此刻是否可见。 */
export function scheduleVisible(schedule: VisibilitySchedule, now = new Date()): boolean {
  if (!schedule || schedule.enabled !== true) {
    return true;
  }
  const inside = inAnyRange(now.getHours() * 60 + now.getMinutes(), schedule.ranges);
  // "outside 指的是时间段之外的可见性；时间段之内取其反"
  return inside ? schedule.outside === 'hidden' : schedule.outside === 'visible';
}

/** 为每个设置算出 visibility（schedule 类型真算，其余恒 true）。 */
function computeVisibility(def: SkinCustomizationDefinition, values: SkinValues, now = new Date()): Record<string, boolean> {
  const out: Record<string, boolean> = {};
  for (const s of def.settings) {
    if (s.type === 'visibility-schedule') {
      out[s.key] = scheduleVisible(values[s.key] as VisibilitySchedule, now);
    } else {
      out[s.key] = true;
    }
  }
  return out;
}

/** 某个控件此刻该不该渲染（`visibleWhen`）。 */
export function isSettingVisible(setting: SkinSetting, values: SkinValues): boolean {
  const cond = setting.visibleWhen;
  if (!cond) {
    return true;
  }
  const matches = (c: SkinSettingCondition) => c.values.some((v) => v === values[c.key]);
  const anyOf = (cond as SkinSettingConditionAnyOf).anyOf;
  if (Array.isArray(anyOf) && anyOf.length > 0) {
    return anyOf.some(matches);
  }
  return matches(cond);
}

/** 某个控件此刻该不该禁用（`disabledWhen` 指向的 boolean 为 true）。 */
export function isSettingDisabled(setting: SkinSetting, values: SkinValues): boolean {
  return setting.disabledWhen ? values[setting.disabledWhen] === true : false;
}

// ---------------------------------------------------------------- apply

/** 把当前 values/visibility 回调给皮肤。 */
export function applyDefinition(def: SkinCustomizationDefinition, now = new Date()): void {
  const values = resolveValues(def);
  try {
    def.apply({ values, visibility: computeVisibility(def, values, now) });
  } catch (e) {
    // 皮肤自己的 apply 抛错不应该拖垮平台
    // eslint-disable-next-line no-console
    console.warn(`[skin] ${def.skinId} 的 customization.apply 抛错：`, e);
  }
}

function releaseDefinition(def: SkinCustomizationDefinition): void {
  try {
    def.apply(null);
  } catch {
    /* ignore */
  }
}

/**
 * 改一个值：持久化 + 立刻重新 apply。
 *
 * <p><b>读写必须都归一到 `def.skinId`（皮肤自报的 id），不能用调用方传进来的 id。</b>
 * 调用方（设置面板）手里通常是**市场 id**（如 `small-tailqwq.maid-atelier`），
 * 而 {@link resolveDef} 是靠 `marketId.endsWith(def.skinId)` 兜底才找到定义的，
 * 真正参与取值的是 `def.skinId`（如 `maid-atelier`）。</p>
 *
 * <p>这里曾经直接写 `readPrefs(skinId)` / `writePrefs(skinId)`，于是**写进 A 键、
 * {@link resolveValues} 从 B 键读**：每次点开关值都正确落盘了，但面板重算时读的是另一个键，
 * 受控 `checked` 原样弹回 —— 用户看到的就是"开关点了没反应"。
 * 而 {@link customizedCount} 当时读的恰好是写入的那个键，所以它会显示"已自定义 N 项"，
 * **计数器和开关互相矛盾**，这个矛盾正是这条 bug 的特征。</p>
 */
export function setSkinSetting(skinId: string, key: string, value: SkinSettingValue): void {
  const def = resolveDef(skinId);
  if (!def) {
    return;
  }
  const stored = readPrefs(def.skinId);
  stored[key] = value;
  writePrefs(def.skinId, stored);
  // 自愈：旧版本把值误写到"市场 id"键上，那些键不会再被读，顺手清掉免得永久残留。
  // 安全：真正的键是 def.skinId，删掉另一个不会丢掉任何有效数据。
  if (skinId !== def.skinId) {
    try {
      localStorage.removeItem(prefsKey(skinId));
    } catch {
      /* ignore */
    }
  }
  applyDefinition(def);
  notify();
}

/** 重置为默认（清掉存储后重新 apply）。 */
export function resetSkinSettings(skinId: string): void {
  const def = resolveDef(skinId);
  // 存储键用的是皮肤**自报的** skinId（默认值解析与 apply 也用它），所以优先按它清
  try {
    localStorage.removeItem(prefsKey(def?.skinId ?? skinId));
    localStorage.removeItem(prefsKey(skinId));
  } catch {
    /* ignore */
  }
  if (def) {
    applyDefinition(def);
  }
  notify();
}

/**
 * 存储里有几条被用户改过（用于界面提示"已自定义"）。
 *
 * <p>同样要归一到 `def.skinId` —— 否则这个数会和面板里控件的实际取值**来自不同的键**：
 * 曾经表现为"已自定义 3 项"却一个开关都点不动（写入键 ≠ 读取键，见 {@link setSkinSetting}）。</p>
 */
export function customizedCount(skinId: string): number {
  const def = resolveDef(skinId);
  return Object.keys(readPrefs(def?.skinId ?? skinId)).length;
}

// ---------------------------------------------------------------- 安装

/**
 * 装宿主侧监听。**必须在任何皮肤脚本执行前调用**（重复调用无副作用）。
 *
 * <p>安装后立刻 dispatch 一次 `ready`，并且此后每当皮肤 register 我们就立刻 apply 一次 ——
 * 这样"皮肤先加载"和"宿主先挂载"两种情况都能收敛到同一状态。</p>
 */
export function installCustomizationHost(): void {
  if (installed) {
    return;
  }
  installed = true;
  const w = window;
  // eslint-disable-next-line no-console
  console.info('[skin:customization] 宿主侧监听已安装');

  for (const v of [LEGACY_PROTOCOL, CURRENT_PROTOCOL] as ProtocolVersion[]) {
    const ev = EVENTS[v];
    w.addEventListener(ev.register, ((e: Event) => {
      const detail = (e as CustomEvent<Registration>).detail;
      if (!detail?.definition || !detail.token) {
        return;
      }
      const def = detail.definition;
      byToken.set(detail.token, def);
      bySkin.set(def.skinId, def);
      registrationCount += 1;
      // 注册即应用一次：皮肤声明的默认值/已存值立刻生效
      applyDefinition(def);
      notify();
    }) as EventListener);

    w.addEventListener(ev.unregister, ((e: Event) => {
      const detail = (e as CustomEvent<Registration>).detail;
      if (!detail?.token) {
        return;
      }
      const def = byToken.get(detail.token);
      byToken.delete(detail.token);
      if (def && bySkin.get(def.skinId) === def) {
        bySkin.delete(def.skinId);
      }
      notify();
    }) as EventListener);
  }

  // "宿主已就绪"握手：皮肤收到后会再 register 一次，解决加载顺序问题
  const announce = () => {
    for (const v of [LEGACY_PROTOCOL, CURRENT_PROTOCOL] as ProtocolVersion[]) {
      try {
        w.dispatchEvent(new Event(EVENTS[v].ready));
      } catch {
        /* ignore */
      }
    }
  };
  announce();

  // visibility-schedule 依赖"当前时间"，所以每分钟重算一次并重新 apply
  clockTimer = window.setInterval(() => {
    for (const def of bySkin.values()) {
      applyDefinition(def);
    }
  }, 60_000);

  // 换页/刷新前清掉定时器
  window.addEventListener('beforeunload', () => {
    if (clockTimer !== undefined) {
      window.clearInterval(clockTimer);
    }
  });

  /*
   * 只读诊断钩子：把注册表快照挂到 window 上。
   * 用途：自动化台架要能直接问"协议到底注册上没有"，而不是靠观察副作用反推；
   * 排查用户问题时也能在控制台一句话看清。**只读，不对外提供写入口。**
   */
  (window as unknown as Record<string, unknown>).__apSkinCustomization = () => ({
    installed,
    registrationCount,
    skins: [...bySkin.keys()],
    definitions: [...bySkin.values()].map((d) => ({
      skinId: d.skinId,
      protocol: d.protocol,
      title: d.title,
      settings: d.settings.map((s) => ({ key: s.key, type: s.type, label: s.label, defaultValue: s.defaultValue })),
    })),
  });
}

/**
 * 皮肤被卸载时由 runtime 调用：把它的自定义状态交还回去。
 *
 * <p><b>为什么收一串候选 id</b>：注册表是按皮肤**自报的** `skinId` 索引的（如 `orca-link`），
 * 而 runtime 手里通常是**市场 id**（如 `small-tailqwq.orca-link`）—— 两者不同名。
 * 只传市场 id 会导致这里永远查不到、静默失效（曾经就是这样）。</p>
 *
 * <p>兜底而已：皮肤自己的 disposer 已经会 `apply(null)` 并 dispatch unregister，
 * 那条路是按 token 精确匹配的，与命名无关。</p>
 */
export function releaseCustomizationOf(...candidates: string[]): void {
  const wanted = candidates.map((c) => String(c ?? '')).filter(Boolean);
  if (wanted.length === 0) {
    return;
  }
  // 先精确命中；再退一步：市场 id 以皮肤自报 skinId 结尾，是市场里的实际命名约定。
  // 退化匹配同样取**最长**的（理由见 resolveDef 的注释），否则会释放掉另一个皮肤的状态。
  const exact = wanted.map((c) => bySkin.get(c)).find((d) => d !== undefined);
  let best: SkinCustomizationDefinition | undefined;
  if (exact === undefined) {
    for (const d of bySkin.values()) {
      if (!d.skinId || !wanted.some((c) => c === d.skinId || c.endsWith(d.skinId))) {
        continue;
      }
      if (!best || d.skinId.length > best.skinId.length) {
        best = d;
      }
    }
  }
  const def = exact ?? best;
  if (!def) {
    return;
  }
  releaseDefinition(def);
  bySkin.delete(def.skinId);
  for (const [token, d] of [...byToken.entries()]) {
    if (d === def) {
      byToken.delete(token);
    }
  }
  notify();
}
