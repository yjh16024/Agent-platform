package com.agentplatform.core.notification;

import com.agentplatform.model.entity.SysNotification;
import com.agentplatform.model.enums.NotificationLevel;
import com.agentplatform.model.enums.NotificationType;
import com.agentplatform.model.repository.SysNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内通知服务测试。
 *
 * <p>两组断言重点：</p>
 * <ol>
 *   <li><b>发送类绝不抛异常</b> —— 它由业务埋点调用，写不进去不能连累主流程；</li>
 *   <li><b>收件人隔离</b> —— 收件人必须参与查询条件本身（隔离做在 SQL 层），
 *       而不是"查出来再在 Java 里比一下"。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private SysNotificationRepository repository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository);
    }

    /** 构造一条已存在的通知。 */
    private static SysNotification existing(String id, String tenant, String recipient, LocalDateTime readAt) {
        return SysNotification.builder()
                .notificationId(id)
                .tenantId(tenant)
                .recipientId(recipient)
                .type(NotificationType.task)
                .level(NotificationLevel.info)
                .title("标题")
                .readAt(readAt)
                .build();
    }

    // ------------------------------------------------------------------ 发送

    @Test
    @DisplayName("正常发送：落库内容与入参一致，且生成业务 ID")
    void notifyPersists() {
        when(repository.findByTenantIdAndRecipientIdAndTypeAndTitleAndCreatedAtAfter(
                anyString(), anyString(), any(), anyString(), any())).thenReturn(List.of());

        service.notify("t1", "u1", NotificationType.quota, NotificationLevel.error,
                "配额已用尽", "详情", "/settings");

        ArgumentCaptor<SysNotification> captor = ArgumentCaptor.forClass(SysNotification.class);
        verify(repository).save(captor.capture());
        SysNotification saved = captor.getValue();
        assertEquals("t1", saved.getTenantId());
        assertEquals("u1", saved.getRecipientId());
        assertEquals(NotificationType.quota, saved.getType());
        assertEquals(NotificationLevel.error, saved.getLevel());
        assertEquals("配额已用尽", saved.getTitle());
        assertNotNull(saved.getNotificationId(), "业务 ID 必须生成（对外只暴露它）");
    }

    @Test
    @DisplayName("收件人为空 → 直接跳过，不写无主通知（点对点模型没有广播退路）")
    void notifySkipsBlankRecipient() {
        service.notify("t1", null, NotificationType.system, NotificationLevel.info, "x", null, null);
        service.notify("t1", "   ", NotificationType.system, NotificationLevel.info, "x", null, null);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("未指定租户 → 落到 default，不写空租户")
    void notifyFallsBackToDefaultTenant() {
        when(repository.findByTenantIdAndRecipientIdAndTypeAndTitleAndCreatedAtAfter(
                anyString(), anyString(), any(), anyString(), any())).thenReturn(List.of());

        service.notify(null, "u1", NotificationType.system, NotificationLevel.info, "x", null, null);

        ArgumentCaptor<SysNotification> captor = ArgumentCaptor.forClass(SysNotification.class);
        verify(repository).save(captor.capture());
        assertEquals("default", captor.getValue().getTenantId());
    }

    @Test
    @DisplayName("去重窗口内已有同类型同标题 → 不再重复落库（防配额类事件刷屏）")
    void notifyDeduplicates() {
        when(repository.findByTenantIdAndRecipientIdAndTypeAndTitleAndCreatedAtAfter(
                anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(List.of(existing("n1", "t1", "u1", null)));

        service.notify("t1", "u1", NotificationType.quota, NotificationLevel.error,
                "配额已用尽", null, null);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("仓储抛异常时发送仍不抛（埋点不能连累主流程）")
    void notifySwallowsRepositoryFailure() {
        when(repository.findByTenantIdAndRecipientIdAndTypeAndTitleAndCreatedAtAfter(
                anyString(), anyString(), any(), anyString(), any())).thenReturn(List.of());
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.notify("t1", "u1", NotificationType.task,
                NotificationLevel.error, "标题", null, null));
    }

    @Test
    @DisplayName("仓储未注入（单测/最小依赖场景）→ 静默跳过而非 NPE")
    void notifyWithoutRepositoryIsSilent() {
        NotificationService bare = new NotificationService(null);
        assertDoesNotThrow(() -> bare.notify("t1", "u1", NotificationType.system,
                NotificationLevel.info, "标题", null, null));
    }

    // ------------------------------------------------------------------ 收件人隔离（做在查询条件里）

    @Test
    @DisplayName("标记已读：收件人必须进查询条件（三要素查，而不是查出来再比对）")
    void markReadPutsRecipientIntoQuery() {
        when(repository.findByNotificationIdAndTenantIdAndRecipientId("n1", "t1", "u1"))
                .thenReturn(Optional.of(existing("n1", "t1", "u1", null)));

        assertTrue(service.markRead("t1", "u1", "n1"));

        // 关键断言：收件人作为查询参数传下去了 —— 别人的通知在 SQL 层就查不到
        verify(repository).findByNotificationIdAndTenantIdAndRecipientId(eq("n1"), eq("t1"), eq("u1"));
    }

    @Test
    @DisplayName("标记已读：查不到（不存在或不属于你）→ false 且不写库")
    void markReadReturnsFalseWhenNotFound() {
        when(repository.findByNotificationIdAndTenantIdAndRecipientId("n1", "t1", "u1"))
                .thenReturn(Optional.empty());

        assertFalse(service.markRead("t1", "u1", "n1"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("标记已读：已是已读 → 不重复写库，但仍返回命中")
    void markReadSkipsAlreadyRead() {
        when(repository.findByNotificationIdAndTenantIdAndRecipientId("n1", "t1", "u1"))
                .thenReturn(Optional.of(existing("n1", "t1", "u1", LocalDateTime.now())));

        assertTrue(service.markRead("t1", "u1", "n1"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("删除：收件人必须进查询条件")
    void deletePutsRecipientIntoQuery() {
        SysNotification mine = existing("n1", "t1", "u1", null);
        when(repository.findByNotificationIdAndTenantIdAndRecipientId("n1", "t1", "u1"))
                .thenReturn(Optional.of(mine));

        assertTrue(service.delete("t1", "u1", "n1"));
        verify(repository).delete(mine);
    }

    @Test
    @DisplayName("删除：查不到 → false 且不调 delete")
    void deleteReturnsFalseWhenNotFound() {
        when(repository.findByNotificationIdAndTenantIdAndRecipientId("n1", "t1", "u1"))
                .thenReturn(Optional.empty());

        assertFalse(service.delete("t1", "u1", "n1"));
        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("仓储未注入时标记已读/删除返回 false（不抛）")
    void markAndDeleteWithoutRepository() {
        NotificationService bare = new NotificationService(null);
        assertFalse(bare.markRead("t1", "u1", "n1"));
        assertFalse(bare.delete("t1", "u1", "n1"));
    }

    // ------------------------------------------------------------------ 查询与批量

    @Test
    @DisplayName("未读数：无收件人时为 0，不查库")
    void unreadCountWithoutRecipient() {
        assertEquals(0L, service.unreadCount("t1", null));
        verify(repository, never()).countByTenantIdAndRecipientIdAndReadAtIsNull(anyString(), anyString());
    }

    @Test
    @DisplayName("列表：仓储未注入时返回空页而非报错")
    void listWithoutRepositoryReturnsEmpty() {
        NotificationService bare = new NotificationService(null);
        var page = bare.list("t1", "u1", false, 0, 20);
        assertTrue(page.getItems().isEmpty());
        assertEquals(0L, page.getTotal());
    }

    @Test
    @DisplayName("列表：字段映射为对外视图（snake_case + read 布尔）")
    void listMapsToView() {
        SysNotification n = existing("n1", "t1", "u1", null);
        Page<SysNotification> p = new PageImpl<>(List.of(n));
        when(repository.findByTenantIdAndRecipientId(eq("t1"), eq("u1"), any(Pageable.class))).thenReturn(p);

        var result = service.list("t1", "u1", false, 0, 20);

        assertEquals(1, result.getItems().size());
        var view = result.getItems().get(0);
        assertEquals("n1", view.get("notification_id"));
        assertEquals("task", view.get("type"));
        assertEquals(false, view.get("read"));
    }

    @Test
    @DisplayName("列表：unreadOnly=true 走未读分支")
    void listUnreadOnlyUsesUnreadQuery() {
        when(repository.findByTenantIdAndRecipientIdAndReadAtIsNull(
                eq("t1"), eq("u1"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.list("t1", "u1", true, 0, 20);

        verify(repository).findByTenantIdAndRecipientIdAndReadAtIsNull(eq("t1"), eq("u1"), any(Pageable.class));
        verify(repository, never()).findByTenantIdAndRecipientId(eq("t1"), eq("u1"), any(Pageable.class));
    }

    @Test
    @DisplayName("列表：size 超上限时被夹到上限（防前端传超大 size 拖垮库）")
    void listClampsPageSize() {
        when(repository.findByTenantIdAndRecipientId(eq("t1"), eq("u1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.list("t1", "u1", false, 0, 9999);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByTenantIdAndRecipientId(eq("t1"), eq("u1"), captor.capture());
        assertEquals(100, captor.getValue().getPageSize());
    }

    @Test
    @DisplayName("全部已读：返回实际改动条数，并写回 readAt")
    void markAllReadCountsChanged() {
        SysNotification a = existing("n1", "t1", "u1", null);
        SysNotification b = existing("n2", "t1", "u1", null);
        when(repository.findByTenantIdAndRecipientIdAndReadAtIsNull("t1", "u1"))
                .thenReturn(List.of(a, b));

        assertEquals(2, service.markAllRead("t1", "u1"));
        assertNotNull(a.getReadAt());
        assertNotNull(b.getReadAt());
    }

    @Test
    @DisplayName("全部已读：无收件人时返回 0")
    void markAllReadWithoutRecipient() {
        assertEquals(0, service.markAllRead("t1", null));
    }

    @Test
    @DisplayName("清理：保留期非法（<=0）时回落到默认 30 天")
    void purgeUsesDefaultRetention() {
        service.purge(0);
        verify(repository).deleteByCreatedAtBefore(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("清理：仓储未注入时返回 0")
    void purgeWithoutRepository() {
        NotificationService bare = new NotificationService(null);
        assertEquals(0, bare.purge(30));
    }

    @Test
    @DisplayName("收件人为空时列表/未读都不应触碰仓储（避免无谓查询）")
    void blankRecipientShortCircuits() {
        NotificationService withRepo = new NotificationService(mock(SysNotificationRepository.class));
        assertTrue(withRepo.list("t1", "", false, 0, 20).getItems().isEmpty());
        assertEquals(0L, withRepo.unreadCount("t1", ""));
    }
}
