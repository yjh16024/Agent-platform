package com.agentplatform.core.plugin.builtin;

import com.agentplatform.core.model.balance.ModelBalanceService;
import com.agentplatform.core.model.balance.ModelBalanceView;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginDescriptor;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 内置插件：<b>账户余额查询</b>。
 *
 * <h3>它补的是"钱快没了却不知道"这个缺口</h3>
 * 模型调用在余额耗尽时会直接开始报错，而用户在此之前<b>没有任何提示</b> ——
 * 只有主动去「模型设置」页看一眼才知道。给它一个工具之后，用户可以直接问
 * "我的额度还剩多少"，也能让智能体在长任务开始前自己先确认一下。
 *
 * <p>对标 dshmarket 上下载量最高的那类插件（{@code DeepSeek-Balance-Whale-Widget} 等，
 * 5.7 万下载）—— 它们的价值全在<b>数据</b>，那个"小鲸鱼挂件"只是展示方式。
 * 所以这里做成工具：不需要任何界面改动，模型就能把数字答出来。</p>
 *
 * <h3>★ 这是"内置插件能碰 core"的一个范例（也是边界）</h3>
 * 它直接注入了 {@link ModelBalanceService} —— 这<b>只有内置插件做得到</b>：
 * 它们的代码就在宿主里（同一个 Spring 容器），而外部 jar 插件只拿得到
 * {@code PluginContext}（agentId / tenantId / config / registrar），
 * 按设计<b>碰不到任何 core 服务</b>（那会让插件与宿主版本强耦合）。
 *
 * <p>所以：<b>要做"必须读平台内部状态"的插件，就做成内置插件。</b>
 * 反过来说，凡是能用 {@code ToolProvider} + 插件自己的 config 实现的能力，
 * 都应该留在外部插件那一侧 —— 那条路对第三方开放且不影响宿主升级。</p>
 *
 * <h3>⚠️ 覆盖范围有限，且必须如实说明</h3>
 * 余额接口是厂商各自开放的，当前平台只接了 <b>deepseek / moonshot / kimi</b> 三家；
 * 其余（OpenAI / 通义 / 智谱 / <b>硅基流动</b>…）要么从未提供、要么已下线。
 * <b>硅基流动已于 2026-08-14 下线该接口</b>，这是本次调查中最典型的例子 ——
 * 工具必须把"查不到"与"不支持查"区分清楚，否则用户会以为是网络问题而反复重试。
 * {@link ModelBalanceView#message()} 里带着现成的说明，直接透出即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinBalancePlugin implements ToolProvider, PluginDescriptor {

    /** 与 plugin_def 中的 plugin_id 一致。 */
    public static final String PLUGIN_ID = "builtin_balance";

    public static final String TOOL_NAME = "get_balance";

    private final ModelBalanceService balanceService;

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
        return "余额查询";
    }

    @Override
    public String description() {
        return "给智能体一个 get_balance 工具，可以直接回答「我的额度还剩多少」。"
                + "数据实时取自厂商的余额接口；当前 deepseek / moonshot / kimi 支持查询，"
                + "其余服务商（含硅基流动，其接口已于 2026-08-14 下线）会明确说明原因。"
                + "需在对话页打开「工具」开关。";
    }

    /*
     * 生命周期：本插件**无状态**，所以两个回调都不维护任何东西。
     *
     * 余额是实时查询的（见 handleQuery 的说明：刻意不缓存），插件里没有按 agentId 存的配置
     * —— 这一点与 BuiltinTtsPlugin 不同，那个必须缓存（API Key 与音色等信息要留着用）。
     * 但仍然实现这两个方法：它们是 Plugin 契约的必填项，且挂载日志对排查
     * "插件到底挂上没有"很有用（这是最常见的一类疑问）。
     */
    @Override
    public void onAttach(PluginContext ctx) {
        log.info("[balance] 已挂载到 agent={}（无状态，余额实时查询）", ctx.agentId());
    }

    @Override
    public void onDetach(PluginContext ctx) {
        log.info("[balance] 已从 agent={} 卸载", ctx.agentId());
    }

    @Override
    public List<PluginTool> provideTools() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        // 无参数：用户问"还剩多少钱"时，模型不需要决定任何东西
        schema.putObject("properties");

        return List.of(new PluginTool(TOOL_NAME,
                "查询当前模型账户的余额与额度。当用户问「还剩多少钱」「额度够不够」"
                        + "或准备执行长任务前想确认余量时使用。返回各绑定的可用余额与明细。",
                schema,
                this::handleQuery));
    }

    /**
     * 工具入口。
     *
     * <p>这里<b>不缓存</b>：余额本就是实时数据，缓存会让"刚充值完还是查不到"变成一个新的困惑来源。
     * 查询本身很快（一次 GET），且用户不会频繁问。</p>
     */
    private JsonNode handleQuery(JsonNode args, PluginContext ctx) {
        try {
            List<ModelBalanceView> views = balanceService.queryAll();
            return JsonNodeFactory.instance.textNode(render(views));
        } catch (Exception e) {
            // queryAll() 承诺永不抛异常，这里是最后一道兜底 —— 工具不该把整轮对话带崩
            log.warn("[balance] 查询异常：{}", e.getMessage());
            throw new IllegalStateException("余额查询失败：" + e.getMessage()
                    + "。可以告知用户到「模型设置」页查看额度。");
        }
    }

    /**
     * 渲染成模型可直接引用的文本。
     *
     * <p>四种状态分开表达（未配置 / 不支持 / 查询失败 / 成功）——
     * 它们对用户的含义完全不同，混成一句"查询失败"会让人去排查根本不存在的问题。</p>
     */
    static String render(List<ModelBalanceView> views) {
        if (views == null || views.isEmpty()) {
            return "（没有可查询的模型绑定。请到「模型设置」页配置对话模型后再试。）";
        }
        StringBuilder sb = new StringBuilder("模型账户余额：\n\n");
        boolean anyOk = false;

        for (ModelBalanceView v : views) {
            String label = bindingLabel(v.binding());
            sb.append("【").append(label).append("】");
            if (v.model() != null && !v.model().isBlank()) {
                sb.append(" ").append(v.model());
            }
            if (v.provider() != null && !v.provider().isBlank()) {
                sb.append("（").append(v.provider()).append("）");
            }
            sb.append('\n');

            if (!v.configured()) {
                // 「未配置」是最需要给出去路的一支：用户什么都不用排查，去配一下就好
                sb.append("  未配置 —— 该绑定还没有选择服务商与模型。\n");
                sb.append("  请到「模型设置」页配置后再查询。\n\n");
                continue;
            }
            if (!v.supported()) {
                // 这一支最容易被误当成故障，所以把厂商侧的说明原样带出来
                sb.append("  无法查询 —— ").append(blankTo(v.message(), "该服务商未提供余额查询接口")).append('\n');
                sb.append("  请到对应服务商的控制台查看额度。\n\n");
                continue;
            }
            if (!v.ok()) {
                sb.append("  查询失败 —— ").append(blankTo(v.message(), "原因未知")).append('\n');
                sb.append("  可稍后重试；若持续失败请检查密钥是否有效。\n\n");
                continue;
            }

            anyOk = true;
            sb.append("  可用余额：").append(blankTo(v.available(), "未知"));
            if (v.currency() != null && !v.currency().isBlank()) {
                sb.append(' ').append(v.currency());
            }
            sb.append('\n');
            if (v.items() != null) {
                for (ModelBalanceView.Item item : v.items()) {
                    if (item == null || item.label() == null) {
                        continue;
                    }
                    sb.append("    · ").append(item.label()).append("：")
                            .append(blankTo(item.value(), "-")).append('\n');
                }
            }
            sb.append('\n');
        }

        if (anyOk) {
            sb.append("（数据实时取自厂商接口。回答用户时请直接给出数字与币种，不要补充推测。）");
        }
        return sb.toString();
    }

    private static String bindingLabel(String binding) {
        if ("chat".equalsIgnoreCase(binding)) {
            return "对话模型";
        }
        if ("embedding".equalsIgnoreCase(binding)) {
            return "嵌入模型";
        }
        return blankTo(binding, "模型");
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
