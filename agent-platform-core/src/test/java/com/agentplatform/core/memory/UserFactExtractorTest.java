package com.agentplatform.core.memory;

import com.agentplatform.model.enums.UserFactCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link UserFactExtractor} 的解析逻辑测试（2026-09-26）。
 *
 * <h3>为什么值得单测解析</h3>
 * 抽取器其它部分是"叫模型、拿字符串"的胶水，没什么好测；**唯一的真实逻辑就是解析**，
 * 而它面对的输入是**模型自由生成的自然文本** —— 实测里最常见的三种形态：
 * 干净的 JSON、包在 ```json 代码块里的 JSON、前后带一句解释的 JSON。
 * 只处理第一种的话，功能会时灵时不灵（取决于模型当天的心情），且失败是静默的 ——
 * 用户只会觉得"这功能怎么有时候有用有时候没用"。所以这里把三种形态都锁住。
 */
@ExtendWith(MockitoExtension.class)
class UserFactExtractorTest {

    @Mock
    private com.agentplatform.core.model.router.ModelRouter modelRouter;
    @Mock
    private UserFactCandidateService candidateService;

    // ---------------- 解析：模型输出的三种常见形态 ----------------

    @Test
    @DisplayName("解析：干净的 JSON 数组")
    void parsesPlainJson() {
        List<UserFactCandidateService.CandidateDraft> drafts = UserFactExtractor.parseDrafts(
                "[{\"key\":\"职业\",\"value\":\"Java 后端工程师\",\"category\":\"background\"}]");

        assertEquals(1, drafts.size());
        assertEquals("职业", drafts.get(0).key());
        assertEquals("Java 后端工程师", drafts.get(0).value());
        assertEquals(UserFactCategory.background, drafts.get(0).category());
    }

    @Test
    @DisplayName("解析：包在 ```json 代码块里（模型最常见的输出形态）")
    void parsesFencedJson() {
        String content = """
                ```json
                [{"key":"常用语言","value":"Java / TypeScript","category":"preference"}]
                ```""";

        List<UserFactCandidateService.CandidateDraft> drafts = UserFactExtractor.parseDrafts(content);

        assertEquals(1, drafts.size());
        assertEquals("常用语言", drafts.get(0).key());
        assertEquals(UserFactCategory.preference, drafts.get(0).category());
    }

    @Test
    @DisplayName("解析：前后带解释文字（只截取 JSON 部分）")
    void parsesJsonEmbeddedInProse() {
        String content = "从这轮对话里我提取到以下事实：\n"
                + "[{\"key\":\"所在地\",\"value\":\"北京\",\"category\":\"background\"}]\n"
                + "以上就是全部。";

        List<UserFactCandidateService.CandidateDraft> drafts = UserFactExtractor.parseDrafts(content);

        assertEquals(1, drafts.size());
        assertEquals("所在地", drafts.get(0).key());
        assertEquals("北京", drafts.get(0).value());
    }

    @Test
    @DisplayName("解析：多条候选")
    void parsesMultiple() {
        List<UserFactCandidateService.CandidateDraft> drafts = UserFactExtractor.parseDrafts("""
                [
                  {"key":"职业","value":"后端工程师","category":"background"},
                  {"key":"目标","value":"完成毕业论文","category":"goal"}
                ]""");

        assertEquals(2, drafts.size());
        assertEquals(UserFactCategory.goal, drafts.get(1).category());
    }

    // ---------------- 解析：异常输入必须静默跳过，不能抛 ----------------

    @Test
    @DisplayName("解析：空数组 / null / 垃圾输入都返回空列表（不抛异常）")
    void handlesBadInputQuietly() {
        assertTrue(UserFactExtractor.parseDrafts("[]").isEmpty());
        assertTrue(UserFactExtractor.parseDrafts(null).isEmpty());
        assertTrue(UserFactExtractor.parseDrafts("").isEmpty());
        assertTrue(UserFactExtractor.parseDrafts("模型今天不想干活").isEmpty());
        assertTrue(UserFactExtractor.parseDrafts("[{不是合法 JSON").isEmpty());
        assertTrue(UserFactExtractor.parseDrafts("{\"key\":\"a\"}").isEmpty(), "对象而非数组也算不可用");
    }

    @Test
    @DisplayName("解析：缺 key 或 value 的项被跳过，完整的保留")
    void skipsIncompleteItems() {
        List<UserFactCandidateService.CandidateDraft> drafts = UserFactExtractor.parseDrafts("""
                [
                  {"key":"","value":"有值没键","category":"other"},
                  {"key":"有键没值","value":"","category":"other"},
                  {"key":"职业","value":"工程师","category":"background"}
                ]""");

        assertEquals(1, drafts.size(), "只有完整的那条应被保留");
        assertEquals("职业", drafts.get(0).key());
    }

    @Test
    @DisplayName("解析：category 非法或缺失时落到 other（不报错）")
    void fallsBackToOtherCategory() {
        List<UserFactCandidateService.CandidateDraft> drafts = UserFactExtractor.parseDrafts("""
                [
                  {"key":"a","value":"1","category":"模型自创的分类"},
                  {"key":"b","value":"2"}
                ]""");

        assertEquals(2, drafts.size());
        assertEquals(UserFactCategory.other, drafts.get(0).category());
        assertEquals(UserFactCategory.other, drafts.get(1).category());
    }

    // ---------------- 开关与守门条件 ----------------

    @Test
    @DisplayName("默认关闭时完全不动作（不发模型请求、不写候选）")
    void disabledByDefaultDoesNothing() {
        // 直接 new 出来的实例里 enabled 是 boolean 默认值 false
        UserFactExtractor extractor = new UserFactExtractor(modelRouter, candidateService);

        extractor.extractAsync("t1", "u1", "sess_1",
                "我是一名 Java 后端工程师，最近在做智能体平台", "好的");

        verify(modelRouter, never()).chat(any(), any());
        verify(candidateService, never()).offer(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("没有 userId 时直接跳过（画像没有归属就无从谈起）")
    void skipsWithoutUserId() {
        UserFactExtractor extractor = new UserFactExtractor(modelRouter, candidateService);

        extractor.extractAsync("t1", null, "sess_1", "我是一名 Java 后端工程师", "好的");
        extractor.extractAsync("t1", "  ", "sess_1", "我是一名 Java 后端工程师", "好的");

        verify(modelRouter, never()).chat(any(), any());
    }

    @Test
    @DisplayName("用户消息过短时跳过（省一次模型调用）")
    void skipsVeryShortMessage() {
        UserFactExtractor extractor = new UserFactExtractor(modelRouter, candidateService);

        extractor.extractAsync("t1", "u1", "sess_1", "你好", "你好，有什么可以帮你？");

        verify(modelRouter, never()).chat(any(), any());
    }
}
