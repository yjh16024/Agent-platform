package com.agentplatform.core.plugin.builtin;

import com.agentplatform.framework.api.Tool;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.plugin.sdk.ConfigFieldDef;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginDescriptor;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置插件：<b>网页内容与热门</b>（2026-10-21）。
 *
 * <h3>它补的是什么缺口</h3>
 * {@code web_search} 只返回「标题 / 链接 / 摘要」，模型知道"有这个页面"，但<b>读不到正文</b>。
 * 于是遇到"这篇讲了什么""这个链接里说了啥"时只能猜。本插件提供两件事：
 *
 * <ol>
 *   <li>{@code web_fetch}：抓一个 URL 并<b>提取正文</b>（去脚本/样式/导航，优先 {@code <article>}）；</li>
 *   <li>{@code bilibili_hot}：B 站热门榜（公开 API，<b>零配置</b>）—— 给"主动找话题"一个内容源。</li>
 * </ol>
 *
 * <h3>为什么正文提取是手写的（没有用 jsoup / Readability）</h3>
 * 项目离线构建，引入新依赖要保证本地仓库有包，风险大于收益。这里的规则集对
 * <b>新闻 / 博客 / 文档 / 百科</b>这类结构化页面够用；对纯 JS 渲染页（小红书、抖音）
 * 本来也抓不到 —— 那类需要浏览器自动化，不在本插件的目标里。
 *
 * <h3>为什么只做 B 站</h3>
 * 实测（2026-10-21）：B 站热门 {@code /x/web-interface/popular} 返回 200 且 code=0；
 * 而 B 站搜索接口 412（要 WBI 签名 + cookie）、微博热搜 403（要登录态）、
 * 抖音/小红书要请求签名 + 登录。**能零配置稳定拿到的只有 B 站热门**，
 * 其余平台强行破解的维护成本远超收益。
 */
@Component
@Slf4j
public class BuiltinWebContentPlugin implements ToolProvider, PluginDescriptor {

    /** 与 plugin_def 中的 plugin_id 一致。 */
    public static final String PLUGIN_ID = "builtin_web_content";

    public static final String TOOL_FETCH = "web_fetch";
    public static final String TOOL_BILI_HOT = "bilibili_hot";
    public static final String TOOL_RSS = "rss_fetch";

    /**
     * 图片搜索（2026-10-21）。
     *
     * <p><b>为什么需要它</b>：`web_search` 返回的是**网页**列表，而"给自己换个头像"
     * 需要的是一张**能直接下载的图片直链**。模型在 QQ 插件里就是这样卡住的 ——
     * 它明确说「I don't actually have an image search tool」，只能拿网页地址干瞪眼。</p>
     *
     * <p>走必应图片搜索的页面抓取：不需要密钥（和 {@code web_search} 的 Bing 后端同一个思路），
     * 页面里每张图都带 {@code murl} 字段（原始图片地址），实测一次能拿到 30 条。</p>
     */
    public static final String TOOL_IMAGE = "image_search";

    /** 必应图片搜索（页面抓取，零配置）。 */
    private static final String BING_IMAGE_URL = "https://cn.bing.com/images/search";

    /**
     * 百度图片的 JSON 接口（2026-10-22 加，**现在是默认引擎**）。
     *
     * <p>为什么换掉 Bing：实测搜「动漫 白发 少女 插画 头像」，Bing 返回
     * "白银还有近 20% 上涨空间""9999 银粒定制""张又侠致辞" —— 中文分词把"银白发"
     * 切成了"白银"，完全跑偏。同一句话给百度，30 条全是
     * "白发头像女动漫""白发动漫女生头像"，且图片来自 {@code img*.baidu.com}
     * （自家 CDN，直连下载 200 / 37~52KB，无防盗链）。</p>
     */
    private static final String BAIDU_IMAGE_URL = "https://image.baidu.com/search/acjson";

    /** 360 图片（备用引擎，实测结果偏少，仅作兜底）。 */
    private static final String SO_IMAGE_URL = "https://image.so.com/j";

    /**
     * 看一个 B 站视频（2026-10-23）。
     *
     * <p><b>为什么需要</b>：原来只有 {@code bilibili_hot}，她只能看到榜单标题 ——
     * "知道有这些视频"，但**看不到内容**，也没法说"这个视频讲了什么"。</p>
     *
     * <p>视频的内容信息 90% 在**字幕**里，情绪和梗在**弹幕**里。所以这个工具做三件事：
     * 取详情（标题/简介/时长/播放）、取弹幕（前 30 条）、取 AI 字幕（**需要登录 cookie**）。
     * 没有 cookie 时字幕拿不到，但详情和弹幕仍然可用 —— 零配置也能跑。</p>
     */
    public static final String TOOL_BILI_VIDEO = "bilibili_video";

    /**
     * 搜歌（2026-10-25）。
     *
     * <p><b>为什么需要</b>：她之前说"这首"，然后开始描述"一个人一把吉他、很慢" ——
     * 因为她**手上没有任何音乐能力**，连歌名都给不出，只能演。有了这个工具，
     * 她可以说出歌名 + 发一个能点开的链接。</p>
     *
     * <p>走 QQ 音乐的公开搜索接口，实测**不需要签名、不需要登录**，
     * `format=json` 去掉 JSONP 包装。</p>
     */
    public static final String TOOL_MUSIC_SEARCH = "music_search";

    /** QQ 音乐搜索（公开接口，无需 key）。 */
    private static final String QQ_MUSIC_SEARCH = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp";

    /** 视频详情（bvid → aid/cid/标题/简介）。 */
    private static final String BILI_VIEW = "https://api.bilibili.com/x/web-interface/view";
    /** 播放器信息（含字幕列表，需要 SESSDATA）。 */
    private static final String BILI_PLAYER = "https://api.bilibili.com/x/player/v2";
    /** 弹幕 XML。 */
    private static final String BILI_DANMAKU = "https://api.bilibili.com/x/v1/dm/list.so";

    /**
     * 堆糖搜索接口（2026-10-22，**画师站首选**）。
     *
     * <p>实测：200 / 339ms / 24 条，图片 {@code a-ho-img.dtstatic.com} 直连下载
     * 200 / 349~578KB，尺寸 1440×1440。堆糖是国内最大的头像/插画社区，
     * 质量明显高于搜索引擎的通用结果。</p>
     */
    private static final String DUITANG_SEARCH_URL = "https://www.duitang.com/napi/blog/list/by_search/";

    /**
     * 花瓣搜索接口（2026-10-22，画师站次选）。
     *
     * <p>实测：200 / 407ms / 20 条，{@code pins[].file.url} 自带 auth_key 签名
     * （{@code gd-hbimg-edge.huaban.com}），也可用 key 拼 {@code hbimg.huaban.com/<key>}，
     * 两者都 200 / 222KB，尺寸 1080×1440。</p>
     */
    private static final String HUABAN_SEARCH_URL = "https://huaban.com/v3/search/pin";

    /** 抓页面必须带浏览器 UA：默认 UA 常被返回简化页或验证码页。 */
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 正文默认与上限（字符数）—— 太长会明显吃上下文与费用。 */
    private static final int DEFAULT_MAX_CHARS = 8000;
    private static final int HARD_MAX_CHARS = 30000;

    /** 单页字节上限：超过基本是二进制或异常页面。 */
    private static final long MAX_BYTES = 3L * 1024 * 1024;

    private static final String BILI_POPULAR = "https://api.bilibili.com/x/web-interface/popular";

    private final ObjectMapper mapper = new ObjectMapper();

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(20))
            .followRedirects(true)
            .build();

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
        return "网页内容与热门";
    }

    @Override
    public String description() {
        return "给智能体两个工具：web_fetch 读取任意网页的正文（新闻/博客/文档/百科），"
                + "bilibili_hot 取 B 站热门榜（零配置）。"
                + "适合「看看这篇文章讲了什么」「最近有什么值得聊的」这类需求。"
                + "需在对话页打开「工具」开关。";
    }

    /**
     * 挂载 / 卸载钩子（`Plugin` 接口里是抽象方法，必须显式实现）。
     *
     * <p>本插件**无状态**：HTTP 客户端是共享的，没有按 agent 缓存的配置，
     * 所以这里只记一行日志，不做任何初始化与清理。</p>
     */
    @Override
    public void onAttach(PluginContext ctx) {
        log.info("[web-content] 已挂载到 agent={}", ctx.agentId());
    }

    @Override
    public void onDetach(PluginContext ctx) {
        /* 无状态，无需清理 */
    }

    // ---------------- 工具声明 ----------------

    @Override
    public List<PluginTool> provideTools() {
        ObjectNode fetchSchema = mapper.createObjectNode();
        fetchSchema.put("type", "object");
        ObjectNode fetchProps = fetchSchema.putObject("properties");
        fetchProps.putObject("url")
                .put("type", "string")
                .put("description", "要读取的网页地址，必须是 http:// 或 https:// 开头的绝对地址");
        fetchProps.putObject("maxChars")
                .put("type", "integer")
                .put("description", "正文最多返回多少字符，默认 " + DEFAULT_MAX_CHARS
                        + "，上限 " + HARD_MAX_CHARS);
        fetchSchema.putArray("required").add("url");

        ObjectNode biliSchema = mapper.createObjectNode();
        biliSchema.put("type", "object");
        biliSchema.putObject("properties").putObject("limit")
                .put("type", "integer")
                .put("description", "返回条数，默认 10，上限 20");

        ObjectNode biliVideoSchema = mapper.createObjectNode();
        biliVideoSchema.put("type", "object");
        ObjectNode bvProps = biliVideoSchema.putObject("properties");
        bvProps.putObject("bvid")
                .put("type", "string")
                .put("description", "BV 号或完整链接（BV1xx411c7mD 或 https://www.bilibili.com/video/BV...）");
        bvProps.putObject("maxSubtitleChars")
                .put("type", "integer")
                .put("description", "字幕最多带回多少字，默认 3000（太长会淹掉对话）");
        biliVideoSchema.putArray("required").add("bvid");

        ObjectNode musicSchema = mapper.createObjectNode();
        musicSchema.put("type", "object");
        ObjectNode msProps = musicSchema.putObject("properties");
        msProps.putObject("keyword")
                .put("type", "string")
                .put("description", "歌名，或「歌名 歌手」（如「晴天 周杰伦」）—— 带上歌手更准");
        msProps.putObject("limit")
                .put("type", "integer")
                .put("description", "返回几条，默认 3，最多 10");
        musicSchema.putArray("required").add("keyword");

        ObjectNode rssSchema = mapper.createObjectNode();
        rssSchema.put("type", "object");
        ObjectNode rssProps = rssSchema.putObject("properties");
        rssProps.putObject("url")
                .put("type", "string")
                .put("description", "RSS / Atom 源的地址（http/https 绝对地址）");
        rssProps.putObject("limit")
                .put("type", "integer")
                .put("description", "取前几条，默认 10，上限 30");
        rssProps.putObject("maxChars")
                .put("type", "integer")
                .put("description", "每条摘要最多多少字符，默认 200");
        rssSchema.putArray("required").add("url");

        ObjectNode imgSchema = mapper.createObjectNode();
        imgSchema.put("type", "object");
        ObjectNode imgProps = imgSchema.putObject("properties");
        imgProps.putObject("query")
                .put("type", "string")
                .put("description", "图片搜索关键词，越具体越好（例如「银白发少年 插画 头像」而不是「好看」）");
        imgProps.putObject("limit")
                .put("type", "integer")
                .put("description", "返回几张，默认 10，上限 20");
        imgProps.putObject("engine")
                .put("type", "string")
                .put("description", "图源。默认 auto：**先搜画师站（堆糖、花瓣）**，质量高；"
                        + "不够再用百度补。也可指定 duitang / huaban / baidu / bing / so。"
                        + "找头像、插画时保持默认 auto 即可");
        imgProps.putObject("site")
                .put("type", "string")
                .put("description", "限定站点，**仅 bing 引擎有效**。找头像/插画可传 duitang.com"
                        + "（堆糖，实测 90% 结果来自它自己的图床）。百度不支持 site 语法");
        imgSchema.putArray("required").add("query");

        return List.of(
                new PluginTool(TOOL_FETCH,
                        "读取一个网页的正文内容。当用户给出链接、或你需要确认某篇文章/某条新闻的具体内容时使用。"
                                + "返回页面的标题与正文（自动剔除脚本、样式、导航）。"
                                + "注意：纯 JS 渲染或需要登录的页面（如小红书、抖音）抓不到正文。",
                        fetchSchema,
                        this::handleFetch),
                new PluginTool(TOOL_BILI_HOT,
                        "获取 B 站当前的热门视频榜（标题 / UP 主 / 播放量 / 链接）。"
                                + "当你想找点「最近大家都在看什么」的话题时可以用它 —— "
                                + "比凭空猜测流行内容可靠。",
                        biliSchema,
                        this::handleBiliHot),
                new PluginTool(TOOL_BILI_VIDEO,
                        "看一个 B 站视频：取标题、简介、时长、播放量、**热门弹幕**，"
                                + "以及 **AI 字幕全文**（字幕需要配置 B 站 cookie，见 biliSessdata）。"
                                + "要聊某个视频讲了什么、弹幕什么反应时用它。"
                                + "⚠️ 没字幕的视频只能拿到简介和弹幕 —— 这时如实说\"没字幕\"，"
                                + "不要凭标题编内容。",
                        biliVideoSchema,
                        this::handleBiliVideo),
                new PluginTool(TOOL_MUSIC_SEARCH,
                        "搜一首歌，返回歌名、歌手、专辑、时长和**可以直接点开的 QQ 音乐链接**。"
                                + "想推荐歌、聊到某首歌时用它 —— 拿到链接再发出去，"
                                + "比只说「这首」强得多（你没法真的播放音乐，别装）。",
                        musicSchema,
                        this::handleMusicSearch),
                new PluginTool(TOOL_RSS,
                        "读取一个 RSS / Atom 订阅源，返回最近若干条的标题、时间、链接与摘要。"
                                + "适合订阅新闻站、博客、以及由 RSSHub 生成的社交平台源"
                                + "（例如微博热搜 /weibo/search/hot、抖音热榜 /douyin/hot、"
                                + "B 站 UP 主 /bilibili/user/video/:uid）。",
                        rssSchema,
                        this::handleRss),
                new PluginTool(TOOL_IMAGE,
                        "搜索网络图片，返回**可以直接下载的图片直链**（jpg/png/gif/webp）与标题。"
                                + "需要一张具体的图时用它（例如换头像、找配图）—— "
                                + "注意它和 web_search 的区别：web_search 给的是网页地址，"
                                + "这个给的是图片本身的地址，可以直接拿去下载/设置头像。"
                                + "返回的是地址列表；**搜索完成后系统会把前 3 张图直接给你看**，"
                                + "看着挑即可。若要把某张发给对方，用 `[发图 <图片地址>]`。"
                                + "默认 engine=auto：**先搜画师站（堆糖、花瓣）**，质量更高；"
                                + "不够再用百度补齐。找头像/插画保持默认即可。",
                        imgSchema,
                        this::handleImageSearch));
    }

    /**
     * 挂载配置（2026-10-23）。
     *
     * <p>只有一项：B 站 `SESSDATA`，给 {@code bilibili_video} 取 AI 字幕用。</p>
     *
     * <p><b>不填也能用</b> —— 视频详情和弹幕走公开接口就能拿到，只是缺"内容"这一半。
     * 填了之后她才说得清"这个视频讲了什么"，而不是只看到弹幕在刷梗。</p>
     */
    @Override
    public List<ConfigFieldDef> configFields() {
        return List.of(
                new ConfigFieldDef(
                        "biliSessdata",
                        "B 站 SESSDATA（可选，用于读视频字幕）",
                        ConfigFieldDef.Kind.text,
                        "",
                        false,
                        true,
                        null,
                        null,
                        null,
                        null,
                        "粘贴 SESSDATA 的值",
                        "登录 bilibili.com → F12 → Application → Cookies → 复制 SESSDATA。"
                                + "不填也能用（详情 + 弹幕），但拿不到 AI 字幕，她就说不了视频讲了什么。"
                                + "**建议用小号** —— 这个值等于登录态。"));
    }

    // ---------------- image_search ----------------

    /**
     * 必应图片搜索（页面抓取，2026-10-21）。
     *
     * <p>解析思路：结果页里每张图对应一个 {@code <a class="iusc" m="{...}">}，
     * 它的 {@code m} 属性是段 JSON（HTML 实体转义过），其中 {@code murl} 是**原始图片地址**、
     * {@code t} 是标题。只保留带图片扩展名的地址 —— 少数结果指向网页而不是图。</p>
     *
     * <p>代价与 {@code web_search} 的 Bing 后端一致：页面改版会让解析失效，届时抛异常
     * 让模型知道"这次没搜到"，而不是返回空列表被它当成"没有这张图"。</p>
     */
    private JsonNode handleImageSearch(JsonNode args, PluginContext ctx) {
        String query = args == null ? null : args.path("query").asText(null);
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("参数 query 不能为空");
        }
        int limit = clamp(args.path("limit").asInt(10), 1, 20);
        String q = query.trim();
        String engine = args.path("engine").asText("auto").trim().toLowerCase(java.util.Locale.ROOT);
        String site = args.path("site").asText("").trim();

        /*
         * 图源选择（2026-10-22）。
         *
         * 默认 auto = **画师站优先**：堆糖 → 花瓣 → 百度。
         *
         * 为什么不用"搜索引擎 + site: 限定"：实测百度和必应**都不认** site 语法 ——
         * 六个站点（huaban / pixiv / duitang / lofter / weibo）的返回完全一样。
         * 所以只能直连画师站自己的搜索接口。实测两者质量明显高于通用引擎：
         *   堆糖 1440×1440、578KB；花瓣 1080×1440、222KB。
         *
         * 为什么仍保留百度兜底：画师站只覆盖头像/插画，查"某产品图""新闻配图"时
         * 通用引擎才管用。
         */
        String label;
        List<String[]> items = new ArrayList<>();
        try {
            switch (engine) {
                case "duitang" -> {
                    items = searchDuitang(q, limit);
                    label = "堆糖";
                }
                case "huaban" -> {
                    items = searchHuaban(q, limit);
                    label = "花瓣";
                }
                case "baidu" -> {
                    items = searchBaiduImages(q, limit);
                    label = "百度";
                }
                case "bing" -> {
                    if (!site.isEmpty() && !site.matches("^[A-Za-z0-9.\\-]+$")) {
                        throw new IllegalArgumentException("site 只接受域名，例如 duitang.com");
                    }
                    items = searchBingImages(site.isEmpty() ? q : q + " site:" + site, limit);
                    label = site.isEmpty() ? "Bing" : "Bing/" + site;
                }
                case "so" -> {
                    items = searchSoImages(q, limit);
                    label = "360";
                }
                default -> {
                    /*
                     * auto：逐个源补齐，**单个源失败不影响整体** ——
                     * "画师站搜不到就退回引擎"正是这个模式的全部意义，
                     * 不能因为堆糖超时就让整次搜索失败。
                     */
                    List<String> used = new ArrayList<>();
                    items.addAll(trySource("堆糖", used, () -> searchDuitang(q, limit)));
                    if (items.size() < limit) {
                        int need = limit - items.size();
                        items.addAll(trySource("花瓣", used, () -> searchHuaban(q, need)));
                    }
                    if (items.size() < limit) {
                        int need = limit - items.size();
                        items.addAll(trySource("百度", used, () -> searchBaiduImages(q, need)));
                    }
                    items = dedupe(items, limit);
                    label = used.isEmpty() ? "无可用图源" : String.join(" + ", used);
                }
            }
        } catch (Exception e) {
            log.warn("[web-content] image_search failed query={} engine={} reason={}", q, engine, e.getMessage());
            throw new IllegalStateException("图片搜索失败（" + engine + "）：" + e.getMessage()
                    + "。可以换关键词，或换 engine（auto / duitang / huaban / baidu / bing / so）重试。");
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("图片搜索没有结果（" + engine + "）：可能被要求人机验证，或关键词太生僻。");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("引擎：").append(label).append("　关键词：").append(q).append('\n');
        sb.append("返回 ").append(items.size()).append(" 张（下面是图片直链，可直接下载）：\n\n");
        for (int i = 0; i < items.size(); i++) {
            String[] item = items.get(i);
            sb.append(i + 1).append(". ").append(item[0].isBlank() ? "（无标题）" : item[0]).append('\n');
            sb.append("   ").append(item[1]).append('\n');
        }
        log.info("[web-content] image_search ok engine={} query={} items={}", engine, q, items.size());
        return JsonNodeFactory.instance.textNode(sb.toString());
    }

    /**
     * 尝试一个图源；失败只记日志并返回空列表。
     *
     * <p>auto 模式的语义是"画师站搜不到就退回引擎" —— 所以单个源超时、被限流
     * **绝不能**让整次搜索失败。</p>
     */
    private List<String[]> trySource(String name, List<String> used, SourceCall call) {
        try {
            List<String[]> got = call.get();
            if (got != null && !got.isEmpty()) {
                used.add(name);
                return got;
            }
        } catch (Exception e) {
            log.warn("[web-content] {} 搜索失败: {}", name, e.getMessage());
        }
        return List.of();
    }

    /** 可抛异常的图源调用（各 searchXxx 都抛 IOException）。 */
    @FunctionalInterface
    private interface SourceCall {
        List<String[]> get() throws Exception;
    }

    /** 按 URL 去重并截断（不同图源可能返回同一张图）。 */
    static List<String[]> dedupe(List<String[]> items, int limit) {
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] it : items) {
            if (out.size() >= limit) {
                break;
            }
            if (it == null || it.length < 2 || it[1] == null || !seen.add(it[1])) {
                continue;
            }
            out.add(it);
        }
        return out;
    }

    // ---------------- image_search：堆糖（画师站首选） ----------------

    private List<String[]> searchDuitang(String query, int limit) throws IOException {
        String url = DUITANG_SEARCH_URL + "?kw=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&start=0&limit=" + Math.min(limit * 2, 40) + "&type=feed";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", "https://www.duitang.com/")
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            String text = response.body() == null ? "" : response.body().string();
            return parseDuitang(text, limit);
        }
    }

    /** 解析堆糖搜索结果：{@code data.object_list[].photo.path} + {@code msg}。 */
    static List<String[]> parseDuitang(String json, int limit) {
        List<String[]> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = JsonUtils.toJsonNode(json);
        } catch (Exception e) {
            return out;
        }
        JsonNode list = root.path("data").path("object_list");
        if (!list.isArray()) {
            return out;
        }
        for (JsonNode o : list) {
            if (out.size() >= limit) {
                break;
            }
            if (o == null || o.isNull()) {
                continue;
            }
            String img = o.path("photo").path("path").asText("");
            if (img.isEmpty()) {
                continue;
            }
            out.add(new String[]{o.path("msg").asText("").trim(), img});
        }
        return out;
    }

    // ---------------- image_search：花瓣 ----------------

    private List<String[]> searchHuaban(String query, int limit) throws IOException {
        String url = HUABAN_SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=" + Math.min(limit * 2, 40);
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept", "application/json")
                .header("Referer", "https://huaban.com/")
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            String text = response.body() == null ? "" : response.body().string();
            return parseHuaban(text, limit);
        }
    }

    /**
     * 解析花瓣搜索结果。
     *
     * <p>{@code pins[].file.url} 自带 {@code auth_key} 签名，可直接下载；
     * 没有 url 时用 {@code file.key} 拼 {@code hbimg.huaban.com/<key>}（实测两种都 200）。</p>
     */
    static List<String[]> parseHuaban(String json, int limit) {
        List<String[]> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = JsonUtils.toJsonNode(json);
        } catch (Exception e) {
            return out;
        }
        JsonNode pins = root.path("pins");
        if (!pins.isArray()) {
            return out;
        }
        for (JsonNode p : pins) {
            if (out.size() >= limit) {
                break;
            }
            if (p == null || p.isNull()) {
                continue;
            }
            JsonNode file = p.path("file");
            String img = file.path("url").asText("");
            if (img.isEmpty()) {
                String key = file.path("key").asText("");
                if (!key.isEmpty()) {
                    img = "https://hbimg.huaban.com/" + key;
                }
            }
            if (img.isEmpty()) {
                continue;
            }
            out.add(new String[]{p.path("raw_text").asText("").trim(), img});
        }
        return out;
    }

    // ---------------- image_search：百度 ----------------

    private List<String[]> searchBaiduImages(String query, int limit) throws IOException {
        String enc = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = BAIDU_IMAGE_URL
                + "?tn=resultjson_com&ipn=rj&ct=201326592&fp=result&queryWord=" + enc
                + "&cl=2&lm=-1&ie=utf-8&oe=utf-8&st=-1&word=" + enc
                + "&pn=0&rn=" + Math.min(limit * 3, 60);
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", "https://image.baidu.com/")
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            String text = response.body() == null ? "" : response.body().string();
            return parseBaiduImages(text, limit);
        }
    }

    /**
     * 解析百度图片的 acjson 结果。
     *
     * <p>取 {@code data[].middleURL}（约 500×500 的中图，对头像足够），
     * 没有则退到 {@code thumbURL}。标题用 {@code fromPageTitleEnc}（HTML 实体编码），
     * 里面带 {@code <em>} 高亮标签，要剥掉。</p>
     *
     * <p>百度偶尔返回**非严格 JSON**（含未转义控制字符），解析失败时返回空列表
     * 而不是抛异常 —— 上层会给出"没有结果"，这比一个看不懂的解析错误更有用。</p>
     */
    static List<String[]> parseBaiduImages(String json, int limit) {
        List<String[]> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = JsonUtils.toJsonNode(json);
        } catch (Exception e) {
            return out;
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            return out;
        }
        for (JsonNode d : data) {
            if (out.size() >= limit) {
                break;
            }
            if (d == null || d.isNull()) {
                continue;
            }
            String img = d.path("middleURL").asText("");
            if (img.isEmpty()) {
                img = d.path("thumbURL").asText("");
            }
            if (img.isEmpty()) {
                continue;
            }
            String title = d.path("fromPageTitleEnc").asText("");
            if (title.isEmpty()) {
                title = d.path("fromPageTitle").asText("");
            }
            title = decodeEntities(title).replaceAll("<[^>]+>", "").trim();
            out.add(new String[]{title, img});
        }
        return out;
    }

    // ---------------- image_search：必应 ----------------

    private List<String[]> searchBingImages(String query, int limit) throws IOException {
        String url = BING_IMAGE_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&first=1";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            String html = response.body() == null ? "" : response.body().string();
            return parseBingImages(html, limit);
        }
    }

    // ---------------- image_search：360（备用） ----------------

    private List<String[]> searchSoImages(String query, int limit) throws IOException {
        String url = SO_IMAGE_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&pn=0&rn=" + Math.min(limit * 3, 60) + "&src=srp";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", "https://image.so.com/")
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            String text = response.body() == null ? "" : response.body().string();
            return parseSoImages(text, limit);
        }
    }

    /** 解析 360 图片的 JSON（{@code list[].img / thumb}）。 */
    static List<String[]> parseSoImages(String json, int limit) {
        List<String[]> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = JsonUtils.toJsonNode(json);
        } catch (Exception e) {
            return out;
        }
        JsonNode list = root.path("list");
        if (!list.isArray()) {
            return out;
        }
        for (JsonNode d : list) {
            if (out.size() >= limit) {
                break;
            }
            if (d == null || d.isNull()) {
                continue;
            }
            String img = d.path("img").asText("");
            if (img.isEmpty()) {
                img = d.path("thumb").asText("");
            }
            if (img.isEmpty()) {
                continue;
            }
            out.add(new String[]{d.path("title").asText("").trim(), img});
        }
        return out;
    }

    /**
     * 从必应图片结果页里抽「标题 + 原始图片地址」。
     *
     * <p><b>必须以结果卡片为单位解析</b>（2026-10-21 修正）。第一版是"全局找 murl，
     * 再往前 400 字符找标题"，结果标题全是空的 —— 实测必应的 {@code m} 属性里
     * {@code t}（标题）在 {@code murl} **之后**，而整张卡片有 1200~1600 字符，
     * 往前找既方向错、范围也不够。</p>
     *
     * <p>现在按 {@code <a ... class="iusc" ...>} 切块，每块里各取各的，天然对齐。</p>
     */
    static List<String[]> parseBingImages(String html, int limit) {
        List<String[]> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        Matcher card = Pattern.compile("(?s)<a[^>]+class=\"iusc\"[^>]*>").matcher(html);
        while (card.find() && out.size() < limit) {
            String block = card.group();
            Matcher m = Pattern.compile("murl&quot;:&quot;(https?://[^&]+)&quot;").matcher(block);
            if (!m.find()) {
                continue;
            }
            String imgUrl = decodeEntities(m.group(1));
            // 只保留真正的图片地址；少数 murl 指向网页
            if (!imgUrl.matches("(?i).*\\.(jpe?g|png|gif|webp)(\\?.*)?$")) {
                continue;
            }
            /*
             * `t&quot;:&quot;` 不会误匹配 `turl` / `cturl`（那两者 `t` 后面是别的字母），
             * 所以这里取到的就是标题。多个时取最后一个（`t` 通常在属性末尾）。
             */
            String title = "";
            Matcher tm = Pattern.compile("t&quot;:&quot;([^&]{1,120})&quot;").matcher(block);
            while (tm.find()) {
                title = decodeEntities(tm.group(1));
            }
            out.add(new String[]{title, imgUrl});
        }
        return out;
    }

    // ---------------- web_fetch ----------------

    private JsonNode handleFetch(JsonNode args, PluginContext ctx) {
        String url = args == null ? null : args.path("url").asText(null);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("参数 url 不能为空");
        }
        String trimmedUrl = url.trim();
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            throw new IllegalArgumentException("url 必须是 http:// 或 https:// 开头的绝对地址");
        }
        int maxChars = clamp(args.path("maxChars").asInt(DEFAULT_MAX_CHARS), 500, HARD_MAX_CHARS);
        assertPublicUrl(trimmedUrl);   // SSRF：只许公网

        try {
            String html = fetchHtml(trimmedUrl);
            if (html == null || html.isEmpty()) {
                return JsonNodeFactory.instance.textNode("（页面为空或无法解析）");
            }
            String title = extractTag(html, "title");
            String text = extractMainText(html);
            if (text.isBlank()) {
                return JsonNodeFactory.instance.textNode(
                        "（该页面没有可提取的正文：可能是纯 JS 渲染、需要登录，或全部内容都在脚本里）");
            }
            boolean truncated = text.length() > maxChars;
            String body = truncated ? text.substring(0, maxChars) : text;

            StringBuilder sb = new StringBuilder();
            if (!title.isBlank()) {
                sb.append("标题：").append(title).append('\n');
            }
            sb.append("正文：\n").append(body);
            if (truncated) {
                sb.append("\n…（正文过长已截断，仅保留前 ").append(maxChars).append(" 字符）");
            }
            log.info("[web-content] fetch ok url={} chars={} truncated={}",
                    trimmedUrl, text.length(), truncated);
            return JsonNodeFactory.instance.textNode(sb.toString());
        } catch (Exception e) {
            log.warn("[web-content] fetch failed url={} reason={}", trimmedUrl, e.getMessage());
            throw new IllegalStateException("读取网页失败：" + e.getMessage()
                    + "。可以换一个链接，或直接用 web_search 的摘要回答。");
        }
    }

    private String fetchHtml(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            if (response.body() == null) {
                return "";
            }
            byte[] bytes = response.body().bytes();
            if (bytes.length > MAX_BYTES) {
                throw new IOException("页面过大（" + (bytes.length / 1024 / 1024) + "MB），已跳过");
            }
            /*
             * 编码：不猜 charset 了（要解析 meta 头，且猜错会乱码）。
             * 直接按 UTF-8 解 —— 主流站点已经是 UTF-8；GBK 老站会出现少量乱码，
             * 但"内容大体可读"比"为了少数站点引入编码探测"更划算。
             */
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** 正文提取：去脚本/样式/导航，优先 article/main，再退化为全页文本。 */
    static String extractMainText(String html) {
        String s = html;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        s = s.replaceAll("(?is)<nav[^>]*>.*?</nav>", " ");
        s = s.replaceAll("(?is)<footer[^>]*>.*?</footer>", " ");
        s = s.replaceAll("(?is)<aside[^>]*>.*?</aside>", " ");
        s = s.replaceAll("(?is)<form[^>]*>.*?</form>", " ");
        s = s.replaceAll("(?is)<!--.*?-->", " ");

        String article = firstGroup(s, "(?is)<article[^>]*>(.*?)</article>");
        if (article == null || article.isBlank()) {
            article = firstGroup(s, "(?is)<main[^>]*>(.*?)</main>");
        }
        String target = (article == null || article.isBlank()) ? s : article;

        String text = target.replaceAll("(?s)<[^>]+>", "\n");
        text = decodeEntities(text);
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        text = text.replaceAll("\\n[ ]*\\n+", "\n\n");
        text = text.replaceAll("(?m)^[ ]{1,}$", "");
        return text.trim();
    }

    private static String firstGroup(String source, String regex) {
        Matcher m = Pattern.compile(regex).matcher(source);
        return m.find() ? m.group(1) : null;
    }

    private static String extractTag(String html, String tag) {
        Matcher m = Pattern.compile("(?is)<" + tag + "[^>]*>(.*?)</" + tag + ">").matcher(html);
        if (!m.find()) {
            return "";
        }
        return decodeEntities(m.group(1).replaceAll("<[^>]+>", "")).trim();
    }

    /** 简单 HTML 实体解码（新闻页里 `&nbsp;` `&ldquo;` `&#8217;` 很常见）。 */
    static String decodeEntities(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text
                .replace("&nbsp;", " ")
                .replace("&ensp;", " ")
                .replace("&emsp;", " ")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&ldquo;", "\u201C")
                .replace("&rdquo;", "\u201D")
                .replace("&lsquo;", "'")
                .replace("&rsquo;", "'")
                .replace("&hellip;", "…")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&amp;", "&");
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
                replacement = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------- rss_fetch ----------------

    /**
     * 读取 RSS / Atom 源（2026-10-21）。
     *
     * <p><b>为什么值得单独做一个工具</b>：大量站点与社交平台没有稳定的开放 API，
     * 但 **RSSHub** 能把它们转成标准 RSS。有了这个工具，模型只要拿到一个 feed 地址
     * 就能读内容 —— 不必为每个平台各写一套抓取与签名（微博/抖音/小红书/B站 都是这么覆盖的）。</p>
     *
     * <p><b>XML 用 JDK 自带 DOM 且显式关掉 XXE</b>：feed 是外部输入，
     * 允许外部实体意味着对方能让解析器去读本机文件或发起内网请求。这不是洁癖，是必须。</p>
     */
    private JsonNode handleRss(JsonNode args, PluginContext ctx) {
        String url = args == null ? null : args.path("url").asText(null);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("参数 url 不能为空");
        }
        String feedUrl = url.trim();
        if (!feedUrl.startsWith("http://") && !feedUrl.startsWith("https://")) {
            throw new IllegalArgumentException("url 必须是 http:// 或 https:// 开头的绝对地址");
        }
        int limit = clamp(args.path("limit").asInt(10), 1, 30);
        int maxChars = clamp(args.path("maxChars").asInt(200), 50, 2000);
        assertPublicUrl(feedUrl);   // SSRF：只许公网

        try {
            byte[] bytes = fetchBytes(feedUrl);
            Document doc = parseXml(bytes);

            String channelTitle = textOfFirst(doc, "channel", "title");
            if (channelTitle.isBlank()) {
                channelTitle = textOfFirst(doc, "feed", "title");
            }

            NodeList items = doc.getElementsByTagName("item");
            boolean atom = false;
            if (items.getLength() == 0) {
                items = doc.getElementsByTagName("entry");
                atom = true;
            }
            if (items.getLength() == 0) {
                return JsonNodeFactory.instance.textNode(
                        "（没有解析到条目：这个地址可能不是 RSS/Atom，或是一个空源）");
            }

            StringBuilder sb = new StringBuilder();
            if (!channelTitle.isBlank()) {
                sb.append("订阅源：").append(channelTitle).append('\n');
            }
            sb.append("返回 ").append(Math.min(items.getLength(), limit)).append(" 条：\n\n");

            int count = 0;
            for (int i = 0; i < items.getLength() && count < limit; i++) {
                Node node = items.item(i);
                if (!(node instanceof Element item)) {
                    continue;
                }
                String title = childText(item, "title");
                String link = atom ? atomLink(item) : childText(item, "link");
                String time = firstNonBlank(
                        childText(item, "pubDate"),
                        childText(item, "published"),
                        childText(item, "updated"));
                String desc = stripHtml(firstNonBlank(
                        childText(item, "description"),
                        childText(item, "summary"),
                        childText(item, "content")));

                count++;
                sb.append(count).append(". ").append(title.isBlank() ? "（无标题）" : title).append('\n');
                if (!time.isBlank()) {
                    sb.append("   时间：").append(time).append('\n');
                }
                if (!link.isBlank()) {
                    sb.append("   链接：").append(link).append('\n');
                }
                if (!desc.isBlank()) {
                    sb.append("   摘要：")
                            .append(desc.length() > maxChars ? desc.substring(0, maxChars) + "…" : desc)
                            .append('\n');
                }
                sb.append('\n');
            }
            log.info("[web-content] rss ok url={} items={}", feedUrl, count);
            return JsonNodeFactory.instance.textNode(sb.toString());
        } catch (Exception e) {
            log.warn("[web-content] rss failed url={} reason={}", feedUrl, e.getMessage());
            throw new IllegalStateException("读取 RSS 失败：" + e.getMessage()
                    + "。请确认这是一个 RSS/Atom 地址（常见形式：/feed、/rss、.xml，或 RSSHub 生成的源）。");
        }
    }

    /** 取原始字节（XML 解析需要字节流，不能先转字符串）。 */
    private byte[] fetchBytes(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            if (response.body() == null) {
                return new byte[0];
            }
            byte[] bytes = response.body().bytes();
            if (bytes.length > MAX_BYTES) {
                throw new IOException("响应过大（" + (bytes.length / 1024 / 1024) + "MB）");
            }
            return bytes;
        }
    }

    /** 安全解析 XML：关掉 DTD 与外部实体（XXE 防护）。 */
    static Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    /** 取 `父元素/子元素` 的文本（限定在父节点内，避免取到页面别处的同名标签）。 */
    private static String textOfFirst(Document doc, String parentTag, String childTag) {
        NodeList parents = doc.getElementsByTagName(parentTag);
        if (parents.getLength() == 0) {
            return "";
        }
        Node parent = parents.item(0);
        if (!(parent instanceof Element el)) {
            return "";
        }
        return childText(el, childTag);
    }

    /** 取**直接子元素**的文本（`getElementsByTagName` 会递归，遇到嵌套结构会取错）。 */
    static String childText(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element el && tag.equals(el.getTagName())) {
                String text = el.getTextContent();
                return text == null ? "" : text.trim();
            }
        }
        return "";
    }

    /** Atom 的链接在属性里（`<link href="..."/>`）。 */
    static String atomLink(Element item) {
        NodeList children = item.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element el && "link".equals(el.getTagName())) {
                String href = el.getAttribute("href");
                if (href != null && !href.isBlank()) {
                    return href.trim();
                }
                String text = el.getTextContent();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    /** 摘要常带 HTML：复用与正文相同的思路去掉标签与实体。 */
    static String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String t = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ");
        t = decodeEntities(t);
        return t.replaceAll("\\s+", " ").trim();
    }

    // ---------------- bilibili_hot ----------------

    private JsonNode handleBiliHot(JsonNode args, PluginContext ctx) {
        int limit = clamp(args == null ? 10 : args.path("limit").asInt(10), 1, 20);
        String url = BILI_POPULAR + "?ps=" + limit + "&pn=1";
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .get()
                    .build();
            String body;
            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code());
                }
                body = response.body() == null ? "" : response.body().string();
            }

            JsonNode root = mapper.readTree(body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                throw new IllegalStateException("B 站返回 code=" + code
                        + "（可能需要登录或触发了风控）");
            }
            JsonNode list = root.path("data").path("list");
            if (!list.isArray() || list.isEmpty()) {
                throw new IllegalStateException("热门列表为空");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("B 站热门榜（共 ").append(list.size()).append(" 条）：\n");
            int index = 0;
            for (JsonNode video : list) {
                index++;
                String title = video.path("title").asText("");
                String up = video.path("owner").path("name").asText("");
                long view = video.path("stat").path("view").asLong(0);
                String bvid = video.path("bvid").asText("");
                String desc = video.path("desc").asText("");
                sb.append(index).append(". ").append(title).append('\n')
                        .append("   UP：").append(up).append("　播放：").append(view).append('\n');
                if (!desc.isBlank()) {
                    sb.append("   简介：").append(desc.length() > 80 ? desc.substring(0, 80) + "…" : desc)
                            .append('\n');
                }
                sb.append("   https://www.bilibili.com/video/").append(bvid).append('\n');
            }
            log.info("[web-content] bilibili hot ok count={}", list.size());
            return JsonNodeFactory.instance.textNode(sb.toString());
        } catch (Exception e) {
            log.warn("[web-content] bilibili hot failed: {}", e.getMessage());
            throw new IllegalStateException("获取 B 站热门失败：" + e.getMessage()
                    + "。可以换 web_search 试试。");
        }
    }

    // ---------------- bilibili_video ----------------

    /**
     * 看一个 B 站视频（2026-10-23）。
     *
     * <p>取三样东西：详情（标题/简介/时长/播放/UP）、热门弹幕、AI 字幕。
     * 字幕要 `SESSDATA`；没有就跳过，其余照常返回 —— **零配置也能用**。</p>
     *
     * <p>没有字幕时必须让模型知道"我看不到内容"，否则它会凭标题瞎编一大段。</p>
     */
    private JsonNode handleBiliVideo(JsonNode args, PluginContext ctx) {
        String raw = args == null ? "" : args.path("bvid").asText("").trim();
        String bvid = extractBvid(raw);
        if (bvid == null) {
            throw new IllegalArgumentException("bvid 不合法：要 BV 号或视频链接（含 /video/BV...）");
        }
        int maxChars = clamp(args.path("maxSubtitleChars").asInt(3000), 500, 8000);
        String sessdata = ctx == null ? "" : ctx.configAsString("biliSessdata", "");

        JsonNode data = biliJson(BILI_VIEW + "?bvid=" + bvid, sessdata, "视频详情");
        long aid = data.path("aid").asLong(0);
        long cid = data.path("cid").asLong(0);
        String title = data.path("title").asText("");
        String desc = data.path("desc").asText("");
        long duration = data.path("duration").asLong(0);
        String up = data.path("owner").path("name").asText("");
        long view = data.path("stat").path("view").asLong(0);
        long danmakuCount = data.path("stat").path("danmaku").asLong(0);

        StringBuilder sb = new StringBuilder();
        sb.append("【视频】").append(title).append('\n');
        sb.append("UP：").append(up).append("　时长：").append(fmtDuration(duration))
                .append("　播放：").append(view).append("　弹幕数：").append(danmakuCount).append('\n');
        if (!desc.isBlank()) {
            sb.append("简介：").append(desc.length() > 400 ? desc.substring(0, 400) + "…" : desc).append('\n');
        }
        sb.append("链接：https://www.bilibili.com/video/").append(bvid).append('\n');

        List<String> dm = fetchDanmaku(cid, 30);
        if (!dm.isEmpty()) {
            sb.append("\n【弹幕（前 ").append(dm.size()).append(" 条）】\n");
            for (String d : dm) {
                sb.append("  · ").append(d).append('\n');
            }
        }

        String subtitle = fetchSubtitle(aid, cid, sessdata, maxChars);
        if (subtitle != null && !subtitle.isBlank()) {
            sb.append("\n【字幕】\n").append(subtitle).append('\n');
        } else if (sessdata.isBlank()) {
            sb.append("\n【字幕】拿不到 —— 没有配置 B 站 cookie（biliSessdata）。\n")
                    .append("  → 只能凭简介和弹幕判断，**不要编视频内容**。\n");
        } else {
            sb.append("\n【字幕】这个视频没有 AI 字幕。\n")
                    .append("  → 只能凭简介和弹幕判断，**不要编视频内容**。\n");
        }

        log.info("[web-content] bilibili video ok bvid={} danmaku={} subtitleLen={}",
                bvid, dm.size(), subtitle == null ? 0 : subtitle.length());
        return JsonNodeFactory.instance.textNode(sb.toString());
    }

    // ---------------- music_search ----------------

    /**
     * 搜歌（2026-10-25）。
     *
     * <p>实测 QQ 音乐的 `client_search_cp` 接口**不需要签名、不需要登录**，
     * 带上 `format=json` 就返回纯 JSON（否则是 JSONP）。原唱通常排在第一位。</p>
     *
     * <p>返回里明确写"你没法真的播放" —— 否则她会只说歌名不给链接，
     * 或者干脆假装按了播放键。</p>
     */
    private JsonNode handleMusicSearch(JsonNode args, PluginContext ctx) {
        String kw = args == null ? "" : args.path("keyword").asText("").trim();
        if (kw.isBlank()) {
            throw new IllegalArgumentException("keyword 不能为空（歌名，或「歌名 歌手」）");
        }
        int limit = clamp(args.path("limit").asInt(3), 1, 10);
        String url = QQ_MUSIC_SEARCH
                + "?w=" + URLEncoder.encode(kw, StandardCharsets.UTF_8)
                + "&format=json&n=" + limit + "&p=1";

        JsonNode root;
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header("Referer", "https://y.qq.com/")
                    .get()
                    .build();
            String body;
            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code());
                }
                body = response.body() == null ? "" : response.body().string();
            }
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("搜歌失败：" + e.getMessage()
                    + "。可以换 web_search 试试。");
        }

        JsonNode list = root.path("data").path("song").path("list");
        if (!list.isArray() || list.isEmpty()) {
            return JsonNodeFactory.instance.textNode(
                    "没搜到「" + kw + "」。换个写法试试 —— 歌名后面带上歌手会更准。");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【搜到 ").append(list.size()).append(" 首】\n");
        for (JsonNode s : list) {
            String name = s.path("songname").asText("");
            StringBuilder singers = new StringBuilder();
            for (JsonNode ar : s.path("singer")) {
                if (singers.length() > 0) {
                    singers.append('/');
                }
                singers.append(ar.path("name").asText(""));
            }
            String album = s.path("albumname").asText("");
            long sec = s.path("interval").asLong(0);
            String mid = s.path("songmid").asText("");
            sb.append("· ").append(name).append(" — ").append(singers).append('\n');
            sb.append("  专辑：").append(album.isBlank() ? "-" : album)
                    .append("　时长：").append(fmtDuration(sec)).append('\n');
            if (!mid.isBlank()) {
                sb.append("  链接：https://y.qq.com/n/ryqq/songDetail/").append(mid).append('\n');
            }
        }
        sb.append("\n（把链接发出去就行 —— 你没法真的播放音乐。）");
        log.info("[web-content] music search ok keyword={} n={}", kw, list.size());
        return JsonNodeFactory.instance.textNode(sb.toString());
    }

    /** 从 BV 号或任意含 BV 的链接里取出 bvid。 */
    static String extractBvid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("(BV[0-9A-Za-z]{10})").matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    /** 秒数 → 「3 分 20 秒」。 */
    static String fmtDuration(long seconds) {
        if (seconds <= 0) {
            return "-";
        }
        long h = seconds / 3600;
        long mm = (seconds % 3600) / 60;
        long ss = seconds % 60;
        if (h > 0) {
            return h + " 时 " + mm + " 分";
        }
        if (mm > 0) {
            return mm + " 分 " + ss + " 秒";
        }
        return ss + " 秒";
    }

    /** 带 cookie 的 B 站 GET，返回 `data` 节点；code != 0 抛错并说明该怎么办。 */
    private JsonNode biliJson(String url, String sessdata, String what) {
        try {
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .get();
            if (sessdata != null && !sessdata.isBlank()) {
                builder.header("Cookie", "SESSDATA=" + sessdata);
            }
            String body;
            try (Response response = http.newCall(builder.build()).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code());
                }
                body = response.body() == null ? "" : response.body().string();
            }
            JsonNode root = mapper.readTree(body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                String msg = root.path("message").asText("");
                String advice = (sessdata == null || sessdata.isBlank())
                        ? "可能需要登录 cookie（biliSessdata）"
                        : "cookie 可能已过期，或触发了风控";
                throw new IllegalStateException(what + " 返回 code=" + code
                        + (msg.isBlank() ? "" : "（" + msg + "）") + "，" + advice);
            }
            return root.path("data");
        } catch (Exception e) {
            throw new IllegalStateException("取" + what + "失败：" + e.getMessage());
        }
    }

    /** 弹幕 XML → 前 N 条纯文本。 */
    private List<String> fetchDanmaku(long cid, int limit) {
        List<String> out = new ArrayList<>();
        if (cid <= 0) {
            return out;
        }
        try {
            Request request = new Request.Builder()
                    .url(BILI_DANMAKU + "?oid=" + cid)
                    .header("User-Agent", BROWSER_UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .get()
                    .build();
            String xml;
            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return out;
                }
                xml = response.body() == null ? "" : response.body().string();
            }
            Matcher m = Pattern.compile("<d p=\"[^\"]*\">([^<]{1,80})</d>").matcher(xml);
            while (m.find() && out.size() < limit) {
                String t = m.group(1).trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        } catch (Exception e) {
            log.debug("[web-content] danmaku failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 取 AI 字幕（需要 SESSDATA）。
     *
     * <p>流程：`player/v2` 拿字幕列表 → 优先中文 → 下载 `subtitle_url` 的 JSON →
     * 拼成纯文本。任何一步失败都返回 null，由上层给出"没字幕"的说明。</p>
     */
    private String fetchSubtitle(long aid, long cid, String sessdata, int maxChars) {
        if (sessdata == null || sessdata.isBlank() || aid <= 0 || cid <= 0) {
            return null;
        }
        try {
            JsonNode data = biliJson(BILI_PLAYER + "?aid=" + aid + "&cid=" + cid, sessdata, "播放器信息");
            JsonNode subs = data.path("subtitle").path("subtitles");
            if (!subs.isArray() || subs.isEmpty()) {
                return null;
            }
            String subUrl = null;
            for (JsonNode item : subs) {
                String lan = item.path("lan").asText("");
                String u = item.path("subtitle_url").asText("");
                if (u.isBlank()) {
                    continue;
                }
                if (u.startsWith("//")) {
                    u = "https:" + u;
                }
                if (subUrl == null) {
                    subUrl = u;
                }
                if (lan.startsWith("zh")) {
                    subUrl = u;
                    break;
                }
            }
            if (subUrl == null) {
                return null;
            }

            Request request = new Request.Builder()
                    .url(subUrl)
                    .header("User-Agent", BROWSER_UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .get()
                    .build();
            String json;
            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }
                json = response.body() == null ? "" : response.body().string();
            }
            JsonNode arr = mapper.readTree(json).path("body");
            if (!arr.isArray()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode line : arr) {
                String c = line.path("content").asText("").trim();
                if (c.isEmpty()) {
                    continue;
                }
                sb.append(c);
                if (sb.length() >= maxChars) {
                    sb.setLength(maxChars);
                    sb.append("…（后面省略）");
                    break;
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            log.debug("[web-content] subtitle failed: {}", e.getMessage());
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---------------- SSRF 防护 ----------------

    /**
     * 只允许抓**公网**地址（2026-10-21）。
     *
     * <p>不加这道闸，模型被诱导去抓 {@code http://127.0.0.1:8081/api/...} 就能读到平台自己的
     * 管理接口，抓 {@code 192.168.x.x} 能摸到内网设备（路由器/摄像头/NAS）。
     * 而模型是**完全不可信的输入方** —— 群里任何人发一句"帮我看看这个链接"就能让它去抓。</p>
     *
     * <p>覆盖：环回、私有网段、链路本地（含云厂商的 {@code 169.254.169.254} 元数据服务）、
     * 多播、CGNAT（100.64/10）、保留段，以及 IPv6 的 {@code ::1} / {@code fc00::/7} / {@code fe80::/10}。</p>
     *
     * <p><b>为什么按解析后的 IP 判断</b>：只查字面量挡不住 {@code foo.evil.com → 127.0.0.1}
     * 这种 DNS 指向。解析一次再逐个地址校验（也顺带挡住"先解析成公网、再重绑定"的一部分场景；
     * 完整防重绑定需要 pin 住 IP 再连接，代价更大，这里先挡住绝大部分）。</p>
     */
    static void assertPublicUrl(String url) {
        String host;
        try {
            host = new java.net.URI(url).getHost();
        } catch (Exception e) {
            throw new IllegalArgumentException("URL 格式不正确：" + url);
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机名");
        }
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if ("localhost".equals(h) || h.endsWith(".localhost") || h.endsWith(".local")
                || h.endsWith(".internal") || h.endsWith(".lan")) {
            throw new IllegalArgumentException("不允许访问内网/本地地址：" + host);
        }
        try {
            for (java.net.InetAddress addr : java.net.InetAddress.getAllByName(host)) {
                if (isBlockedAddress(addr)) {
                    throw new IllegalArgumentException("不允许访问内网/保留地址：" + addr.getHostAddress());
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("域名解析失败：" + host);
        }
    }

    private static boolean isBlockedAddress(java.net.InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            if (first == 0) {
                return true;
            }
            // 100.64.0.0/10 —— 运营商级 NAT，常被云上当作内网
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            // 192.0.0.0/24 保留段
            if (first == 192 && second == 0) {
                return true;
            }
            // 198.18.0.0/15 基准测试段
            return first == 198 && (second == 18 || second == 19);
        }
        if (b.length == 16) {
            // fc00::/7 唯一本地地址
            return (b[0] & 0xFE) == 0xFC;
        }
        return false;
    }
}
