package com.agentplatform.core.plugin.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;

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
