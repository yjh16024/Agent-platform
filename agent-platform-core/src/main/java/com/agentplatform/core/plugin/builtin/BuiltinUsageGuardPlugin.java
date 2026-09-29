package com.agentplatform.core.plugin.builtin;

import com.agentplatform.core.multimodal.QuotaService;
import com.agentplatform.plugin.sdk.ConfigFieldDef;
import com.agentplatform.plugin.sdk.EventSubscriber;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginDescriptor;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import com.agentplatform.plugin.sdk.model.DomainEvent;
import com.agentplatform.plugin.sdk.model.EventTypes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置插件：<b>用量统计与阈值预警</b>。
 *
 * <h3>它补两个缺口</h3>
 * <ol>
 *   <li><b>看得到</b>：给智能体一个 {@code get_usage} 工具，用户可以直接问"今天用了多少"，
 *       不必去管理页翻。</li>
 *   <li><b>提前知道</b>：配额被撞满时平台会<b>直接拦掉业务</b>（{@code QUOTA_EXCEEDED}）——
 *       那时已经晚了。本插件在用到 {@code warnPercent}（默认 80%）时主动推一条消息，
 *       留出调整的余地。</li>
 * </ol>
 *
 * <h3>★ 它顺带补上了"token 配额没人记账"这个缺口</h3>
 * {@code QuotaService} 早就声明了 {@code tokens} 类型（默认 1 亿），但全仓<b>没有任何地方递增它</b>——
 * 因为它的 {@code checkAndIncrement} 每次只加 1，计不了 token 这种"一次消耗很多"的量。
 * 本次给它加了 {@code recordUsage(tenantId, type, amount)}，本插件是第一个使用者。
 *
 * <p>于是分工变得清楚：<b>{@code model_calls} 由核心在每轮开始时计</b>（进入前的闸门），
 * <b>{@code tokens} 由本插件在每轮结束时记账</b>（事后的实际消耗）。</p>
 *
 * <h3>⚠️ 两个诚实性说明</h3>
 * <ul>
 *   <li><b>流式链路此前拿不到 token</b>（usage 恒为 0）。已在 2026-09-29 修好：
 *       {@code SpringAiModelAdapter.stream} 现在把累积用量挂在 done 帧上，
 *       {@code AgentRuntimeService} 从中取值再发事件。但如果上游网关在流式下不返回 usage，
 *       这里记到的仍是 0 —— 那是厂商行为，平台无法控制。</li>
 *   <li><b>事件是 agent 级的</b>，而配额是租户级的。所以本插件把同一个租户下所有智能体的消耗
 *       汇总到一处 —— 这正是想要的（配额本来就按租户算）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinUsageGuardPlugin implements EventSubscriber, ToolProvider, PluginDescriptor {

    /** 与 plugin_def 中的 plugin_id 一致。 */
    public static final String PLUGIN_ID = "builtin_usage_guard";

    public static final String TOOL_NAME = "get_usage";

    /** 配额类型（与 QuotaService 的 DEFAULT_QUOTA 键一致）。 */
    static final String QUOTA_TOKEN = "tokens";
    static final String QUOTA_MODEL_CALLS = "model_calls";

    // ---------------- 配置键（与前端表单字段一致） ----------------

    public static final String CFG_CHANNEL = "channel";
    public static final String CFG_WEBHOOK_URL = "webhookUrl";
    public static final String CFG_SECRET = "secret";
    public static final String CFG_WARN_PERCENT = "warnPercent";
    public static final String CFG_TIMEOUT_MS = "timeoutMs";

    private static final int DEFAULT_WARN_PERCENT = 80;

    private final QuotaService quotaService;

    /** 按 agentId 隔离配置（插件是单例；理由同 BuiltinTtsPlugin）。 */
    private final Map<String, GuardConfig> configByAgent = new ConcurrentHashMap<>();

    /**
     * 预警去重：租户 → 上次预警的日期。
     *
     * <p>配额是<b>租户级</b>的（多个智能体共享一份），所以按键是租户而不是 agentId ——
     * 否则同一租户下挂了三台智能体，就会在同一个阈值上收到三条一模一样的预警。
     * 每天最多提醒一次：到了 80% 之后每一轮都会满足条件，不设闸门会把群刷屏。</p>
     */
    private final Map<String, LocalDate> lastWarnedOn = new ConcurrentHashMap<>();

    // ---------------- Plugin ----------------

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String name() {
        return "用量统计与预警";
    }

    @Override
    public String description() {
        return "统计模型调用与 token 消耗（按租户累计），在用到配额的 warnPercent（默认 80%）时"
                + "推送到飞书/钉钉/企微/Slack 群，避免额度耗尽后业务被直接拦掉。"
                + "同时给智能体一个 get_usage 工具，可直接回答「今天用了多少」。"
                + "填了 webhook 才会推送；不填也能用工具查用量。";
                }

                /**
                * 配置项声明（键名与 {@link GuardConfig#of} 读取的完全一致）。
                *
                * <p>与告警插件一样，webhook 不标必填 —— 不填时它仍然提供 {@code get_usage} 工具，
                * 只是不做推送。界面上要让人看出"不填也能用"。</p>
                */
                @Override
                public List<ConfigFieldDef> configFields() {
                return List.of(
                ConfigFieldDef.select(CFG_CHANNEL, "渠道", List.of(
                              WebhookPusher.CHANNEL_FEISHU, WebhookPusher.CHANNEL_DINGTALK,
                              WebhookPusher.CHANNEL_WECOM, WebhookPusher.CHANNEL_SLACK,
                              WebhookPusher.CHANNEL_GENERIC))
                      .hint("与告警推送用的是同一套渠道适配；选错的表现是「配了但收不到」"),
                ConfigFieldDef.text(CFG_WEBHOOK_URL, "Webhook 地址")
                      .ph("https://open.feishu.cn/open-apis/bot/v2/hook/...")
                      .hint("不填也能用：此时只统计、不推送"),
                ConfigFieldDef.secret(CFG_SECRET, "加签密钥")
                      .hint("可选。只有钉钉机器人在开启「加签」时才需要"),
                ConfigFieldDef.number(CFG_WARN_PERCENT, "预警阈值（%）", 10, 100)
                      .def(String.valueOf(DEFAULT_WARN_PERCENT))
                      .hint("用到配额的百分之多少时提醒。到 100% 平台会直接拦掉调用，所以建议留出余量"),
                ConfigFieldDef.number(CFG_TIMEOUT_MS, "超时（毫秒）", 500, 30_000)
                      .def(String.valueOf(WebhookPusher.DEFAULT_TIMEOUT_MS)));
                }

                @Override
                public void onAttach(PluginContext ctx) {
        GuardConfig cfg = GuardConfig.of(ctx.config());
        configByAgent.put(ctx.agentId(), cfg);
        log.info("[usage-guard] 已挂载到 agent={} 已配置 webhook={} 预警阈值={}%",
                ctx.agentId(), cfg.hasWebhook(), cfg.warnPercent());
    }

    @Override
    public void onDetach(PluginContext ctx) {
        configByAgent.remove(ctx.agentId());
    }

    // ---------------- 事件订阅 ----------------

    @Override
    public Set<String> eventTypes() {
        return Set.of(EventTypes.AGENT_RUN_COMPLETED);
    }

    /**
     * 一轮对话完成后记账 + 检查阈值。
     *
     * <p>在事件的发布线程上同步执行（一次内存/Redis 递增，通常还有一次 webhook）。
     * 不投异步是有意的：投了之后"记账失败"的日志会脱离事件上下文，而记账丢失是静默的 ——
     * 没有日志就永远发现不了。</p>
     */
    @Override
    public void onEvent(DomainEvent event) {
        if (event == null || !EventTypes.AGENT_RUN_COMPLETED.equals(event.type())) {
            return;
        }
        String agentId = event.agentId();
        GuardConfig cfg = agentId == null ? null : configByAgent.get(agentId);
        if (cfg == null) {
            return;   // 这台智能体没挂本插件
        }
        String tenantId = event.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }

        long prompt = event.number("prompt_tokens", 0);
        long completion = event.number("completion_tokens", 0);
        long tokens = prompt + completion;

        if (tokens > 0) {
            try {
                long total = quotaService.recordUsage(tenantId, QUOTA_TOKEN, tokens);
                log.debug("[usage-guard] 记账 tenant={} 本轮={} 累计={}", tenantId, tokens, total);
            } catch (Exception e) {
                // 记账失败不该影响事件派发的其它订阅者；但必须留痕（否则统计会静默偏低）
                log.warn("[usage-guard] 用量记账失败 tenant={} tokens={} error={}",
                        tenantId, tokens, e.getMessage());
            }
        }

        checkThreshold(tenantId, cfg);
    }

    /**
     * 检查是否达到预警线并推送。
     *
     * <p>遍历配额清单而不是只看 token：{@code model_calls} 同样会撞满并拦掉业务，
     * 而它由核心侧独立计数，本插件只是**读**它。</p>
     */
    private void checkThreshold(String tenantId, GuardConfig cfg) {
        if (!cfg.hasWebhook()) {
            return;   // 没配 webhook：静默跳过（工具仍然可用）
        }
        List<Map<String, Object>> quotas;
        try {
            quotas = quotaService.list(tenantId);
        } catch (Exception e) {
            log.debug("[usage-guard] 读取配额失败（忽略）：{}", e.getMessage());
            return;
        }

        StringBuilder hit = new StringBuilder();
        for (Map<String, Object> row : quotas) {
            String type = row.get("quotaType") == null ? null : String.valueOf(row.get("quotaType"));
            long limit = asLong(row.get("limit"), -1);
            long used = asLong(row.get("used"), 0);
            if (type == null || limit <= 0) {
                continue;   // 无上限（Long.MAX_VALUE 之类的兜底）不预警
            }
            int percent = (int) Math.min(100, used * 100 / limit);
            if (percent >= cfg.warnPercent()) {
                hit.append("  · ").append(labelOf(type)).append('：')
                        .append(used).append(" / ").append(limit)
                        .append("（").append(percent).append("%）\n");
            }
        }
        if (hit.isEmpty()) {
            // 回落到阈值以下时清掉标记，这样下一轮再冲高还能再提醒一次
            lastWarnedOn.remove(tenantId);
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate warned = lastWarnedOn.get(tenantId);
        if (today.equals(warned)) {
            return;   // 今天已经提醒过：到了阈值之后每轮都满足，不设闸门会把群刷屏
        }
        // 先占位再推送：并发下宁可漏一条，也不要重复刷屏
        lastWarnedOn.put(tenantId, today);

        String text = "⚠️ 模型配额接近上限\n\n租户：" + tenantId + "\n" + hit
                + "\n说明：token 用量由本轮对话结束时累计；model_calls 由平台在每轮开始时计数。\n"
                + "建议到「配额」页调整上限，或检查是否有异常调用。";
        WebhookPusher.push(new WebhookPusher.Config(
                cfg.channel(), cfg.webhookUrl(), cfg.secret(), cfg.timeoutMs()),
                text, "usage-guard:threshold tenant=" + tenantId);
    }

    // ---------------- 工具 ----------------

    @Override
    public List<PluginTool> provideTools() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties");   // 无参数

        return List.of(new PluginTool(TOOL_NAME,
                "查询本租户今日的模型用量与配额（模型调用次数、token 消耗、各自占比）。"
                        + "当用户问「今天用了多少」「额度还剩多少」时使用（若同时挂了余额插件，"
                        + "额度余额请用 get_balance，本工具给的是用量与配额）。",
                schema,
                this::handleQuery));
    }

    private JsonNode handleQuery(JsonNode args, PluginContext ctx) {
        String tenantId = ctx == null ? null : ctx.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("无法确定当前租户，暂时查不到用量");
        }
        try {
            return JsonNodeFactory.instance.textNode(render(tenantId, quotaService.list(tenantId)));
        } catch (Exception e) {
            log.warn("[usage-guard] 查询用量失败 tenant={} error={}", tenantId, e.getMessage());
            throw new IllegalStateException("查询用量失败：" + e.getMessage());
        }
    }

    /** 渲染成模型可直接引用的文本（包级可见以便测试）。 */
    static String render(String tenantId, List<Map<String, Object>> quotas) {
        StringBuilder sb = new StringBuilder("今日用量（周期：每日）：\n\n");
        boolean any = false;
        if (quotas != null) {
            for (Map<String, Object> row : quotas) {
                String type = row.get("quotaType") == null ? null : String.valueOf(row.get("quotaType"));
                if (type == null) {
                    continue;
                }
                long limit = asLong(row.get("limit"), -1);
                long used = asLong(row.get("used"), 0);
                any = true;
                sb.append("  · ").append(labelOf(type)).append('：').append(used);
                if (limit > 0 && limit != Long.MAX_VALUE) {
                    sb.append(" / ").append(limit)
                            .append("（").append(Math.min(100, used * 100 / limit)).append("%）");
                } else {
                    sb.append("（未设上限）");
                }
                sb.append('\n');
            }
        }
        if (!any) {
            return "（暂时没有可用的用量数据。）";
        }
        sb.append('\n').append("（用量按租户累计，跨该租户下所有智能体；")
                .append("token 由每轮对话结束时记账，模型调用次数由平台在每轮开始时计数。）");
        return sb.toString();
    }

    private static String labelOf(String quotaType) {
        return switch (quotaType) {
            case QUOTA_TOKEN -> "Token 消耗";
            case QUOTA_MODEL_CALLS -> "模型调用";
            case "files" -> "文件数";
            case "plugins" -> "插件数";
            default -> quotaType;
        };
    }

    private static long asLong(Object v, long fallback) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? fallback : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---------------- 配置 ----------------

    /**
     * 一次挂载的预警配置（不可变）。
     *
     * @param channel     渠道（决定 body 形状与是否加签）
     * @param webhookUrl  群机器人地址；为空则只统计不推送
     * @param secret      钉钉加签密钥（可选）
     * @param warnPercent 达到配额的百分之多少时预警（默认 80）
     * @param timeoutMs   单次推送超时
     */
    public record GuardConfig(String channel, String webhookUrl, String secret,
                              int warnPercent, int timeoutMs) {

        static GuardConfig defaults() {
            return new GuardConfig(WebhookPusher.CHANNEL_GENERIC, null, null,
                    DEFAULT_WARN_PERCENT, WebhookPusher.DEFAULT_TIMEOUT_MS);
        }

        public static GuardConfig of(JsonNode config) {
            if (config == null || config.isNull()) {
                return defaults();
            }
            int percent = (int) clamp(num(config, CFG_WARN_PERCENT, DEFAULT_WARN_PERCENT), 10, 100);
            return new GuardConfig(
                    blankToDefault(str(config, CFG_CHANNEL), WebhookPusher.CHANNEL_GENERIC),
                    blankToNull(str(config, CFG_WEBHOOK_URL)),
                    blankToNull(str(config, CFG_SECRET)),
                    percent,
                    (int) clamp(num(config, CFG_TIMEOUT_MS, WebhookPusher.DEFAULT_TIMEOUT_MS),
                            500, 30_000));
        }

        public boolean hasWebhook() {
            return webhookUrl != null && !webhookUrl.isBlank();
        }

        private static String str(JsonNode node, String key) {
            JsonNode v = node.path(key);
            return v.isMissingNode() || v.isNull() ? null : v.asText();
        }

        private static double num(JsonNode node, String key, double fallback) {
            JsonNode v = node.path(key);
            if (v.isMissingNode() || v.isNull()) {
                return fallback;
            }
            double d = v.asDouble(fallback);
            return Double.isFinite(d) ? d : fallback;
        }

        private static String blankToNull(String v) {
            return v == null || v.isBlank() ? null : v.trim();
        }

        private static String blankToDefault(String v, String fallback) {
            return v == null || v.isBlank() ? fallback : v.trim();
        }

        private static double clamp(double v, double min, double max) {
            return Math.max(min, Math.min(max, v));
        }
    }
}
