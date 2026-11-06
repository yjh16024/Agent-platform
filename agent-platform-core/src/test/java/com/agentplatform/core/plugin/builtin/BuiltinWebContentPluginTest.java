package com.agentplatform.core.plugin.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网页正文提取的单元测试（2026-10-21）。
 *
 * <p>只测**纯函数**（不碰网络）：正文提取最容易"看起来对、实际把导航栏也塞进去"，
 * 而这类错误在真实对话里表现为"模型答非所问"，很难回溯到提取层。</p>
 */
class BuiltinWebContentPluginTest {

    @Test
    @DisplayName("正文提取：优先 article，剔除 script/style/nav/footer")
    void extractMainTextStripsNoise() {
        String html = "<html><head><title>页面标题</title><style>.a{color:red}</style></head>"
                + "<body><nav>首页 产品 关于</nav>"
                + "<article><h1>Hi</h1><p>Hello &amp; world&nbsp;ok.</p><script>var a=1;</script></article>"
                + "<footer>版权所有</footer></body></html>";

        String text = BuiltinWebContentPlugin.extractMainText(html);

        assertTrue(text.contains("Hello & world ok."), "实体应被解码、正文应保留");
        assertFalse(text.contains("首页 产品 关于"), "nav 不应出现在正文里");
        assertFalse(text.contains("版权所有"), "footer 不应出现在正文里");
        assertFalse(text.contains("var a=1"), "script 内容不应出现在正文里");
        assertFalse(text.contains("color:red"), "style 内容不应出现在正文里");
    }

    @Test
    @DisplayName("没有 article/main 时退化为全页文本（不返回空）")
    void fallsBackToWholeBodyWhenNoArticle() {
        String html = "<html><body><div><p>第一段</p><p>第二段</p></div></body></html>";
        String text = BuiltinWebContentPlugin.extractMainText(html);

        assertTrue(text.contains("第一段"));
        assertTrue(text.contains("第二段"));
    }

    @Test
    @DisplayName("实体解码：命名实体与十进制/十六进制数字实体")
    void decodeEntitiesWorks() {
        assertEquals("a b", BuiltinWebContentPlugin.decodeEntities("a&nbsp;b"));
        assertEquals("…", BuiltinWebContentPlugin.decodeEntities("&hellip;"));
        assertEquals("中", BuiltinWebContentPlugin.decodeEntities("&#20013;"));
        assertEquals("A", BuiltinWebContentPlugin.decodeEntities("&#x41;"));
        assertEquals("a & b", BuiltinWebContentPlugin.decodeEntities("a &amp; b"));
        // 解析不了的原样保留，不能抛异常
        assertEquals("&#zz;", BuiltinWebContentPlugin.decodeEntities("&#zz;"));
    }

    @Test
    @DisplayName("RSS 2.0 解析：item 的 title/link/pubDate/description")
    void parsesRssItems() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel><title>频道名</title>"
                + "<item><title>条目一</title><link>https://a.com/1</link>"
                + "<pubDate>Tue, 21 Oct 2026 10:00:00 GMT</pubDate>"
                + "<description>&lt;p&gt;摘要内容&lt;/p&gt;</description></item>"
                + "</channel></rss>";

        Document doc = BuiltinWebContentPlugin.parseXml(xml.getBytes(StandardCharsets.UTF_8));
        NodeList items = doc.getElementsByTagName("item");

        assertEquals(1, items.getLength());
        Element item = (Element) items.item(0);
        assertEquals("条目一", BuiltinWebContentPlugin.childText(item, "title"));
        assertEquals("https://a.com/1", BuiltinWebContentPlugin.childText(item, "link"));
        assertEquals("摘要内容",
                BuiltinWebContentPlugin.stripHtml(BuiltinWebContentPlugin.childText(item, "description")));
    }

    @Test
    @DisplayName("Atom 解析：link 在 href 属性里")
    void parsesAtomEntry() throws Exception {
        String xml = "<feed><title>F</title><entry><title>E1</title>"
                + "<link href=\"https://b.com/1\"/><updated>2026-10-21T10:00:00Z</updated></entry></feed>";

        Document doc = BuiltinWebContentPlugin.parseXml(xml.getBytes(StandardCharsets.UTF_8));
        Element entry = (Element) doc.getElementsByTagName("entry").item(0);

        assertEquals("E1", BuiltinWebContentPlugin.childText(entry, "title"));
        assertEquals("https://b.com/1", BuiltinWebContentPlugin.atomLink(entry));
    }

    @Test
    @DisplayName("图片搜索：抽出图片直链与标题，并过滤掉指向网页的结果")
    void parsesBingImages() {
        // 这是必应结果页里 <a class="iusc" m="{...}"> 的真实形态（JSON 被 HTML 实体转义）
        String html = "<a class=\"iusc\" m=\"{&quot;t&quot;:&quot;动漫头像&quot;,&quot;murl&quot;:&quot;https://x.com/a.jpg&quot;,&quot;purl&quot;:&quot;https://p.com&quot;}\">"
                + "<a class=\"iusc\" m=\"{&quot;t&quot;:&quot;这不是图片&quot;,&quot;murl&quot;:&quot;https://x.com/page.html&quot;}\">"
                + "<a class=\"iusc\" m=\"{&quot;t&quot;:&quot;第二张&quot;,&quot;murl&quot;:&quot;https://y.com/b.png&quot;}\">";

        List<String[]> items = BuiltinWebContentPlugin.parseBingImages(html, 10);

        // .html 那条必须被过滤 —— 否则模型会拿一个网页地址去当头像
        assertEquals(2, items.size());
        assertEquals("动漫头像", items.get(0)[0]);
        assertEquals("https://x.com/a.jpg", items.get(0)[1]);
        assertEquals("https://y.com/b.png", items.get(1)[1]);
    }

    @Test
    @DisplayName("图片搜索：标题在 murl 之后也能取到（必应真实字段顺序）")
    void parsesBingImagesWithTitleAfterMurl() {
        /*
         * 实测必应的 m 属性里 `t`（标题）排在 `murl` **之后**，整张卡片 1200+ 字符。
         * 第一版"往前找 400 字符"因此全空 —— 模型拿到的只有 URL，
         * 它的原话是"回来的都是直链，没有标题也没有预览…很容易挑错"，于是不敢换头像。
         */
        String html = "<a class=\"iusc\" m=\"{&quot;cid&quot;:&quot;x&quot;,"
                + "&quot;murl&quot;:&quot;https://x.com/a.jpg&quot;,"
                + "&quot;turl&quot;:&quot;https://x.com/thumb.jpg&quot;,"
                + "&quot;t&quot;:&quot;动漫头像 - 堆糖&quot;}\">";

        List<String[]> items = BuiltinWebContentPlugin.parseBingImages(html, 5);

        assertEquals(1, items.size());
        // turl 不能被抓成标题
        assertEquals("动漫头像 - 堆糖", items.get(0)[0]);
        assertEquals("https://x.com/a.jpg", items.get(0)[1]);
    }

    @Test
    @DisplayName("图片搜索：limit 生效、空页面返回空列表不抛异常")
    void parseBingImagesRespectsLimit() {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            // 必须带 class="iusc"：解析是按结果卡片切的，没有这个类就不是一张结果
            html.append("<a class=\"iusc\" m=\"{&quot;t&quot;:&quot;t").append(i)
                    .append("&quot;,&quot;murl&quot;:&quot;https://x.com/").append(i).append(".jpg&quot;}\">");
        }

        assertEquals(2, BuiltinWebContentPlugin.parseBingImages(html.toString(), 2).size());
        assertTrue(BuiltinWebContentPlugin.parseBingImages("", 10).isEmpty());
        assertTrue(BuiltinWebContentPlugin.parseBingImages(null, 10).isEmpty());
    }

    @Test
    @DisplayName("图片搜索：百度 acjson 解析（middleURL / thumbURL 兜底、剥掉 em 标签）")
    void parsesBaiduImages() {
        String json = "{\"data\":["
                + "{\"middleURL\":\"https://img1.baidu.com/a.jpg\",\"fromPageTitleEnc\":\"<em>白发</em>头像女动漫\"},"
                + "{\"thumbURL\":\"https://img2.baidu.com/b.png\",\"fromPageTitle\":\"白发动漫\"},"
                + "{\"queryEnc\":\"x\"},"
                + "null]}";

        List<String[]> items = BuiltinWebContentPlugin.parseBaiduImages(json, 10);

        // 后两条不是图片（一条只有 queryEnc、一条是 null），必须被跳过
        assertEquals(2, items.size());
        assertEquals("白发头像女动漫", items.get(0)[0]);
        assertEquals("https://img1.baidu.com/a.jpg", items.get(0)[1]);
        assertEquals("https://img2.baidu.com/b.png", items.get(1)[1]);
    }

    @Test
    @DisplayName("图片搜索：堆糖解析（object_list[].photo.path，无图的跳过）")
    void parsesDuitang() {
        String json = "{\"data\":{\"object_list\":["
                + "{\"msg\":\"头像动漫\",\"photo\":{\"path\":\"https://a-ho-img.dtstatic.com/1.jpg\",\"width\":1440}},"
                + "{\"msg\":\"这条没有图\"},"
                + "{\"msg\":\"\",\"photo\":{\"path\":\"https://a-ho-img.dtstatic.com/2.jpeg\"}}]}}";

        List<String[]> items = BuiltinWebContentPlugin.parseDuitang(json, 10);

        assertEquals(2, items.size());
        assertEquals("头像动漫", items.get(0)[0]);
        assertEquals("https://a-ho-img.dtstatic.com/1.jpg", items.get(0)[1]);
        assertEquals("https://a-ho-img.dtstatic.com/2.jpeg", items.get(1)[1]);
    }

    @Test
    @DisplayName("图片搜索：花瓣解析（file.url 优先，缺则用 key 拼）")
    void parsesHuaban() {
        String json = "{\"pins\":["
                + "{\"raw_text\":\"♡JUICY♡\",\"file\":{\"url\":\"https://gd-hbimg-edge.huaban.com/abc?auth_key=1\"}},"
                + "{\"raw_text\":\"\",\"file\":{\"key\":\"def\"}},"
                + "{\"raw_text\":\"无文件\"}]}";

        List<String[]> items = BuiltinWebContentPlugin.parseHuaban(json, 10);

        assertEquals(2, items.size(), "第三个 pin 没有 file，应跳过");
        assertEquals("https://gd-hbimg-edge.huaban.com/abc?auth_key=1", items.get(0)[1]);
        assertEquals("https://hbimg.huaban.com/def", items.get(1)[1]);
    }

    @Test
    @DisplayName("图片搜索：多源合并时按 URL 去重")
    void dedupesAcrossSources() {
        List<String[]> raw = List.of(
                new String[]{"a", "https://x.com/1.jpg"},
                new String[]{"b", "https://x.com/2.jpg"},
                new String[]{"c", "https://x.com/1.jpg"});

        List<String[]> out = BuiltinWebContentPlugin.dedupe(raw, 10);

        assertEquals(2, out.size());
        assertEquals("https://x.com/1.jpg", out.get(0)[1]);
        assertEquals("https://x.com/2.jpg", out.get(1)[1]);
    }

    @Test
    @DisplayName("图片搜索：360 JSON 解析（img / thumb）")
    void parsesSoImages() {
        String json = "{\"list\":["
                + "{\"img\":\"https://a.com/1.jpg\",\"title\":\"白发\"},"
                + "{\"thumb\":\"https://a.com/2.png\"}]}";

        List<String[]> items = BuiltinWebContentPlugin.parseSoImages(json, 10);

        assertEquals(2, items.size());
        assertEquals("https://a.com/1.jpg", items.get(0)[1]);
        assertEquals("https://a.com/2.png", items.get(1)[1]);
    }

    @Test
    @DisplayName("图片搜索：百度返回非严格 JSON 时返回空列表，不抛异常")
    void baiduToleratesBrokenJson() {
        assertTrue(BuiltinWebContentPlugin.parseBaiduImages("{\"data\":[ oops", 10).isEmpty());
        assertTrue(BuiltinWebContentPlugin.parseBaiduImages("", 10).isEmpty());
    }

    @Test
    @DisplayName("XXE 防护：带 DOCTYPE 的 XML 必须被拒绝（不能让它读本机文件）")
    void rejectsDoctypeToPreventXxe() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<rss><channel><title>&xxe;</title></channel></rss>";

        assertThrows(Exception.class,
                () -> BuiltinWebContentPlugin.parseXml(evil.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("SSRF 防护：环回 / 私有网段 / 云元数据地址都要拒绝")
    void blocksInternalAddresses() {
        // 这些都是字面量 IP，不需要 DNS，测试不依赖网络
        String[] blocked = {
                "http://127.0.0.1:8081/api/v1/agents",
                "http://localhost/admin",
                "http://10.0.0.1/",
                "http://192.168.1.1/",
                "http://172.16.0.1/",
                // 云厂商元数据服务：拿到它能读实例凭证，是最典型的 SSRF 目标
                "http://169.254.169.254/latest/meta-data/",
                "http://0.0.0.0:9200/",
        };
        for (String url : blocked) {
            assertThrows(IllegalArgumentException.class,
                    () -> BuiltinWebContentPlugin.assertPublicUrl(url),
                    "应拒绝：" + url);
        }
    }
}
