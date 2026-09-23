package com.agentplatform.core.memory;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.model.entity.UserFact;
import com.agentplatform.model.enums.UserFactCategory;
import com.agentplatform.model.enums.UserFactSource;
import com.agentplatform.model.repository.UserFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆（用户画像）的单元测试（2026-09-22）。
 *
 * <p>重点覆盖四件事 —— <b>upsert 语义</b>、<b>归属隔离</b>、<b>渲染的边界</b>、
 * 以及<b>渲染永不抛异常</b>（它挂在对话主链路上，抛了用户就发不出消息）。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserFactServiceTest {

    @Mock
    private UserFactRepository repository;

    private UserFactService service;

    @BeforeEach
    void setUp() {
        service = new UserFactService(repository);
    }

    private static UserFact fact(String tenant, String user, String key, String value) {
        return UserFact.builder()
                .factId("fact_" + key)
                .tenantId(tenant)
                .userId(user)
                .factKey(key)
                .factValue(value)
                .category(UserFactCategory.other)
                .source(UserFactSource.manual)
                .build();
    }

    // ---------------- upsert ----------------

    @Test
    @DisplayName("保存：同 key 已存在 → 走**更新**而不是插入（表上有唯一约束，无脑插入会 500）")
    void saveUpdatesExistingKey() {
        when(repository.findByTenantIdAndUserIdAndFactKey("t1", "u1", "职业"))
                .thenReturn(Optional.of(fact("t1", "u1", "职业", "学生")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserFact saved = service.save("t1", "u1", "职业", "Java 工程师", "background");

        assertEquals("Java 工程师", saved.getFactValue());
        assertEquals(UserFactCategory.background, saved.getCategory());
        // 关键：是"改"不是"新增" —— 业务 ID 保持原值
        assertEquals("fact_职业", saved.getFactId());
    }

    @Test
    @DisplayName("保存：key 不存在 → 新建，带业务 ID 且来源为 manual")
    void saveInsertsNewKey() {
        when(repository.findByTenantIdAndUserIdAndFactKey(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserFact saved = service.save("t1", "u1", "语言", "中文", null);

        assertNotNull(saved.getFactId());
        assertEquals(UserFactSource.manual, saved.getSource());
        assertEquals(UserFactCategory.other, saved.getCategory(), "未指定分类应落 other");
    }

    @Test
    @DisplayName("保存：已被自动抽取写过的记录，用户手改后来源收回 manual")
    void saveResetsSourceToManual() {
        UserFact auto = fact("t1", "u1", "语言", "英文");
        auto.setSource(UserFactSource.auto);
        when(repository.findByTenantIdAndUserIdAndFactKey(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(auto));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserFact saved = service.save("t1", "u1", "语言", "中文", null);

        assertEquals(UserFactSource.manual, saved.getSource(),
                "用户编辑过的内容不该继续标成系统推测");
    }

    @Test
    @DisplayName("保存：key / value 为空都报错（不写入脏数据）")
    void saveRejectsBlank() {
        assertThrows(BizException.class, () -> service.save("t1", "u1", "  ", "x", null));
        assertThrows(BizException.class, () -> service.save("t1", "u1", "k", "   ", null));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("保存：缺少用户身份直接报错（fail-closed，不落一条无主画像）")
    void saveRequiresUserId() {
        assertThrows(BizException.class, () -> service.save("t1", "", "k", "v", null));
        assertThrows(BizException.class, () -> service.save("t1", null, "k", "v", null));
    }

    @Test
    @DisplayName("保存：超长内容被拒（列宽 1000，让它报可读错误而不是 DB 异常）")
    void saveRejectsTooLongValue() {
        assertThrows(BizException.class,
                () -> service.save("t1", "u1", "k", "x".repeat(1001), null));
    }

    // ---------------- 归属隔离 ----------------

    @Test
    @DisplayName("隔离：查询把 userId 与 tenantId 一起作为查询条件（不是查出来再比）")
    void listPassesOwnerToQuery() {
        when(repository.findByTenantIdAndUserIdOrderByCategoryAscFactKeyAsc(anyString(), anyString()))
                .thenReturn(List.of());

        service.list("t1", "u1");

        verify(repository).findByTenantIdAndUserIdOrderByCategoryAscFactKeyAsc(eq("t1"), eq("u1"));
    }

    @Test
    @DisplayName("隔离：操作别人的画像 → 报「不存在」（不区分「没有」与「不是你的」）")
    void operationsAreOwnerScoped() {
        when(repository.findByFactIdAndTenantIdAndUserId(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThrows(BizException.class, () -> service.delete("t1", "u2", "fact_x"));
        assertThrows(BizException.class, () -> service.update("t1", "u2", "fact_x", null, "v", null));
    }

    @Test
    @DisplayName("隔离：userId 为空时返回空列表，而不是退化成「查全部」")
    void listEmptyWithoutUser() {
        assertTrue(service.list("t1", null).isEmpty());
        assertTrue(service.list("t1", "").isEmpty());
        verify(repository, never())
                .findByTenantIdAndUserIdOrderByCategoryAscFactKeyAsc(anyString(), anyString());
    }

    @Test
    @DisplayName("一键清除：按 (tenant, user) 删，返回删除条数")
    void clearAllScopedToUser() {
        when(repository.deleteByTenantIdAndUserId("t1", "u1")).thenReturn(3L);

        assertEquals(3L, service.clearAll("t1", "u1"));
        verify(repository).deleteByTenantIdAndUserId("t1", "u1");
    }

    // ---------------- 渲染 ----------------

    @Test
    @DisplayName("渲染：没有画像时返回 null（调用方据此不拼接）")
    void renderNullWhenEmpty() {
        when(repository.findByTenantIdAndUserIdOrderByCategoryAscFactKeyAsc(anyString(), anyString()))
                .thenReturn(List.of());

        assertNull(service.render("t1", "u1"));
    }

    @Test
    @DisplayName("渲染：包含边界说明（来源是用户填写、不要主动复述）")
    void renderHasBoundaries() {
        when(repository.findByTenantIdAndUserIdOrderByCategoryAscFactKeyAsc(anyString(), anyString()))
                .thenReturn(List.of(fact("t1", "u1", "职业", "Java 工程师")));

        String text = service.render("t1", "u1");

        assertNotNull(text);
        assertTrue(text.contains("职业：Java 工程师"), "应包含原文：" + text);
        assertTrue(text.contains("用户本人填写"), "应标明来源，避免模型把它当成系统指令");
        assertTrue(text.contains("不要主动复述"), "否则模型每轮都念一遍，用户会立刻察觉到被记录");
    }

    @Test
    @DisplayName("渲染：超过条数上限时截断（画像不能无限挤占上下文）")
    void renderTruncatesByCount() {
        List<UserFact> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(fact("t1", "u1", "k" + i, "v" + i));
        }
        when(repository.findByTenantIdAndUserIdOrderByCategoryAscFactKeyAsc(anyString(), anyString()))
                .thenReturn(many);

        String text = service.render("t1", "u1");

        assertNotNull(text);
        long lines = text.lines().filter(l -> l.startsWith("- ")).count();
        assertTrue(lines <= 20, "渲染行数应受上限约束，实际 " + lines);
    }

    @Test
    @DisplayName("渲染：单条超长值被截断")
    void renderTruncatesLongValue() {
        when(repository.findByTenantIdAndUserIdOrderByCategoryAscFactKeyAsc(anyString(), anyString()))
                .thenReturn(List.of(fact("t1", "u1", "长文", "x".repeat(2000))));

        String text = service.render("t1", "u1");

        assertNotNull(text);
        assertTrue(text.contains("…"), "超长值应带截断标记");
        assertTrue(text.length() < 1500, "渲染结果不应接近原文长度，实际 " + text.length());
    }

    @Test
    @DisplayName("渲染：值为空的记录被跳过（不产生「- key：」这种空行）")
    void renderSkipsBlankValues() {
        when(repository.findByTenantIdAndUserIdOrderByCategoryAscFactKeyAsc(anyString(), anyString()))
                .thenReturn(List.of(fact("t1", "u1", "空", "  "), fact("t1", "u1", "实", "有值")));

        String text = service.render("t1", "u1");

        assertNotNull(text);
        assertFalse(text.contains("空："), "空值不该被渲染：" + text);
        assertTrue(text.contains("实：有值"));
    }

    @Test
    @DisplayName("★ 渲染：仓储抛异常时返回 null 而不是上抛（它挂在对话主链路上）")
    void renderSwallowsException() {
        when(repository.findByTenantIdAndUserIdOrderByCategoryAscFactKeyAsc(anyString(), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        assertNull(service.render("t1", "u1"));
    }

    @Test
    @DisplayName("渲染：userId 为空直接返回 null，不查库")
    void renderNullWithoutUser() {
        assertNull(service.render("t1", null));
        assertNull(service.render("t1", ""));
        verify(repository, never())
                .findByTenantIdAndUserIdOrderByCategoryAscFactKeyAsc(anyString(), anyString());
    }

    @Test
    @DisplayName("更新：改名撞上已有 key → 给出可读报错（而不是唯一键冲突）")
    void updateRejectsDuplicateKey() {
        UserFact target = fact("t1", "u1", "语言", "中文");
        UserFact other = fact("t1", "u1", "职业", "工程师");
        when(repository.findByFactIdAndTenantIdAndUserId("fact_语言", "t1", "u1"))
                .thenReturn(Optional.of(target));
        when(repository.findByTenantIdAndUserIdAndFactKey("t1", "u1", "职业"))
                .thenReturn(Optional.of(other));

        BizException ex = assertThrows(BizException.class,
                () -> service.update("t1", "u1", "fact_语言", "职业", null, null));
        assertTrue(ex.getMessage().contains("职业"), "报错应点出冲突的 key：" + ex.getMessage());
    }

    @Test
    @DisplayName("更新：字段传 null 表示不改（只更 value 时其它字段原样保留）")
    void updatePartialKeepsOthers() {
        UserFact target = fact("t1", "u1", "职业", "工程师");
        target.setCategory(UserFactCategory.background);
        when(repository.findByFactIdAndTenantIdAndUserId("fact_职业", "t1", "u1"))
                .thenReturn(Optional.of(target));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserFact updated = service.update("t1", "u1", "fact_职业", null, "架构师", null);

        assertEquals("职业", updated.getFactKey());
        assertEquals("架构师", updated.getFactValue());
        assertEquals(UserFactCategory.background, updated.getCategory());
    }

    @Test
    @DisplayName("计数：供前端显示规模")
    void countDelegatesToRepository() {
        when(repository.countByTenantIdAndUserId("t1", "u1")).thenReturn(7L);

        assertEquals(7L, service.count("t1", "u1"));
    }

    @Test
    @DisplayName("保存：非法的分类字符串回落成 other 而不是抛异常（表单脏值不该 500）")
    void saveToleratesUnknownCategory() {
        when(repository.findByTenantIdAndUserIdAndFactKey(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        ArgumentCaptor<UserFact> captor = ArgumentCaptor.forClass(UserFact.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.save("t1", "u1", "k", "v", "not-a-category");

        assertEquals(UserFactCategory.other, captor.getValue().getCategory());
    }
}
