package com.agentplatform.core.session;

import com.agentplatform.framework.api.ModelAdapter;

import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.model.entity.Message;
import com.agentplatform.model.entity.Session;
import com.agentplatform.model.repository.MessageRepository;
import com.agentplatform.model.repository.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 中期记忆：会话早期摘要（rollup）。
 * <p>
 * 上下文窗口只回放最近 N 轮；更早的消息会被截断丢弃。本服务把这些「即将被截断」的早期轮次
 * 压缩成一段摘要存到 {@code session_def.summary}，运行入口在历史较长时把它拼进系统提示词，
 * 使跨长对话的早期信息仍可被模型感知（不丢"记忆"，只丢逐字细节）。
 * </p>
 * <p>
 * <b>摘要策略</b>：当前为<b>离线要点式</b>（抽取早期 user 消息要点，无 Key 也可用、零网络）；
 * 语义层调用 LLM 可后续在同一方法替换，不改变调用方。若某轮 LLM 不可用，回退本策略即可。
 * </p>
 * <p><b>触发</b>：懒生成——仅当轮次超过 {@code ROLLUP_MIN_TURNS} 且未被现有摘要覆盖时才 rollup，
 * 幂等（{@code summary_turn} 记录已覆盖到的轮次）。</p>
 */
@Slf4j
@Service
public class SessionSummaryService {

    /**
     * 超过该轮次才考虑摘要（2026-10-26 改成**可配置**）。
     *
     * <p><b>为什么原来写死 40 是个坑</b>：插件侧的 {@code maxHistoryTurns}
     * 是可调的（默认 40，用户可改）。两边一旦不对齐，就会出现
     * 「超过上下文窗口、但还没到摘要门槛」的空窗 —— 那一段对话是真丢。</p>
     *
     * <p>现在从 {@code agent-platform.memory.summary-min-turns} 读，
     * 默认 20。**改它的时候记得和插件里的「记忆轮数」保持一致或更小。**</p>
     */
    private final int rollupMinTurns;

    /**
     * 保留最近轮次不进入摘要（可配置，默认 12）。
     *
     * <p><b>必须小于 {@link #rollupMinTurns}</b>：如果相等，摘要覆盖范围
     * 就是「第 0 轮之前」，等于没内容可摘。构造时会自动夹到
     * {@code rollupMinTurns - 1}。</p>
     *
     * <p>取 12 是留出重叠：上下文带 20 轮原文，摘要覆盖第 1~8 轮，
     * 中间有 8 轮重叠 —— 宁可重复，也不要漏。</p>
     */
    private final int rollupKeepTurns;

    /**
     * 距离上次摘要至少跨过这么多轮才重算（2026-10-28）。
     *
     * <p>不加这个，`covered < targetTurn` 每轮都成立 → 每句对话都重新摘要一次。
     * 取 10：摘要本来就是给"很久以前"用的，晚十轮更新没影响。</p>
     */
    private static final int SUMMARY_MIN_GAP_TURNS = 10;

    /**
     * 单次 rollup 最多处理多少条消息（防止极端会话一次压太多）。
     *
     * <p>2026-10-28 从 60 提到 600：改成**增量**之后，每次只处理"上次摘要点之后"
     * 的新消息，正常情况下远小于这个上限；这里只是兜底。</p>
     */
    static final int EARLY_MAX_MSGS = 600;

    /**
     * 摘要最多保留多少条要点（2026-10-28 从 14 提到 24）。
     *
     * <p><b>原来 14 条是个硬伤</b>：摘要每次从 turn 0 开始取前 60 条消息、
     * 只留 14 条要点 —— 于是一个 172 轮的会话，摘要**永远只覆盖最前面 14 轮**，
     * 中间一百多轮从来没进过摘要。用户感觉"摘要吃干饭"就是这个原因。</p>
     *
     * <p>现在改成增量累积 + 均匀采样：要点从最早的会话一直覆盖到当前。
     * 24 条 × 120 字 ≈ 2900 字，约 3K token，可接受。</p>
     */
    static final int POINT_MAX = 24;

    static final int POINT_CHARS = 120;

    /**
     * 摘要提炼的提示词（2026-11-01）。
     *
     * <p>七条规则各自对应一个实测问题：</p>
     * <ul>
     *   <li>规则 1、2 —— 原来摘出来的是「宝宝，在干嘛呢」这种原话，不是事实；</li>
     *   <li>规则 3 —— 「哼」「好」占了大量名额；</li>
     *   <li><b>规则 4、最后一段 —— 这是「调和」的核心</b>：同一件事说三遍只留一条，
     *       改口了以新的为准（原来的实现是 addAll，纯追加）；</li>
     *   <li>规则 5、6 —— 上限交给模型按重要度取舍，而不是我们按时间先进先出。</li>
     * </ul>
     */
    private static final String SUMMARY_SYSTEM_PROMPT = """
            你在整理一段聊天记录，产出「长期记忆要点」。
            这些要点会在之后的对话里当作背景，给同一个角色看。

            规则：
            1. 只写事实：谁做了什么、答应了什么、发生过什么、有什么偏好。
            2. 不要写「他说…」「她说…」这种转述框架，直接写内容本身。
            3. 「嗯」「好」「哈哈」「哦」这类附和不要摘。
            4. 同一件事换个说法说了几遍，只留一条。
               旧要点和新内容冲突时，以新的为准，把旧的替换掉，不要两条都留。
            5. 每条不超过 40 字，最多 20 条。
            6. 按重要程度排序，最该记住的放前面。
            7. 只输出要点本身，每行一条，行首用「· 」，不要编号，不要解释。

            如果新旧内容重复，或旧要点已被更完整的说法覆盖，合并掉，不要两条都留。
            """;

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    /**
     * 摘要用的模型通道（2026-11-01 新增，**可为 null**）。
     *
     * <p><b>为什么要它</b>：原来的摘要是"截取 user 消息前 120 字"——
     * 零成本，但产出的是一堆碎话。实测一份 884 字的摘要里，
     * 引用插件指令原文三次（「挑一件值得让他知道的，跟他说一句…」），
     * 还有大量「哼」「宝宝，在干嘛呢」这种对方的话。</p>
     *
     * <p>让它提炼能同时解决三件事：① 落盘的是**事实**不是原文；
     * ② 能**合并去重**（同一件事说三遍不存三条）；
     * ③ 能按重要度取舍，而不是按时间先后截断。</p>
     *
     * <p><b>为什么可为 null</b>：没配模型、模型不可用、调用超时 ——
     * 任何一种情况都要能退回原来的规则抽取。摘要挂掉不影响对话。</p>
     */
    private final ModelRouter modelRouter;

    /** 是否用模型提炼摘要（默认开；模型不可用时自动回退）。 */
    private final boolean llmEnabled;

    /** 提炼用的模型 id；空则走平台默认。 */
    private final String llmModel;

    /** 提炼用的服务商（和 {@link #llmModel} 成对）。 */
    private final String llmProvider;

    /** 单次提炼的超时（毫秒）。超时就回退规则抽取，不阻塞对话。 */
    private static final long LLM_TIMEOUT_MS = 8000;

    public SessionSummaryService(
            SessionRepository sessionRepository,
            MessageRepository messageRepository,
            @Value("${agent-platform.memory.summary-min-turns:20}") int rollupMinTurns,
            @Value("${agent-platform.memory.summary-keep-turns:12}") int rollupKeepTurns,
            @Value("${agent-platform.memory.summary-llm-enabled:true}") boolean llmEnabled,
            @Value("${agent-platform.memory.summary-llm-provider:deepseek}") String llmProvider,
            @Value("${agent-platform.memory.summary-llm-model:deepseek-chat}") String llmModel,
            @Autowired(required = false) ModelRouter modelRouter) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        // 门槛至少 2 轮，否则第一次对话就会触发摘要（毫无意义）
        this.rollupMinTurns = Math.max(2, rollupMinTurns);
        // 保留窗口必须严格小于门槛，否则「该摘的那部分」是空的
        this.rollupKeepTurns = Math.max(1, Math.min(rollupKeepTurns, this.rollupMinTurns - 1));
        this.llmEnabled = llmEnabled;
        this.llmProvider = llmProvider == null ? "deepseek" : llmProvider.trim();
        this.llmModel = llmModel == null ? "deepseek-chat" : llmModel.trim();
        this.modelRouter = modelRouter;
        log.info("SessionSummaryService: llm={} model={} router={}",
                llmEnabled, this.llmModel.isEmpty() ? "(默认)" : this.llmModel,
                modelRouter == null ? "缺失" : "可用");
    }

    /**
     * 返回会话的早期摘要文本；无（或会话太短/无需）返回 {@code null}。
     * <p>已有可用摘要则直接返回；未覆盖到最新截断点时懒生成并持久化。</p>
     */
    /**
     * 运行时入口：只给会话句柄，内部按 sessionId 取实体。
     *
     * <p>摘要字段（{@code summary} / {@code summaryTurn}）是持久化状态，属于本服务的内部细节，
     * 不该沿着调用链外泄到运行时——否则换存储就等于改运行时。</p>
     */
    public String summaryForHandle(com.agentplatform.framework.api.SessionHandle handle) {
        if (handle == null) {
            return null;
        }
        return sessionRepository.findBySessionId(handle.sessionId())
                .map(this::summaryFor)
                .orElse(null);
    }

    public String summaryFor(Session session) {
        if (session == null) {
            return null;
        }
        try {
            int latestTurn = messageRepository.maxTurnNo(session.getSessionId());
            if (latestTurn < rollupMinTurns) {
                // 历史还不够长，早期内容仍在窗口内 → 直接用（可能 null）
                return existing(session);
            }
            int covered = session.getSummaryTurn() == null ? 0 : session.getSummaryTurn();
            /*
             * 摘要不要每轮重算（2026-10-28 修）。
             *
             * <p><b>原来的逻辑有漏洞</b>：`summaryTurn` 存的是 `targetTurn`
             * （= latestTurn - keepTurns）。下一轮 latestTurn 前进 1，targetTurn 也跟着前进 1，
             * 于是 `covered < targetTurn` **永远成立** —— 每一句对话都要重新摘一次。</p>
             *
             * <p>实测一个 172 轮的会话，14:11 / 14:12 / 14:13 连着三次对话
             * 产生三次 rollup，每次都把这个会话的全部消息读出来过滤一遍。
             * 它不影响正确性（摘要内容差不多），但纯属白烧 IO 和 CPU。</p>
             *
             * <p>现在要求「距离上次摘要至少跨过 {@value #SUMMARY_MIN_GAP_TURNS} 轮」
             * 才重算。摘要本来就是给"很久以前"用的，晚几轮更新没有影响。</p>
             */
            if (covered > 0 && latestTurn - covered < SUMMARY_MIN_GAP_TURNS) {
                return existing(session);
            }
            int targetTurn = latestTurn - rollupKeepTurns;
            if (covered >= targetTurn) {
                return existing(session);
            }
            return rollup(session, targetTurn);
        } catch (Exception e) {
            log.warn("Session summary skipped for {}: {}", session.getSessionId(), e.getMessage());
            return null;
        }
    }

    private String existing(Session session) {
        String summary = session.getSummary();
        return summary == null || summary.isBlank() ? null : summary;
    }

    private String rollup(Session session, int targetTurn) {
        /*
         * 增量抽取（2026-10-28 重写）。
         *
         * 原来每次都从 turn 0 开始取前 EARLY_MAX_MSGS 条、只保留 POINT_MAX 条要点，
         * 于是 172 轮的会话，摘要**永远只覆盖最前面 14 轮**，中间一百多轮是空白。
         * 用户的感觉就是"摘要吃干饭"。
         *
         * 现在：只取「上次摘要点之后 ~ 本次目标」的新消息，要点**追加**到旧摘要后，
         * 超过上限时均匀采样（保留首尾），覆盖整个时间跨度。
         *
         * 任何失败都保留旧摘要、不推进 summaryTurn —— 宁可重复，不能丢。
         */
        int covered = session.getSummaryTurn() == null ? 0 : session.getSummaryTurn();

        List<Message> fresh = messageRepository.findBySessionIdOrderByTurnNoAscSeqNoAsc(session.getSessionId())
                .stream()
                .filter(m -> m.getTurnNo() != null
                        && m.getTurnNo() > covered
                        && m.getTurnNo() <= targetTurn)
                .limit(EARLY_MAX_MSGS)
                .toList();
        if (fresh.isEmpty()) {
            return existing(session);
        }

        /*
         * 混合压缩（2026-11-01）：
         *   ① 优先让模型「调和」—— 给旧要点 + 新消息，要它产出合并后的结果；
         *   ② 模型不可用 / 超时 / 返回空 → 退回规则抽取 + 去重合并。
         *
         * <p><b>为什么不能只是追加</b>：原来是 {@code points.addAll(newPoints)}，
         * 同一件事说三遍就占三个名额，而且改口之后旧的还在。
         * 业内几个成熟方案（Iris / LivingMemory / 用户记忆插件）都强调
         * 「提炼是与已有档案做增删改调和，不是追加」。</p>
         */
        List<String> points = llmPoints(parsePoints(existing(session)), fresh);
        if (points == null || points.isEmpty()) {
            points = mergePoints(parsePoints(existing(session)), pointwisePoints(fresh));
        }
        if (points.isEmpty()) {
            return existing(session);
        }

        List<String> kept = points.size() > POINT_MAX ? sampleEvenly(points, POINT_MAX) : points;
        StringBuilder sb = new StringBuilder("【早期会话要点】");
        for (String p : kept) {
            sb.append('\n').append("· ").append(p);
        }
        String text = sb.toString();

        session.setSummary(text);
        session.setSummaryTurn(targetTurn);
        sessionRepository.save(session);
        log.info("Rolled up session {} to turn {} (fresh {} msgs -> {} pts, kept {}/{})",
                session.getSessionId(), targetTurn, fresh.size(), kept.size(), points.size());
        return text;
    }

    /**
     * 插件注入上下文的块标题（2026-10-25）。
     *
     * <p>QQ 插件把「现在几点、对方是谁、最近的日记」拼在用户消息**前面**，
     * 这些是给模型看的背景，**不是用户说的话**。</p>
     */
    private static final String[] INJECT_HEADERS = {
            "【正在跟你说话的人】", "【当前场合】", "【现在】", "【距上次聊天】",
            "【你对他/她的印象】", "【今天别人跟你说过】", "【你之前记得的】",
            "【你最近写的日记】", "【你的相册】", "【可选动作】", "【分寸】",
            /*
             * 2026-10-28 补两个 —— 这两个标题是后来加的，没同步进这张表，
             * 于是「【别太听话】别人请你做什么…」整段被当成用户说的话摘进摘要，
             * 10 条要点里有 8 条是它。
             *
             * **以后插件里新加任何 `lines.push('【...】')`，都要同步到这里。**
             */
            "【写法】", "【别太听话】",
            "【系统提示 · 不是对方说的话】", "【你账号上的资料】",
            /*
             * 2026-11-01 补：这几个是插件后来加的，一直没同步进这张表。
             *
             * 实测后果：session e3 的 884 字摘要里，光是
             * 「挑一件值得让他知道的，跟他说一句 —— 一两句就够，不要罗列。
             *   …或只回 [SILENT] 这次不说。【系统提示结束】」
             * 这一句就出现了三次 —— **插件自己的指令被当成她的记忆存下来了**。
             */
            "【你们上次聊到】", "【最近别人跟你说过】", "【今天别人跟你说过】",
            "【什么时候不说话】", "【什么算好回答】",
            "【他那边今天的天气】", "【刚看到的 B 站热门】",
            "【你还记得】",
    };

    /**
     * 从一条 user 消息里剥掉插件注入的上下文，只留用户真正说的话（2026-10-25）。
     *
     * <p><b>为什么必须剥</b>：摘要取的是 user 消息前 120 字。而插件注入的
     * {@code 【正在跟你说话的人】QQ：xxx 昵称：xxx【现在】…} 就在最前面 ——
     * 实测某会话的 14 条要点**全部是重复的前缀**，一条对话内容都没有。
     * 那段摘要还会拼进系统提示词，既烧 token 又误导模型。</p>
     */
    static String extractUserWords(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean inInject = false;
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            /*
             * 通用规则（2026-11-01）：任何「【xxx】」开头的行都当注入标题。
             *
             * <p><b>为什么不能再只靠白名单</b>：插件侧每加一段
             * {@code lines.push('【...】')} 都要记得同步 INJECT_HEADERS，
             * 而实际上漏了五个 —— 漏掉的那些整段被当成"用户说的话"摘进摘要。
             * 白名单里那条注释自己都写着"以后新加任何标题都要同步到这里"，
             * 说明靠人记是不可靠的。</p>
             *
             * <p>所以加一条通用判断兜底：只要以【开头、含】，一律视为注入。
             * 她正常说话不会用「【】」。</p>
             */
            if (t.startsWith("【") && t.contains("】")) {
                inInject = true;
                continue;
            }
            boolean isHeader = false;
            for (String h : INJECT_HEADERS) {
                if (t.startsWith(h)) {
                    isHeader = true;
                    break;
                }
            }
            if (isHeader) {
                inInject = true;
                continue;
            }
            // 注入块内的附属行：缩进、QQ/昵称/城市、括号补充、破折号说明
            if (inInject) {
                boolean stillInject = line.startsWith("  ") || line.startsWith("\t")
                        || t.startsWith("QQ：") || t.startsWith("昵称：") || t.startsWith("城市：")
                        || t.startsWith("（") || t.startsWith("——");
                if (stillInject) {
                    continue;
                }
                inInject = false;   // 遇到真正的内容，退出注入状态
            }
            sb.append(t);
        }
        return sb.toString().trim();
    }

    /**
     * 从一批消息里抽要点（不含标题），供增量合并用（2026-10-28）。
     *
     * <p><b>注意</b>：这里**不截断**，返回全部要点，
     * 由调用方决定怎么合并/采样。</p>
     */
    private List<String> pointwisePoints(List<Message> msgs) {
        List<String> out = new ArrayList<>();
        for (Message m : msgs) {
            String role = m.getRole();
            if (role == null) {
                continue;
            }
            String content = m.textContent();
            if (content == null || content.isBlank()) {
                continue;
            }

            if ("user".equalsIgnoreCase(role)) {
                // 先剥掉插件注入的上下文，否则摘的全是「【现在】…」这类噪音
                String said = extractUserWords(content);
                if (said.isEmpty()) {
                    continue;
                }
                String point = collapse(said);
                if (!point.isEmpty()) {
                    out.add(point);
                }
                continue;
            }

            /*
             * 她的回复也进摘要（2026-11-01）。
             *
             * <p><b>原来只取 user</b>，而 user 消息里 83% 是插件注入 ——
             * 于是 884 字的摘要里全是「宝宝，在干嘛呢」「好」「哼」这种
             * **他说的话**，一条「她答应了什么」都没有。</p>
             *
             * <p>后果是：她记得你说过的每句话，但不记得自己答应过什么，
             * 也不记得自己做过什么决定 —— 表现为"记忆错乱"。</p>
             *
             * <p>这里先把 {@code [表情 开心]}、{@code [语音]}、{@code [发图 …]}
             * 这类动作标记剥掉，只留她说的话。</p>
             */
            if ("assistant".equalsIgnoreCase(role)) {
                String said = content
                        .replaceAll("\\[[^\\]]{1,16}\\]", "")
                        .replaceAll("<[^>]{1,32}>", "")
                        .trim();
                if (said.isEmpty()) {
                    continue;
                }
                String point = collapse(said);
                if (!point.isEmpty()) {
                    out.add(point);
                }
            }
        }
        return out;
    }

    /** 从已有摘要文本里取回要点行（以「·」开头的那几行）。 */
    /**
     * 用模型提炼摘要（2026-11-01）。失败返回 {@code null}，由调用方回退规则抽取。
     *
     * <p><b>提示词为什么这么写</b>：</p>
     * <ul>
     *   <li><b>要求合并而不是追加</b> —— 同一件事换个说法说三遍，只留一条；</li>
     *   <li><b>只要是事实</b> —— 不写"他说了…她说…"，直接写发生了什么；</li>
     *   <li><b>明确禁止摘附和</b> —— 「嗯」「好」「哼」这类不该占名额；</li>
     *   <li><b>给上限</b> —— 超了让模型自己按重要度取舍，而不是我们按时间截断。</li>
     * </ul>
     *
     * <p>返回的是**提炼后的短句**，不是原文切片 —— 这就是"落盘摘要不落原文"。</p>
     */
    private List<String> llmPoints(List<String> oldPoints, List<Message> fresh) {
        if (!llmEnabled || modelRouter == null) {
            return null;
        }
        String dialogue = renderDialogue(fresh);
        if (dialogue.isBlank()) {
            return null;
        }

        StringBuilder old = new StringBuilder();
        for (String p : oldPoints) {
            old.append("· ").append(p).append('\n');
        }

        String user = "【已有要点】\n" + (old.length() == 0 ? "（无）\n" : old)
                + "\n【新的对话】\n" + dialogue;

        try {
            ModelAdapter.ChatRequest req = new ModelAdapter.ChatRequest(
                    llmModel, SUMMARY_SYSTEM_PROMPT, user,
                    // 温度压到 0.2：提炼要稳定，不希望同一段对话两次抽出不同结论
                    0.2,
                    // 24 条 × 40 字 ≈ 960 字，1200 留余量
                    1200,
                    Map.of(), List.of(), null, null);
            ModelAdapter.ChatResponse resp = modelRouter.chat(llmProvider, req);
            String content = resp == null ? null : resp.content();
            if (content == null || content.isBlank()) {
                log.debug("summary llm：返回空，回退规则抽取");
                return null;
            }
            List<String> got = parseLines(content);
            if (got.isEmpty()) {
                log.debug("summary llm：解析不出条目，回退规则抽取");
                return null;
            }
            log.info("summary llm：{} 条旧要点 + {} 条消息 → {} 条（模型 {}）",
                    oldPoints.size(), fresh.size(), got.size(), llmModel);
            return got;
        } catch (Exception e) {
            // 摘要失败不影响对话 —— 回退到规则抽取即可
            log.warn("summary llm 失败（回退规则抽取）：{}", e.getMessage());
            return null;
        }
    }

    /** 把消息渲染成"他/她：内容"的纯文本，剥掉注入块与动作标记。 */
    private String renderDialogue(List<Message> fresh) {
        StringBuilder sb = new StringBuilder();
        for (Message m : fresh) {
            String t = m.textContent();
            if (t == null || t.isBlank()) {
                continue;
            }
            // 跳过插件注入（【现在】【可选动作】这类整段）
            if (t.startsWith("【") && t.contains("】")) {
                continue;
            }
            String clean = t.replaceAll("\\[[^\\]]{1,16}\\]", "")   // [表情 开心] / [语音]
                    .replaceAll("\\s+", " ")
                    .trim();
            if (clean.isEmpty()) {
                continue;
            }
            String who = "user".equalsIgnoreCase(m.getRole()) ? "他" : "她";
            sb.append(who).append("：").append(clean).append('\n');
            if (sb.length() > 6000) {
                break;
            }
        }
        return sb.toString();
    }

    /** 解析模型输出的一行一条要点（去掉 ·、-、数字前缀、引号）。 */
    private static List<String> parseLines(String content) {
        List<String> out = new ArrayList<>();
        for (String line : content.split("\n")) {
            String t = line.trim()
                    .replaceAll("^[·\\-*•\\d.、\\)]+\\s*", "")
                    .replaceAll("^[\"“”「」]+|[\"“”「」]+$", "")
                    .trim();
            if (t.length() >= 4 && !t.startsWith("（") && !t.startsWith("(")) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 规则版「调和」（模型不可用时的回退，2026-11-01）。
     *
     * <p>比纯追加多两步：</p>
     * <ol>
     *   <li>完全相同去重（同一句话重复三次只留一条）；</li>
     *   <li>剔除被更长条目包含的短条目（"他明天来"被"他明天下午来，带了东西"覆盖）。</li>
     * </ol>
     *
     * <p>它不会像模型那样"改口替换"（说 A 后又说 B，规则分不出哪个新），
     * 但至少不会把同一件事重复占位。</p>
     */
    private static List<String> mergePoints(List<String> oldPts, List<String> newPts) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String p : oldPts) {
            if (p != null && !p.isBlank()) {
                set.add(p.trim());
            }
        }
        for (String p : newPts) {
            if (p != null && !p.isBlank()) {
                set.add(p.trim());
            }
        }
        List<String> all = new ArrayList<>(set);

        List<String> kept = new ArrayList<>();
        for (String p : all) {
            boolean covered = false;
            for (String q : all) {
                if (!q.equals(p) && q.length() > p.length() && q.contains(p)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                kept.add(p);
            }
        }
        return kept;
    }

    private static List<String> parsePoints(String summary) {
        List<String> out = new ArrayList<>();
        if (summary == null || summary.isBlank()) {
            return out;
        }
        for (String line : summary.split("\n")) {
            String t = line.trim();
            if (t.startsWith("·")) {
                String p = t.substring(1).trim();
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /**
     * 均匀采样，保留首尾（2026-10-28）。
     *
     * <p>要点超上限时必须**等间隔抽**，不能取前 N 条 —— 否则最近几百轮
     * 永远进不了摘要（这正是之前 14 条上限的死穴）。</p>
     */
    private static List<String> sampleEvenly(List<String> src, int max) {
        if (src.size() <= max) {
            return src;
        }
        List<String> out = new ArrayList<>(max);
        double step = (double) src.size() / max;
        for (int i = 0; i < max; i++) {
            int idx = (int) Math.min(src.size() - 1, Math.round(i * step));
            out.add(src.get(idx));
        }
        return out;
    }

    private String collapse(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= POINT_CHARS ? t : t.substring(0, POINT_CHARS) + "…";
    }
}
