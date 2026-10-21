package com.agentplatform.core.settings;

import java.util.List;

/**
 * 平台设置项注册表 —— **「哪些项可以改、怎么改」的唯一权威来源**。
 *
 * <h3>它是什么 / 不是什么</h3>
 * <ul>
 *   <li><b>是</b>：每个设置项的元数据（键名、分组、标签、说明、类型、是否需要重启）；
 *       「平台设置」页的展示与校验全部由它驱动，页面不硬编码任何一项；</li>
 *   <li><b>不是</b>：值本身。值有两层来源，见 {@link PlatformSettingService} ——
 *       配置文件 / 环境变量是「基础值」，本表对应的覆盖文件是「页面改过的那部分」。</li>
 * </ul>
 *
 * <h3>★ 为什么"需重启"是一个显式字段，而不是注释</h3>
 * 这里的项分两类，读法完全不同（这是本功能的全部技术前提）：
 * <ol>
 *   <li><b>运行期读取</b>（{@code restartRequired=false}）：目标组件在**每次执行时**才读这个值
 *       （如 {@code UserFactExtractor.extractAsync} 开头判 {@code enabled}）。
 *       写入后立即生效，不需要任何装配动作 —— 这是"热切换"能成立的原因；</li>
 *   <li><b>装配期读取</b>（{@code restartRequired=true}）：目标组件是
 *       {@code @ConditionalOnProperty} 的 Bean 装配条件、或构造器注入的 final 字段。
 *       Spring 只在**容器启动时**读一次 —— 写入后必须重启进程，
 *       由 {@link PlatformSettingsInitializer} 在启动早期把文件值注入 Environment 才会生效。</li>
 * </ol>
 * 把两者混在一起展示、却不告诉用户"哪个要重启"，是这类设置页最常见的设计缺陷 ——
 * 用户改完没生效，会以为功能坏了。所以 {@code restartRequired} 必须随项声明、
 * 由界面以徽标展示，并在保存后弹窗告知。
 *
 * <h3>安全边界（刻意不放上来的那些）</h3>
 * 鉴权开关（{@code security.enabled} / {@code rbac.enabled}）、密钥（{@code jwt.secret} /
 * {@code secrets.model-key}）、MCP 命令白名单、凭证隔离开关（{@code protected-paths.enabled}）等
 * **不进入本表**：它们要么是安全语义开关（热切会让在线会话瞬间失效），要么改错了就没有回头路
 * （密钥类），要么本身就是"防误操作"的闸门。页面只做**可以安全调整的部署/功能形态**。
 */
public final class PlatformSettingRegistry {

    // ------------------------------------------------------------------ 键名常量
    // 单一来源：读点（UserFactExtractor 等）与注册表都引用这里，避免字符串拼错后静默失效
    // （拼错的表现为"开关点了没反应"，且编译期无任何提示）。

    public static final String MEMORY_AUTO_PROFILE_ENABLED = "agent-platform.memory.auto-profile.enabled";
    public static final String MEMORY_VECTOR_ENABLED = "agent-platform.memory.vector.enabled";
    public static final String WORKSPACE_ENABLED = "agent-platform.agent.workspace.enabled";
    public static final String TOOL_ACTIONS_ENABLED = "agent-platform.tool.actions.enabled";
    public static final String TOOL_RATE_LIMIT_ENABLED = "agent-platform.tool.rate-limit.enabled";
    public static final String TOOL_RATE_LIMIT_PER_MINUTE = "agent-platform.tool.rate-limit.per-minute";
    public static final String TOOL_DISABLED = "agent-platform.tool.disabled";
    public static final String AUDIT_ENABLED = "agent-platform.audit.enabled";
    public static final String SPRING_AI_ENABLED = "agent-platform.springai.enabled";
    public static final String SPRING_AI_RAG_ENABLED = "agent-platform.springai.rag.enabled";
    public static final String RAG_VECTOR_STORE = "agent-platform.rag.vector-store";
    public static final String STORAGE_TYPE = "agent-platform.storage.type";
    public static final String EVENTS_ENABLED = "agent-platform.events.enabled";

    /** 值的类型 —— 决定界面用哪种控件、写入时做哪种校验。 */
    public enum Type {
        BOOL, ENUM, NUMBER, LIST,
        /** 多行文本（2026-10-20 加）：目前用于「输出格式」指引这类可编辑的提示词片段。 */
        TEXT
    }

    /**
     * 「输出格式」指引的键（2026-10-20）。
     *
     * <p>平台会把它拼进每轮系统提示词的末尾。**之所以做成设置项**：这段文字直接决定
     * 模型输出的风格（用不用 Markdown、要不要画图），用户有权知道它存在、看清内容、
     * 也应当能改 —— 藏在代码里等于"平台偷偷往你的提示词里加东西"。</p>
     */
    public static final String PROMPT_RENDER_GUIDE = "agent-platform.prompt.render-guide";

    /** 默认值：与原先硬编码在 {@code AgentRuntimeService.withRenderGuide} 里的内容逐字一致。 */
    public static final String DEFAULT_RENDER_GUIDE = """
            ## 输出格式

            你的回复会按 Markdown 渲染，可以正常使用标题、有序/无序列表、表格、**粗体**、
            行内代码与代码块 —— 用它们把内容组织清楚，不要写成一大段纯文本。

            当需要展示**数据对比或趋势**（而不是罗列文字）时，用 chart 代码块，界面会画成图：

            ```chart
            { "type": "bar", "title": "各渠道询盘量",
              "data": [ {"label": "阿里巴巴", "value": 120}, {"label": "独立站", "value": 86} ] }
            ```

            - `type`：`bar`（柱状，默认）/ `line`（折线，适合趋势）/ `pie`（占比）
            - `data`：每项是 `{"label": "名称", "value": 数字}`，至少一项
            - 只在**确实有数据**要展示时才用；没有数据时不要为了排版好看而画图
            - ⚠️ 必须是合法 JSON（双引号，不能有注释或多余逗号），否则界面只能显示原文""";

    /**
     * 单个设置项的定义。
     *
     * @param key               Spring 属性名（与 application.yml / @Value 里的完全一致）
     * @param group             分组显示名（按 {@link #definitions()} 声明顺序渲染）
     * @param label             界面标签
     * @param description       说明（讲清"改了会发生什么"，含风险提示）
     * @param type              值类型
     * @param fallback          兜底默认值 —— 仅当配置文件与环境变量都没给这个键时使用
     *                          （取值与 application.yml / @Value 的默认一致；正常部署下用不到）
     * @param options           ENUM 的候选项（其他类型为 null）
     * @param restartRequired   写入后是否需要重启进程才生效（见类注释）
     * @param lockedReason      非 null 表示该环境**强制**该项（写入会被拒绝），字符串是给用户看的原因；
     *                          目前只有桌面（内置库）模式下的 Kafka 事件总线用到
     */
    public record SettingDef(String key, String group, String label, String description,
                             Type type, String fallback, List<String> options,
                             boolean restartRequired, String lockedReason) {
    }

    /** 桌面（内置库）模式下的锁定说明。 */
    static final String EMBEDDED_EVENTS_LOCK =
            "本机（内置库）模式强制关闭：单机没有 Kafka broker，桌面壳启动时已排除 Kafka 自动配置 —— "
                    + "打开它只会让启动变慢，不会真的收发事件。";

    private static final List<SettingDef> DEFS = List.of(

            // ---------------- 长期记忆（运行期读取：每轮对话时判断） ----------------
            new SettingDef(MEMORY_AUTO_PROFILE_ENABLED, "长期记忆", "用户画像自动抽取",
                    "每轮对话结束后异步识别关于用户的稳定事实，落成「待确认候选」，"
                            + "用户在「长期记忆」页逐条采纳后才写入正式画像（候选不参与对话）。"
                            + "抽取会额外调用一次模型（按「长期记忆」页所配模型计费）。",
                    Type.BOOL, "false", null, false, null),
            new SettingDef(MEMORY_VECTOR_ENABLED, "长期记忆", "跨会话语义召回",
                    "把历史消息索引进向量库，其它会话可按语义召回相关片段。"
                            + "未配置嵌入模型的部署可关掉，避免每轮对话都白跑一次 embed。",
                    Type.BOOL, "true", null, false, null),

            // ---------------- 文件与工具 ----------------
            new SettingDef(WORKSPACE_ENABLED, "文件与工具", "文件读写工具（fs_*）",
                    "关闭后所有 fs_* 工具直接拒绝执行。工作区根约束与凭证隔离仍然生效 —— "
                            + "这是「临时禁止改文件」，不是放开限制。",
                    Type.BOOL, "true", null, false, null),
            new SettingDef(TOOL_ACTIONS_ENABLED, "文件与工具", "项目动作集（git_* / run_tests）",
                    "git_status / git_diff / git_log 与按项目类型探测的 run_tests / run_build。"
                            + "动作在**启动时**注册，改动需重启后生效。",
                    Type.BOOL, "true", null, true, null),
            new SettingDef(TOOL_RATE_LIMIT_ENABLED, "文件与工具", "工具调用限流",
                    "按租户限制工具调用速率，防止模型陷入调用循环时，"
                            + "把一次对话放大成几十次外部请求（网络 / 子进程 / 文件扫描）。",
                    Type.BOOL, "true", null, false, null),
            new SettingDef(TOOL_RATE_LIMIT_PER_MINUTE, "文件与工具", "限流阈值（次/分钟）",
                    "每租户每分钟的工具调用上限。默认 600 偏宽松是刻意的："
                            + "先拦「明显失控」，不给正常的多步任务制造摩擦（20 轮的编程任务正常就要几十次调用）。",
                    Type.NUMBER, "600", null, false, null),
            new SettingDef(TOOL_DISABLED, "文件与工具", "禁用工具清单",
                    "逗号分隔的工具名，命中的工具一律拒绝执行并明确告知「平台策略」。"
                            + "生产环境关掉危险工具用；留空表示不禁用任何工具。",
                    Type.LIST, "", null, false, null),

            // ---------------- 操作审计 ----------------
            new SettingDef(AUDIT_ENABLED, "操作审计", "写操作审计",
                    "自动记录全部写操作（谁 / 何时 / 对什么 / 做了什么 / 结果）。"
                            + "审计刻意不记录请求体；关闭后审计页不再产生新记录（历史保留）。",
                    Type.BOOL, "true", null, false, null),

            // ---------------- 模型、存储与事件（装配期读取：需重启） ----------------
            new SettingDef(SPRING_AI_ENABLED, "模型、存储与事件", "Spring AI 模型通道",
                    "模型调用 / 原生 tool-role 工具循环 / 可观测走 Spring AI 2.0.1（底层为官方厂商 SDK）。"
                            + "关闭后回退自研适配器；凭证三级回退与加密逻辑不变。需重启生效。",
                    Type.BOOL, "true", null, true, null),
            new SettingDef(SPRING_AI_RAG_ENABLED, "模型、存储与事件", "Spring AI 文档解析",
                    "RAG 的解析与切分走 Spring AI（TikaDocumentReader + TokenTextSplitter）；"
                            + "关闭后回退自研实现。混合检索本身不受此项影响。需重启生效。",
                    Type.BOOL, "true", null, true, null),
            new SettingDef(RAG_VECTOR_STORE, "模型、存储与事件", "向量库",
                    "in-memory 是本地演示实现（8 维伪向量，重启即丢）；milvus 接真实向量库（需 Milvus 可用）。"
                            + "切换后已有向量**不会迁移**，需重建索引 / 重新上传文档。需重启生效。",
                    Type.ENUM, "in-memory", List.of("in-memory", "milvus"), true, null),
            new SettingDef(STORAGE_TYPE, "模型、存储与事件", "文件存储",
                    "local 落本地磁盘 ./data/files；minio 接对象存储（需 MinIO 可用）。需重启生效。",
                    Type.ENUM, "local", List.of("local", "minio"), true, null),
            new SettingDef(EVENTS_ENABLED, "模型、存储与事件", "Kafka 事件总线",
                    "智能体配置变更广播与日志事件流。broker 不可用时发送失败静默降级，不阻断业务。"
                            + "需重启生效。",
                    Type.BOOL, "true", null, true, EMBEDDED_EVENTS_LOCK),

            // ---------------- 系统提示词（运行期读取：每轮对话组装时） ----------------
            new SettingDef(PROMPT_RENDER_GUIDE, "系统提示词", "「输出格式」指引",
                    "每轮对话都会把这段文字拼在系统提示词末尾，用来告诉模型「回复会被 Markdown 渲染，"
                            + "可以用标题/列表/表格/图表」。**纯文本渠道（QQ、短信、终端）不会注入它** —— "
                            + "请求里带 renderGuide=false 即可，否则模型会输出一堆 `**` `-` `|` 符号。"
                            + "留空 = 完全不注入。",
                    Type.TEXT, DEFAULT_RENDER_GUIDE, null, false, null)
    );

    private PlatformSettingRegistry() {
    }

    /** 全部设置项（声明顺序 = 界面渲染顺序）。 */
    public static List<SettingDef> definitions() {
        return DEFS;
    }

    /** 按键查定义；不存在返回 null。 */
    public static SettingDef find(String key) {
        if (key == null) {
            return null;
        }
        for (SettingDef def : DEFS) {
            if (def.key().equals(key)) {
                return def;
            }
        }
        return null;
    }
}
