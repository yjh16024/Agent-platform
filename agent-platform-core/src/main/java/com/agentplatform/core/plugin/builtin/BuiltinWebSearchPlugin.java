package com.agentplatform.core.plugin.builtin;

import com.agentplatform.framework.api.ToolResult;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.plugin.sdk.ConfigFieldDef;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginDescriptor;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import com.agentplatform.plugin.sdk.UiProvider;
import com.agentplatform.plugin.sdk.model.PluginManifest;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置插件：<b>联网搜索</b>。
 *
 * <h3>它补的是模型的哪个缺口</h3>
 * 模型的知识停在训练截止那一刻，且<b>自己不知道这一点</b> —— 问它"某某的最新版本是多少"，
 * 它会给出一个听起来很确定的过期答案。工具本身不会让它变聪明，但能给它一条
 * 「<b>先去查、再回答</b>」的路。这也是各类 Agent 平台里调用量最大的工具之一
 * （对标 dshmarket 的 {@code modsearch} / {@code dsh-free-search}，两者合计下载量 5.7 万）。
 *
 * <h3>★ 为什么默认"不用配任何密钥也能用"</h3>
 * 搜索工具的价值取决于<b>用户是否真的会去开它</b>。而"先去某个网站注册账号、拿到 key、
 * 再回来填进表单"这一串动作，会让绝大多数人在第一步就放弃。所以这里做成两级：
 *
 * <table border="1">
 *   <caption>两种检索后端</caption>
 *   <tr><th>provider</th><th>是否需要 Key</th><th>说明</th></tr>
 *   <tr><td>{@code tavily}（默认，配了 key 时）</td><td>需要</td>
 *       <td>专为 AI 检索设计，一次请求返回带摘要的结构化结果；有免费额度</td></tr>
 *   <tr><td>{@code duckduckgo}（未配 key 时的兜底）</td><td><b>不需要</b></td>
 *       <td>走 DuckDuckGo 的 Instant Answer API，<b>零配置即可用</b>。
 *           覆盖范围明显窄（偏"百科式词条"而非网页全文检索），
 *           但足以让用户先看到效果、再决定要不要去配 key。</td></tr>
 * </table>
 *
 * <p>返回值里带 {@code provider} 与 {@code limited=true} 标记 —— 兜底后端的结果不丰富这件事
 * 必须让模型与用户都看得见，否则会被误当成"搜索功能坏了"。</p>
 *
 * <h3>返回格式：为什么是结构化文本而不是裸 JSON</h3>
 * 工具结果会被回灌给模型当上下文。裸 JSON 会让它多花一轮去"理解结构"，而
 * 「标题 / 链接 / 摘要」三段式的纯文本它用起来最直接，也更容易在回答里正确引用链接。
 */
@Slf4j
@Component
public class BuiltinWebSearchPlugin implements ToolProvider, PluginDescriptor, UiProvider {

    /** 与 plugin_def 中的 plugin_id 一致。 */
    public static final String PLUGIN_ID = "builtin_web_search";

    public static final String TOOL_NAME = "web_search";

    // ---------------- 配置键（与前端表单字段一致） ----------------

    public static final String CFG_PROVIDER = "provider";
    public static final String CFG_API_KEY = "apiKey";
    public static final String CFG_BASE_URL = "baseUrl";
    public static final String CFG_MAX_RESULTS = "maxResults";
    public static final String CFG_TIMEOUT_MS = "timeoutMs";

    private static final String PROVIDER_TAVILY = "tavily";
    private static final String PROVIDER_DUCKDUCKGO = "duckduckgo";

    /**
     * DeepSeek 自研搜索（2026-10-28 开放）。
     *
     * <p>和 Tavily / Bing 的关键差别：返回里**直接带 {@code content}（正文）**，
     * 还有 {@code authority}（权威性 0~4）和 {@code date}（发布时间）。</p>
     */
    private static final String PROVIDER_DEEPSEEK = "deepseek";

    /** DeepSeek 官方地址（用户也可以填自定义 baseUrl 走代理）。 */
    private static final String DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com";

    private static final String DEFAULT_TAVILY_BASE_URL = "https://api.tavily.com";
    private static final String DDG_URL = "https://api.duckduckgo.com/";

    /*
     * 必应中国（2026-10-21 加）：**零配置、国内可直连**的后端。
     *
     * 背景：DuckDuckGo 的 API 域名在国内直连超时（实测 TIMEOUT），
     * 而原来"没配密钥就走 DuckDuckGo"的兜底在国内等于**搜索永远失败** ——
     * 用户看到的是"搜索失败（duckduckgo）：Connect timed out"，看起来像插件坏了。
     *
     * 实测 `https://cn.bing.com/search?q=哈尔滨天气` → HTTP 200 / 684ms / 10 条结果，
     * 所以把它做成新的默认兜底：仍然不需要任何密钥。
     */
    private static final String BING_URL = "https://cn.bing.com/search";

    /** 抓必应必须带浏览器 UA：默认 UA 会拿到简化页甚至验证码页。 */
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final String PROVIDER_BING = "bing";

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int DEFAULT_TIMEOUT_MS = 12_000;
    private static final int HARD_MAX_RESULTS = 20;
    private static final int HARD_MAX_TIMEOUT_MS = 60_000;

    /** 单条摘要的字符上限：太长会把上下文挤满，反而降低回答质量。 */
    private static final int SNIPPET_LIMIT = 400;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 按 agentId 隔离配置（插件是单例，`onAttach` 按智能体回调；详见 BuiltinTtsPlugin 的同类说明）。 */
    private final Map<String, SearchConfig> configByAgent = new ConcurrentHashMap<>();
    private final Map<Integer, OkHttpClient> clientsByTimeout = new ConcurrentHashMap<>();

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
        return "联网搜索";
    }

    @Override
    public String description() {
        return "给智能体一个 web_search 工具：先查资料再回答，缓解「模型不知道自己知识过期」的问题。"
                + "不填密钥也能用（走 DuckDuckGo 兜底，覆盖面较窄）；填入 Tavily 密钥后结果明显更全。"
                + "需在对话页打开「工具」开关。";
                }

                /**
                * 配置项声明（键名与 {@link SearchConfig#of} 读取的完全一致）。
                *
                * <p>全部都可以不填 —— 留空即走 DuckDuckGo 兜底，所以这里<b>没有任何必填项</b>。
                * 这一点要在界面上说清楚，否则用户看到"必填"就会以为不填就没法用。</p>
                */
                @Override
                public List<ConfigFieldDef> configFields() {
                return List.of(
                ConfigFieldDef.select(CFG_PROVIDER, "搜索服务商", List.of("deepseek", "bing", "tavily", "duckduckgo"))
                        .hint("留空自动判断：填了密钥走 Tavily，否则走 **Bing 页面抓取**（零配置、国内可直连）。"
                                + "DuckDuckGo 仅建议海外部署使用 —— 它的域名在国内直连会超时"),
                ConfigFieldDef.secret(CFG_API_KEY, "Tavily API Key")
                      .ph("tvly-...")
                      .hint("可选。在 tavily.com 注册后于控制台获取；填了搜索质量会明显更好"),
                ConfigFieldDef.text(CFG_BASE_URL, "自定义接口地址")
                      .hint("可选。留空用默认；若走代理或自建网关才需要填"),
                ConfigFieldDef.number(CFG_MAX_RESULTS, "每次返回条数", 1, HARD_MAX_RESULTS)
                      .def(String.valueOf(DEFAULT_MAX_RESULTS))
                      .hint("条数越多越占上下文，5 条通常够用"),
                ConfigFieldDef.number(CFG_TIMEOUT_MS, "超时（毫秒）", 500, HARD_MAX_TIMEOUT_MS)
                      .def(String.valueOf(DEFAULT_TIMEOUT_MS)));
                }

                @Override
                public void onAttach(PluginContext ctx) {
        SearchConfig cfg = SearchConfig.of(ctx.config());
        configByAgent.put(ctx.agentId(), cfg);
        log.info("[web-search] 已挂载到 agent={} provider={} 已配置密钥={}",
                ctx.agentId(), cfg.effectiveProvider(), cfg.hasApiKey());
    }

    @Override
    public void onDetach(PluginContext ctx) {
        configByAgent.remove(ctx.agentId());
    }

    // ---------------- 工具 ----------------

    @Override
    public List<PluginTool> provideTools() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "搜索关键词。用自然语言问题或关键词都可以，越具体结果越好");
        ArrayNode required = schema.putArray("required");
        required.add("query");

        ObjectNode statusSchema = JsonNodeFactory.instance.objectNode();
        statusSchema.put("type", "object");
        statusSchema.putObject("properties");   // 无参数

        return List.of(
                new PluginTool(TOOL_NAME,
                        "联网搜索网页资料。当问题涉及「最新」「当前」「今年」等时效性内容，"
                                + "或你不确定事实细节时，应当先用它查证再回答。返回若干条「标题 / 链接 / 摘要」。",
                        schema,
                        this::handleSearch),
                /*
                 * 给界面用的状态工具（见 provideUi）。单独开一个而不是复用 web_search：
                 * 界面元素需要的是"配没配、用的哪个后端"这类短状态，而不是搜索结果。
                 */
                new PluginTool(STATUS_TOOL_NAME,
                        "查询联网搜索的当前状态（是否已配置密钥、实际使用哪个后端）。",
                        statusSchema,
                        this::handleStatus));
    }

    /** 界面状态查询工具名。 */
    public static final String STATUS_TOOL_NAME = "search_status";

    private JsonNode handleStatus(JsonNode args, PluginContext ctx) {
        SearchConfig cfg = configByAgent.getOrDefault(ctx == null ? "" : ctx.agentId(), SearchConfig.defaults());
        return JsonNodeFactory.instance.textNode(cfg.hasApiKey() ? "已配密钥" : "未配密钥");
    }

    // ---------------- 界面贡献 ----------------

    /**
     * 在**侧栏底部**放一个状态角标。
     *
     * <p>为什么这个位置值得占：搜索插件的效果高度依赖"配没配密钥"（配了走 Tavily、结果明显更全；
     * 没配走兜底、覆盖很窄），而这件事<b>只在配置页里看得到</b> —— 用户一旦离开那个页面就忘了，
     * 之后看到结果不理想只会以为"搜索不好用"。把它常驻在侧栏，是让这个状态一直可见的最省事做法。</p>
     *
     * <p>它不可点击（{@code action} 为空）：状态本身就是信息，点它没有可做的事 ——
     * 与其放一个点了没反应的按钮，不如让它安静待着。</p>
     */
    @Override
    public List<PluginManifest.Contributes.UiDef> provideUi() {
        return List.of(new PluginManifest.Contributes.UiDef(
                "search-status",
                "sidebar.footer.action",
                "badge",
                "搜索",
                10,                                   // 排在内置项之后
                null,
                null,
                new PluginManifest.Contributes.DataSourceDef(STATUS_TOOL_NAME)));
    }

    /**
     * 工具入口。
     *
     * <p>校验失败<b>直接抛异常</b> —— 宿主会把它转成 {@code ToolResult.fail(message)} 回给模型，
     * 模型据此重试。返回一段"错误文本"反而会被它当成正常结果继续往下编。</p>
     */
    private JsonNode handleSearch(JsonNode args, PluginContext ctx) {
        String query = args == null ? null : args.path("query").asText(null);
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("参数 query 不能为空");
        }
        SearchConfig cfg = configByAgent.getOrDefault(ctx == null ? "" : ctx.agentId(), SearchConfig.defaults());

        try {
            String text;
            if (PROVIDER_TAVILY.equals(cfg.effectiveProvider())) {
                text = searchTavily(query.trim(), cfg);
            } else if (PROVIDER_DEEPSEEK.equals(cfg.effectiveProvider())) {
                text = searchDeepSeek(query.trim(), cfg);
            } else if (PROVIDER_BING.equals(cfg.effectiveProvider())) {
                text = searchBing(query.trim(), cfg);
            } else {
                text = searchDuckDuckGo(query.trim(), cfg);
            }
            return JsonNodeFactory.instance.textNode(text);
        } catch (Exception e) {
            // 搜索失败应该让模型知道"这次没查到"，而不是抛出去把整轮对话打挂
            log.warn("[web-search] 检索失败 agent={} provider={} reason={}",
                    ctx == null ? null : ctx.agentId(), cfg.effectiveProvider(), e.getMessage());
            throw new IllegalStateException("搜索失败（" + cfg.effectiveProvider() + "）：" + e.getMessage()
                    + "。可以换个关键词重试，或直接说明你无法联网核实。");
        }
    }

    // ---------------- Bing（页面抓取，零配置） ----------------

    /**
     * 必应页面抓取（2026-10-21）。
     *
     * <p><b>为什么抓页面而不是调 API</b>：必应的搜索 API 属于 Azure 服务，要订阅要密钥；
     * 而结果页对普通请求是公开的。在"零配置就能用"这个目标下，抓页面是唯一选择。</p>
     *
     * <p><b>代价说清楚</b>：① 结果可能滞后；② 必应改版会让解析失效（此时**抛异常**，
     * 模型拿到的是"这次没查到"，而不是空结果被它拿去编）；③ 高频请求可能被限流。</p>
     */
    private String searchBing(String query, SearchConfig cfg) throws Exception {
        int limit = Math.min(cfg.maxResults(), 10);
        String url = BING_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&count=" + limit;
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .get()
                .build();

        try (Response response = clientFor(cfg.timeoutMs()).newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            String html = response.body() == null ? "" : response.body().string();
            List<String> items = parseBing(html, limit);
            if (items.isEmpty()) {
                throw new IllegalStateException("未解析到结果（页面结构可能已变，或被要求人机验证）");
            }
            StringBuilder sb = new StringBuilder();
            for (String item : items) {
                sb.append(item).append("\n\n");
            }
            sb.append("（来源：Bing 页面抓取，免密钥；结果可能滞后，关键事实建议二次核实）");
            return sb.toString();
        }
    }

    /** 从必应结果页里抽「标题 / 链接 / 摘要」。 */
    private static List<String> parseBing(String html, int limit) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        Matcher blockMatcher = Pattern.compile("(?s)<li class=\"b_algo\".*?</li>").matcher(html);
        int index = 0;
        while (blockMatcher.find() && out.size() < limit) {
            String block = blockMatcher.group();
            Matcher titleMatcher = Pattern
                    .compile("(?s)<h2>.*?<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>")
                    .matcher(block);
            if (!titleMatcher.find()) {
                continue;
            }
            String link = titleMatcher.group(1);
            String title = decodeEntities(titleMatcher.group(2).replaceAll("<[^>]+>", "").trim());
            if (title.isEmpty() || link.isEmpty()) {
                continue;
            }
            String snippet = "";
            Matcher pMatcher = Pattern.compile("(?s)<p[^>]*>(.*?)</p>").matcher(block);
            if (pMatcher.find()) {
                snippet = decodeEntities(pMatcher.group(1).replaceAll("<[^>]+>", "").trim());
            }
            index++;
            out.add(index + ". " + title + "\n   " + link
                    + (snippet.isEmpty() ? "" : "\n   " + snippet));
        }
        return out;
    }

    /**
     * 简单 HTML 实体解码。
     *
     * <p>必应的摘要是 HTML 片段，里面夹着 `&ensp;`（半角空格）、`&#0183;`（间隔点）
     * 这类实体。不解码就会把 `&ensp;` 原样喂给模型，它可能会照着念出来。</p>
     */
    private static String decodeEntities(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text
                .replace("&nbsp;", " ")
                .replace("&ensp;", " ")
                .replace("&emsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&hellip;", "…")
                .replace("&mdash;", "—")
                .replace("&amp;", "&");
        // 数字实体：&#123;（十进制）/ &#x1F600;（十六进制）
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement;
            try {
                int cp = m.group(1).isEmpty()
                        ? Integer.parseInt(m.group(2))
                        : Integer.parseInt(m.group(2), 16);
                replacement = new String(Character.toChars(cp));
            } catch (Exception e) {
                replacement = m.group();   // 解析不了就原样保留
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------- Tavily ----------------

    private String searchTavily(String query, SearchConfig cfg) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_key", cfg.apiKey());
        body.put("query", query);
        body.put("max_results", cfg.maxResults());
        // basic 深度已经够"查个事实"用，且更快更省额度
        body.put("search_depth", "basic");
        body.put("include_answer", false);

        Request request = new Request.Builder()
                .url(cfg.tavilyUrl())
                .post(RequestBody.create(JsonUtils.toJson(body), JSON))
                .header("Accept", "application/json")
                .header("User-Agent", "agent-platform/1.0")
                .build();

        JsonNode root = execute(request, cfg);
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return "（没有检索到结果：" + query + "）";
        }
        List<Map<String, String>> items = new ArrayList<>();
        for (JsonNode r : results) {
            items.add(Map.of(
                    "title", text(r, "title"),
                    "url", text(r, "url"),
                    "snippet", text(r, "content")));
        }
        return render(query, "tavily", items, false);
    }

    // ---------------- DeepSeek 自研搜索（2026-10-28） ----------------

    /**
     * DeepSeek 自研搜索引擎（2026-10-28）。
     *
     * <p><b>为什么单独接它</b>：返回的每条结果里**直接带 {@code content}（正文）**，
     * 还有 {@code authority}（权威性 0~4）和 {@code date}（发布时间）。</p>
     *
     * <p>对比现在的 Bing 抓取：「search → 拿 5 个 URL → fetch 3 个」要**四次往返**，
     * 而且还得赌哪些链接能抓成功。DeepSeek 一次就把正文带回来了。</p>
     *
     * <p>接口 {@code POST https://api.deepseek.com/v1/tools/search}，
     * $0.75 / 1000 次成功调用，**失败和空结果不计费**。</p>
     */
    private String searchDeepSeek(String query, SearchConfig cfg) throws Exception {
        if (!cfg.hasApiKey()) {
            throw new IllegalStateException("DeepSeek 搜索需要 API Key（在内置插件设置里填）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("count", cfg.maxResults());

        String endpoint = cfg.baseUrl() == null || cfg.baseUrl().isBlank()
                ? DEFAULT_DEEPSEEK_BASE_URL
                : cfg.baseUrl().replaceAll("/+$", "");
        // 允许填 https://api.deepseek.com，也允许已经带到 /v1
        String url = endpoint.endsWith("/v1")
                ? endpoint + "/tools/search"
                : endpoint + "/v1/tools/search";

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JsonUtils.toJson(body), JSON))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("User-Agent", "agent-platform/1.0")
                .build();

        JsonNode response = execute(request, cfg);
        JsonNode results = response.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return "（没有检索到结果：" + query + "）";
        }
        List<Map<String, String>> items = new ArrayList<>();
        for (JsonNode r : results) {
            // 优先正文，退到 summary，再退到 snippet
            String content = text(r, "content");
            if (content.isBlank()) {
                content = text(r, "summary");
            }
            if (content.isBlank()) {
                content = text(r, "snippet");
            }
            // 1200 字上限：再多会挤占上下文，模型也用不上
            if (content.length() > 1200) {
                content = content.substring(0, 1200) + "…";
            }
            StringBuilder meta = new StringBuilder();
            int authority = r.path("authority").asInt(-1);
            if (authority >= 0) {
                meta.append("【权威 ").append(authority).append("/4】");
            }
            String date = text(r, "date");
            if (!date.isBlank()) {
                meta.append("【").append(date.length() >= 10 ? date.substring(0, 10) : date).append("】");
            }
            items.add(Map.of(
                    "title", text(r, "title"),
                    "url", text(r, "url"),
                    "snippet", meta + content));
        }
        return render(query, "deepseek", items, false);
    }

    // ---------------- DuckDuckGo（零配置兜底） ----------------

    /**
     * DuckDuckGo Instant Answer API。
     *
     * <p><b>它不是网页搜索</b> —— 返回的是"关于这个词条，百科/官方怎么说"，
     * 对"什么是 XX"很准，对"XX 的最新版本"基本没用。所以结果里要显式标注
     * {@code limited=true}，并且把 {@code AbstractText} 与 {@code RelatedTopics} 都取上，
     * 尽量多给一点可用的东西。</p>
     */
    private String searchDuckDuckGo(String query, SearchConfig cfg) throws Exception {
        String url = DDG_URL + "?q=" + encode(query) + "&format=json&no_html=1&skip_disambig=1";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", "agent-platform/1.0")
                .build();

        return parseDuckDuckGo(execute(request, cfg), query, cfg.maxResults());
    }

    /**
     * 解析 DuckDuckGo 的响应并渲染。
     *
     * <p>抽成纯函数是为了能直接测 —— 兜底路径的正确性只能在测试里保证，
     * 而它恰恰是"用户第一次开这个工具时看到什么"。</p>
     */
    static String parseDuckDuckGo(JsonNode root, String query, int maxResults) {
        List<Map<String, String>> items = new ArrayList<>();

        String abstractText = text(root, "AbstractText");
        String abstractUrl = text(root, "AbstractURL");
        if (abstractText != null && !abstractText.isBlank()) {
            items.add(Map.of(
                    "title", blankTo(text(root, "Heading"), query),
                    "url", blankTo(abstractUrl, "https://duckduckgo.com/?q=" + encode(query)),
                    "snippet", abstractText));
        }
        JsonNode related = root == null ? null : root.path("RelatedTopics");
        if (related != null && related.isArray()) {
            for (JsonNode r : related) {
                if (items.size() >= maxResults) {
                    break;
                }
                String rt = text(r, "Text");
                String ru = text(r, "FirstURL");
                if (rt != null && ru != null) {
                    items.add(Map.of("title", rt, "url", ru, "snippet", rt));
                }
            }
        }
        if (items.isEmpty()) {
            return "（兜底搜索引擎没查到「" + query + "」的结果。\n"
                    + "注意：当前未配置搜索密钥，走的是 DuckDuckGo Instant Answer，"
                    + "只覆盖百科式词条、不覆盖网页全文检索。\n"
                    + "如需真正可用的联网搜索，请到「插件 → 挂载配置」填入 Tavily 密钥——"
                    + "它专为 AI 检索设计且提供免费额度。）";
        }
        return render(query, "duckduckgo", items, true);
    }

    // ---------------- 渲染与 HTTP ----------------

    /**
     * 把结果渲染成模型最好用的形状：每条一段，标题 / 链接 / 摘要。
     *
     * <p>末尾那句"引用要求"是有意加的提示词 —— 让模型在回答里带上链接，
     * 用户才能自己核对，这是"可验证"与"看起来像真的"之间的差别。</p>
     *
     * <p>包级可见（而非 private）以便测试直接断言渲染结果（尤其是"兜底后端要标 limited"这一条）。</p>
     */
    static String render(String query, String provider, List<Map<String, String>> items, boolean limited) {
        StringBuilder sb = new StringBuilder();
        sb.append("搜索「").append(query).append("」得到 ").append(items.size()).append(" 条结果");
        sb.append("（来源：").append(provider);
        if (limited) {
            sb.append("，覆盖范围有限（未配置搜索密钥）");
        }
        sb.append("）：\n\n");

        int i = 1;
        for (Map<String, String> item : items) {
            sb.append(i++).append(". ").append(blankTo(item.get("title"), "(无标题)")).append('\n');
            sb.append("   链接：").append(blankTo(item.get("url"), "(无)")).append('\n');
            String snippet = item.get("snippet");
            if (snippet != null && !snippet.isBlank()) {
                sb.append("   摘要：").append(clip(snippet)).append('\n');
            }
            sb.append('\n');
        }
        sb.append("请在回答中引用上述真实链接，不要凭印象补充链接。");
        return sb.toString();
    }

    private JsonNode execute(Request request, SearchConfig cfg) throws Exception {
        try (Response response = clientFor(cfg.timeoutMs()).newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code() + " " + snippet(body));
            }
            if (body.isBlank()) {
                throw new IllegalStateException("响应为空");
            }
            return JsonUtils.toJsonNode(body);
        }
    }

    private OkHttpClient clientFor(int timeoutMs) {
        return clientsByTimeout.computeIfAbsent(timeoutMs, ms -> new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(Math.min(ms, 6000)))
                .readTimeout(Duration.ofMillis(ms))
                .build());
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return s;   // UTF-8 一定存在，这里不可达
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.path(field);
        return v == null || v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String clip(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= SNIPPET_LIMIT ? t : t.substring(0, SNIPPET_LIMIT) + "…";
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    // ---------------- 配置 ----------------

    /**
     * 一次挂载的搜索配置快照（不可变）。
     *
     * @param provider   检索后端；留空时按"有没有密钥"自动决定
     * @param apiKey     Tavily 密钥；空表示走 DuckDuckGo 兜底
     * @param baseUrl    检索服务地址（默认 Tavily 官方端点；
     *                   可换成自建/中转的兼容服务，也便于测试对着本地 mock 断言请求体）
     * @param maxResults 返回条数上限
     * @param timeoutMs  单次检索超时
     */
    public record SearchConfig(String provider, String apiKey, String baseUrl,
                               int maxResults, int timeoutMs) {

        static SearchConfig defaults() {
            return new SearchConfig(null, null, null, DEFAULT_MAX_RESULTS, DEFAULT_TIMEOUT_MS);
        }

        public static SearchConfig of(JsonNode config) {
            if (config == null || config.isNull()) {
                return defaults();
            }
            return new SearchConfig(
                    blankToNull(str(config, CFG_PROVIDER)),
                    blankToNull(str(config, CFG_API_KEY)),
                    blankToNull(str(config, CFG_BASE_URL)),
                    (int) clamp(num(config, CFG_MAX_RESULTS, DEFAULT_MAX_RESULTS), 1, HARD_MAX_RESULTS),
                    (int) clamp(num(config, CFG_TIMEOUT_MS, DEFAULT_TIMEOUT_MS), 500, HARD_MAX_TIMEOUT_MS));
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /**
         * 实际使用的后端：用户显式指定的优先，否则<b>按有没有密钥自动选</b>。
         *
         * <p>自动选的理由：绝大多数用户只想"能用"，不想理解 provider 这个概念。
         * 填了密钥就用最好的，没填就退回零配置兜底 —— 两个动作各自都自洽。</p>
         */
        public String effectiveProvider() {
            if (provider != null) {
                return provider;
            }
            /*
             * 默认后端（2026-10-21 改）：**没有密钥时走必应页面抓取**。
             *
             * 原来是 DuckDuckGo —— 它的 API 域名在国内直连超时，等于"零配置不可用"。
             * 现在零配置也能搜到东西，`duckduckgo` 降为显式选项（海外部署可用）。
             */
            return hasApiKey() ? PROVIDER_TAVILY : PROVIDER_BING;
        }

        /**
         * Tavily 检索端点。
         *
         * <p>容错用户的填法：裸域名 / 带尾斜杠 / 直接填完整 {@code /search} 端点，
         * 都能解析成同一个地址（与 TTS / 图片插件的 URL 规则一致）。</p>
         */
        public String tavilyUrl() {
            String base = baseUrl == null ? DEFAULT_TAVILY_BASE_URL : baseUrl.trim().replaceAll("/+$", "");
            return base.endsWith("/search") ? base : base + "/search";
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

        private static double clamp(double v, double min, double max) {
            return Math.max(min, Math.min(max, v));
        }
    }
}
