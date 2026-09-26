package com.agentplatform.core.memory;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.model.enums.UserFactCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 用户画像的**自动抽取**（从一轮对话里识别关于用户的稳定事实，产出候选待用户确认）。
 *
 * <h3>它在整条链路里的位置</h3>
 * <pre>
 *   一轮对话结束
 *     → extractAsync(...)              ← 本类：fire-and-forget，绝不阻塞对话
 *       → 调模型产出 JSON 候选
 *       → UserFactCandidateService.offer(...)   ← 过滤 + 落进"待确认"表
 *     → 用户界面看到候选 → 采纳 / 忽略
 * </pre>
 *
 * <p>超时不由本类管：{@code ModelRouter} 下方的适配器有自己的请求超时，
 * 再加一层只会造出"配了不生效"的假承诺。</p>
 *
 * <h3>★ 三个刻意的设计约束</h3>
 * <ol>
 *   <li><b>平台标配，不做成插件</b>。虽然"用 {@code after_llm} 钩子"看起来更解耦，但那有两个硬问题：
 *       ① {@code after_llm} **只在非流式链路生效**（流式下刻意不生效，见 {@code AgentHook} 的矩阵），
 *       于是聊天页一开「流式」，抽取就整个失效且用户毫无感知；
 *       ② 抽像是"系统观察用户"的能力，不该取决于用户装没装某个插件
 *       （同通知模块的处理：`core/notification` 刻意没做成事件的订阅者）。</li>
 *   <li><b>失败必须完全静默</b>。它挂在对话收尾路径上，任何异常都不能冒泡出去 ——
 *       用户发消息这件事，不该因为"顺带记个笔记失败了"而受影响。</li>
 *   <li><b>默认关闭</b>（{@code agent-platform.memory.auto-profile.enabled}）。
 *       涉及"系统在背后记录用户"，让使用者显式打开比默认开启更合适；
 *       也避免既有部署因为升版突然开始调用额外的模型。</li>
 * </ol>
 *
 * <h3>为什么用虚拟线程而不是线程池队列</h3>
 * 抽取是纯 I/O 等待（一次模型往返），虚拟线程按需创建、阻塞成本极低；
 * 而固定池在并发对话下会排队，排到最后抽取结果早已与对话无关（甚至用户已经退出）。
 * 更关键的是：**排队会掩盖问题** —— 抽取失败该被丢弃，而不是积压。
 */
@Slf4j
@Service
public class UserFactExtractor {

    /** 总开关（默认关）。 */
    @Value("${agent-platform.memory.auto-profile.enabled:false}")
    private boolean enabled;

    /**
     * 抽取用的模型服务商 / 模型。
     *
     * <p>刻意**不复用本轮对话的模型**：抽取是"顺带做的事"，用平台默认模型更可预测，
     * 也不必让调用方把本轮解析出的 provider/model 一路传下来（那个链路已经足够长）。</p>
     *
     * <p><b>这里写的是固定的默认值，没有回落到
     * {@code agent-platform.model.default-*}</b> —— 那种"默认值本身又是一个占位符"的
     * 嵌套写法 Spring 能解析，但会被 IDE 的 Spring 插件标成错误，徒增噪声。
     * 二者默认值本来就相同（deepseek / deepseek-chat），真要统一改，改这里一行即可。</p>
     */
    @Value("${agent-platform.memory.auto-profile.provider:deepseek}")
    private String provider;

    @Value("${agent-platform.memory.auto-profile.model:deepseek-chat}")
    private String model;

    /** 只抽取足够长的用户消息：太短的多半是"你好""继续"这类，没有可提取的信息。 */
    private static final int MIN_USER_MESSAGE_CHARS = 10;

    /** 传给模型的对话文本上限（超出截断，避免把一次长粘贴整个发过去）。 */
    private static final int MAX_DIALOGUE_CHARS = 4000;

    private final ModelRouter modelRouter;
    private final UserFactCandidateService candidateService;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public UserFactExtractor(ModelRouter modelRouter, UserFactCandidateService candidateService) {
        this.modelRouter = modelRouter;
        this.candidateService = candidateService;
    }

    /**
     * 异步抽取（fire-and-forget）。**任何情况下都不抛异常、不阻塞调用方。**
     *
     * @param tenantId  租户
     * @param userId    用户（为空则直接跳过 —— 画像没有归属就无从谈起）
     * @param sessionId 来源会话（可追溯）
     * @param userMessage    本轮用户消息
     * @param assistantReply 本轮助手回复
     */
    public void extractAsync(String tenantId, String userId, String sessionId,
                             String userMessage, String assistantReply) {
        if (!enabled) {
            return;
        }
        if (userId == null || userId.isBlank()) {
            return;   // 无归属用户，抽取无意义（也避免写出一条无法归属的候选）
        }
        if (userMessage == null || userMessage.trim().length() < MIN_USER_MESSAGE_CHARS) {
            return;   // 太短，没有可提取的信息，省一次模型调用
        }
        try {
            executor.submit(() -> {
                try {
                    extract(tenantId, userId, sessionId, userMessage, assistantReply);
                } catch (Exception e) {
                    // 静默降级：这是"顺带做的事"，绝不能影响对话（见类注释约束 ②）
                    log.debug("[memory] 画像自动抽取失败（已忽略）：{}", e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            // 极端情况下（如关闭中）提交被拒 —— 同样静默
            log.debug("[memory] 画像抽取任务提交被拒（已忽略）");
        }
    }

    // ------------------------------------------------------------------ 实现

    private void extract(String tenantId, String userId, String sessionId,
                         String userMessage, String assistantReply) {
        String dialogue = buildDialogue(userMessage, assistantReply);
        ModelAdapter.ChatRequest req = new ModelAdapter.ChatRequest(
                model, EXTRACTION_SYSTEM_PROMPT, dialogue,
                // 温度压到 0：抽取要的是稳定输出，不希望同一段对话两次抽出不同结果
                0.0,
                // 最多几条短事实，512 足够且省 token
                512,
                Map.of(),
                List.of(),
                null, null);

        ModelAdapter.ChatResponse resp = modelRouter.chat(provider, req);
        String content = resp == null ? null : resp.content();
        List<UserFactCandidateService.CandidateDraft> drafts = parseDrafts(content);
        if (drafts.isEmpty()) {
            return;
        }
        int n = candidateService.offer(tenantId, userId, drafts, sessionId, model);
        if (n > 0) {
            log.info("[memory] 为用户 {} 抽取到 {} 条画像候选（待其确认）", userId, n);
        }
    }

    /** 拼"用户 / 助手"两段对话，并做长度截断。 */
    private static String buildDialogue(String userMessage, String assistantReply) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户：").append(userMessage.trim());
        if (assistantReply != null && !assistantReply.isBlank()) {
            sb.append("\n助手：").append(assistantReply.trim());
        }
        String text = sb.toString();
        return text.length() > MAX_DIALOGUE_CHARS ? text.substring(0, MAX_DIALOGUE_CHARS) : text;
    }

    /**
     * 解析模型返回的 JSON 数组。
     *
     * <p>模型的输出不会总是干净的 JSON（可能包了 ```json 代码块、可能带前后解释文字），
     * 所以这里**先尝试整体解析，失败再截取第一个 {@code [} 到最后一个 {@code ]} 重试** ——
     * 两种都不成才放弃。解析失败不是错误路径，而是这个功能的常态之一。</p>
     *
     * <p>包私有而非 private：它是本类唯一有分支的纯逻辑（也是最容易写错的一处），
     * 单独可测比藏在私有方法里更有价值。</p>
     */
    static List<UserFactCandidateService.CandidateDraft> parseDrafts(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        JsonNode array = tryParseArray(content.trim());
        if (array == null) {
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start >= 0 && end > start) {
                array = tryParseArray(content.substring(start, end + 1));
            }
        }
        if (array == null || !array.isArray()) {
            return List.of();
        }

        List<UserFactCandidateService.CandidateDraft> out = new ArrayList<>();
        for (JsonNode item : array) {
            String key = textOf(item, "key");
            String value = textOf(item, "value");
            if (key.isBlank() || value.isBlank()) {
                continue;   // 模型偶尔吐出不完整项，跳过而不是报错
            }
            out.add(new UserFactCandidateService.CandidateDraft(
                    key, value, UserFactCategory.of(textOf(item, "category"))));
        }
        return out;
    }

    private static JsonNode tryParseArray(String json) {
        try {
            JsonNode node = JsonUtils.toJsonNode(json);
            return node != null && node.isArray() ? node : null;
        } catch (Exception e) {
            // Jackson 3 的异常是 unchecked；解析失败属预期内，静默返回 null 让调用方走截取重试
            return null;
        }
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode child = node.get(field);
        // asText(String) 这种带默认值的重载已被标记弃用，这里显式判空后取文本
        if (child == null || child.isNull()) {
            return "";
        }
        return child.asText().trim();
    }

    /**
     * 抽取用的系统提示词。
     *
     * <p>几条约束都是针对"模型在这类任务上会怎么犯错的"：</p>
     * <ul>
     *   <li><b>只提取用户明确说过的</b> —— 否则它会把助手的猜测也记成用户的事实；</li>
     *   <li><b>只提取长期稳定的</b> —— 否则"用户今天问了天气"会被记成"关注天气"；</li>
     *   <li><b>明确"没有就输出 []"</b> —— 不说这句，模型倾向于硬凑几条出来；</li>
     *   <li><b>限定 category 取值</b> —— 否则它会自创分类，落到枚举解析时全变 other。</li>
     * </ul>
     */
    private static final String EXTRACTION_SYSTEM_PROMPT = """
            你是用户画像抽取器。从下面这轮对话里提取**关于用户本人**的稳定事实。

            严格输出 JSON 数组，不要任何解释、不要代码块以外的文字。没有可提取的内容就输出 []。
            格式：[{"key":"职业","value":"Java 后端工程师","category":"background"}]

            规则：
            1. 只提取**用户明确说过**的信息，绝不根据助手的推测或常识去补全。
            2. 只提取**长期稳定**的特征（职业、技术栈、语言偏好、所在地、当前目标、表达习惯等），
               不要提取一次性的问题、临时情绪、或当天要做的事。
            3. key 用简短的中文名词（如：职业 / 常用语言 / 所在地 / 项目技术栈 / 称呼偏好）。
            4. category 只能取这四个之一：preference（偏好）/ background（背景）/ goal（目标）/ other。
            5. 最多 5 条；宁可少不要多。""";
}
