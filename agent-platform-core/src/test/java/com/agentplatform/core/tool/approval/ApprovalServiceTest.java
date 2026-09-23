package com.agentplatform.core.tool.approval;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.tool.fs.FileWriteService;
import com.agentplatform.model.entity.ToolApproval;
import com.agentplatform.model.enums.ToolApprovalStatus;
import com.agentplatform.model.repository.ToolApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.LocalDateTime;
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
 * 工具审批服务的单元测试（2026-09-23）。
 *
 * <p>审批是"写能力能上线"的前提，所以测试重点不在"能批准"，而在**不该批的批不了**：
 * 只能批自己提交的、已处理的不能重复批、执行失败要记下来而不是静默成功。</p>
 */
@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    @Mock
    private ToolApprovalRepository repository;

    @Mock
    private FileWriteService fileWriteService;

    /** 快照：本测试不关心它的内部行为，只验证"批准时会去捕获快照"。 */
    @Mock
    private SnapshotService snapshotService;

    /** 跨实例唤醒通道：单测里进程内唤醒走 wake()，广播本身不参与断言。 */
    @Mock
    private ApprovalWakeChannel wakeChannel;

    private ApprovalService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalService(repository, fileWriteService, snapshotService, wakeChannel);
        ReflectionTestUtils.setField(service, "keepDays", 7);
        // 默认给一个较短的超时，避免"忘了设"的用例真的挂 5 分钟；
        // 需要验证唤醒的用例会自行改长。
        ReflectionTestUtils.setField(service, "blockingTimeoutSeconds", 1);
    }

    private static ToolApproval pending(String approvalId, String userId, String toolName, String args) {
        return ToolApproval.builder()
                .approvalId(approvalId)
                .tenantId("t1")
                .userId(userId)
                .toolName(toolName)
                .toolArgs(args)
                .summary("将修改某文件")
                .status(ToolApprovalStatus.pending)
                .build();
    }

    // ---------------- 提交 ----------------

    @Test
    @DisplayName("提交：落库为 pending，参数被序列化成 JSON 快照")
    void submitCreatesPendingWithArgsSnapshot() {
        ArgumentCaptor<ToolApproval> captor = ArgumentCaptor.forClass(ToolApproval.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("path", "src/A.java").put("content", "hi");
        String id = service.submit("t1", "a1", "s1", "r1", "u1", "fs_write_file", args, "新建 src/A.java");

        assertNotNull(id);
        ToolApproval saved = captor.getValue();
        assertEquals(ToolApprovalStatus.pending, saved.getStatus());
        assertTrue(saved.getToolArgs().contains("src/A.java"), "参数快照应包含路径：" + saved.getToolArgs());
        assertEquals("s1", saved.getSessionId(), "会话归属要记下来，前端才能把审批挂到会话上");
    }

    @Test
    @DisplayName("提交：tenantId 为空时归一到 default")
    void submitNormalizesTenant() {
        ArgumentCaptor<ToolApproval> captor = ArgumentCaptor.forClass(ToolApproval.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.submit(null, null, null, null, null, "fs_write_file", null, null);

        assertEquals("default", captor.getValue().getTenantId());
    }

    // ---------------- 批准 ----------------

    @Test
    @DisplayName("批准：执行成功 → status=approved 且记录结果（执行前先捕获快照）")
    void approveExecutesAndRecordsResult() {
        when(repository.findByApprovalIdAndTenantId("appr_1", "t1"))
                .thenReturn(Optional.of(pending("appr_1", "u1", "fs_write_file", "{\"path\":\"a.txt\"}")));
        when(fileWriteService.writeFile(anyString(), any(), eq(false)))
                .thenReturn(new FileWriteService.WriteOutcome("a.txt", false, 5));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ToolApproval r = service.approve("t1", "u1", "appr_1");

        assertEquals(ToolApprovalStatus.approved, r.getStatus());
        assertEquals("u1", r.getDecidedBy());
        assertNotNull(r.getDecidedAt());
        assertNull(r.getErrorMsg());
        assertTrue(r.getResult().contains("a.txt"), "结果摘要应提到改动的文件：" + r.getResult());
        // ★ 回滚的前提：批准时必须先捕获快照（否则写操作不可撤销）
        verify(snapshotService).capture(eq("appr_1"), any());
    }

    @Test
    @DisplayName("★ 快照捕获失败 → 不执行写入（没有快照的写操作不可撤销）")
    void approveAbortsWhenSnapshotFails() {
        when(repository.findByApprovalIdAndTenantId("appr_1", "t1"))
                .thenReturn(Optional.of(pending("appr_1", "u1", "fs_write_file", "{\"path\":\"a.txt\"}")));
        when(snapshotService.capture(anyString(), any()))
                .thenThrow(new BizException("INTERNAL_ERROR", "磁盘写入失败"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ToolApproval r = service.approve("t1", "u1", "appr_1");

        assertTrue(r.getErrorMsg().contains("磁盘写入失败"), "失败原因要可见：" + r.getErrorMsg());
        verify(fileWriteService, never()).writeFile(anyString(), any(), eq(false));
    }

    @Test
    @DisplayName("★ 批准但执行失败 → 仍记 approved，但错误写进 errorMsg（不能静默成功）")
    void approveRecordsExecutionFailure() {
        when(repository.findByApprovalIdAndTenantId("appr_1", "t1"))
                .thenReturn(Optional.of(pending("appr_1", "u1", "fs_write_file", "{}")));
        when(fileWriteService.writeFile(anyString(), any(), eq(false)))
                .thenThrow(new BizException("BAD_REQUEST", "路径超出工作区范围"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ToolApproval r = service.approve("t1", "u1", "appr_1");

        assertEquals(ToolApprovalStatus.approved, r.getStatus());
        assertTrue(r.getErrorMsg().contains("超出工作区"), "失败原因必须可见：" + r.getErrorMsg());
        assertNull(r.getResult());
    }

    @Test
    @DisplayName("★ 隔离：不能批准别人提交的申请（多一条路径就多一个绕过口）")
    void cannotApproveOthersRequest() {
        when(repository.findByApprovalIdAndTenantId("appr_1", "t1"))
                .thenReturn(Optional.of(pending("appr_1", "u2", "fs_write_file", "{}")));

        BizException ex = assertThrows(BizException.class, () -> service.approve("t1", "u1", "appr_1"));

        assertTrue(ex.getMessage().contains("只能审批自己"), ex.getMessage());
        verify(fileWriteService, never()).writeFile(anyString(), any(), eq(false));
    }

    @Test
    @DisplayName("隔离：按 approvalId + tenantId 查，跨租户查不到")
    void crossTenantNotFound() {
        when(repository.findByApprovalIdAndTenantId("appr_1", "t2")).thenReturn(Optional.empty());

        assertThrows(BizException.class, () -> service.approve("t2", "u1", "appr_1"));
    }

    @Test
    @DisplayName("不能重复批准：已处理的申请再批 → 冲突报错")
    void cannotApproveTwice() {
        ToolApproval done = pending("appr_1", "u1", "fs_write_file", "{}");
        done.setStatus(ToolApprovalStatus.approved);
        when(repository.findByApprovalIdAndTenantId("appr_1", "t1")).thenReturn(Optional.of(done));

        BizException ex = assertThrows(BizException.class, () -> service.approve("t1", "u1", "appr_1"));

        assertTrue(ex.getMessage().contains("已处理"), ex.getMessage());
    }

    @Test
    @DisplayName("缺少用户身份 → 拒绝（fail-closed，不能出现「无主体的批准」）")
    void approveRequiresUser() {
        assertThrows(BizException.class, () -> service.approve("t1", null, "appr_1"));
        assertThrows(BizException.class, () -> service.approve("t1", "  ", "appr_1"));
    }

    // ---------------- 拒绝 ----------------

    @Test
    @DisplayName("拒绝：状态置 rejected 且不执行任何写操作")
    void rejectDoesNotExecute() {
        when(repository.findByApprovalIdAndTenantId("appr_1", "t1"))
                .thenReturn(Optional.of(pending("appr_1", "u1", "fs_write_file", "{}")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ToolApproval r = service.reject("t1", "u1", "appr_1", "这次改动不需要");

        assertEquals(ToolApprovalStatus.rejected, r.getStatus());
        assertEquals("这次改动不需要", r.getErrorMsg());
        verify(fileWriteService, never()).writeFile(anyString(), any(), eq(false));
        verify(fileWriteService, never()).editFile(anyString(), anyString(), anyString(), eq(false));
    }

    // ---------------- 过期 ----------------

    @Test
    @DisplayName("过期清理：按保留期算出截止时间并委托仓储批量标记")
    void expireStaleUsesKeepDays() {
        when(repository.expirePendingBefore(any(LocalDateTime.class))).thenReturn(3);

        assertEquals(3, service.expireStale());
        verify(repository).expirePendingBefore(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("过期清理：keepDays 非法时回落到 7 天（不因为配置写错就清空全部）")
    void expireStaleFallsBackToSevenDays() {
        ReflectionTestUtils.setField(service, "keepDays", 0);
        when(repository.expirePendingBefore(any(LocalDateTime.class))).thenReturn(0);

        service.expireStale();

        verify(repository).expirePendingBefore(any(LocalDateTime.class));
    }

    // ---------------- 就地确认（阻塞等待） ----------------

    @Test
    @DisplayName("★ 就地确认：等待前用户已经点了批准 → 立即返回，不白等")
    void awaitDecisionReturnsImmediatelyWhenAlreadyDecided() {
        ToolApproval done = pending("appr_1", "u1", "fs_write_file", "{}");
        done.setStatus(ToolApprovalStatus.approved);
        done.setResult("已覆盖 a.txt");
        when(repository.findByApprovalIdAndTenantId("appr_1", "t1")).thenReturn(Optional.of(done));

        ApprovalService.WaitOutcome out = service.awaitDecision("appr_1", "t1");

        assertTrue(out.decided());
        assertTrue(out.approved());
        assertEquals("已覆盖 a.txt", out.result());
    }

    @Test
    @DisplayName("★ 就地确认：等待前用户已拒绝 → 立即返回 rejected（工具据此告诉模型别重试）")
    void awaitDecisionReturnsRejectedImmediately() {
        ToolApproval done = pending("appr_1", "u1", "fs_write_file", "{}");
        done.setStatus(ToolApprovalStatus.rejected);
        when(repository.findByApprovalIdAndTenantId("appr_1", "t1")).thenReturn(Optional.of(done));

        ApprovalService.WaitOutcome out = service.awaitDecision("appr_1", "t1");

        assertTrue(out.decided());
        assertFalse(out.approved());
        assertEquals(ToolApprovalStatus.rejected, out.status());
    }

    @Test
    @DisplayName("★ 就地确认：没人处理 → 超时后 decided=false（调用方降级为两步式）")
    void awaitDecisionTimesOut() {
        // 超时设 1 秒，避免测试挂太久；仓储始终返回 pending（无人处理）
        ReflectionTestUtils.setField(service, "blockingTimeoutSeconds", 1);
        when(repository.findByApprovalIdAndTenantId("appr_1", "t1"))
                .thenReturn(Optional.of(pending("appr_1", "u1", "fs_write_file", "{}")));

        ApprovalService.WaitOutcome out = service.awaitDecision("appr_1", "t1");

        assertFalse(out.decided(), "超时应明确返回未决，调用方据此降级");
    }

    @Test
    @DisplayName("★ 就地确认：等待期间用户批准 → 阻塞方被唤醒并拿到执行结果")
    void awaitDecisionWakesUpOnApprove() throws Exception {
        ReflectionTestUtils.setField(service, "blockingTimeoutSeconds", 10);
        ToolApproval row = pending("appr_1", "u1", "fs_write_file", "{\"path\":\"a.txt\"}");
        // 前两次查询（await 的两次复查）返回 pending，之后 approve 走自己的查询
        when(repository.findByApprovalIdAndTenantId("appr_1", "t1"))
                .thenReturn(Optional.of(row));
        when(fileWriteService.writeFile(anyString(), any(), eq(false)))
                .thenReturn(new FileWriteService.WriteOutcome("a.txt", false, 3));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 另起线程：模拟"用户点了批准"
        Thread approver = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            service.approve("t1", "u1", "appr_1");
        });
        approver.start();

        ApprovalService.WaitOutcome out = service.awaitDecision("appr_1", "t1");
        approver.join(2000);

        assertTrue(out.decided(), "批准后等待方应被唤醒");
        assertTrue(out.approved());
        assertNotNull(out.result(), "应拿到执行结果");
    }

    @Test
    @DisplayName("就地确认：approvalId 为空 → 直接按超时处理（不注册等待器）")
    void awaitDecisionWithBlankId() {
        assertFalse(service.awaitDecision(null, "t1").decided());
        assertFalse(service.awaitDecision("  ", "t1").decided());
    }

    // ---------------- 视图 ----------------

    @Test
    @DisplayName("视图：暴露业务 ID 与中文状态名，不暴露自增主键")
    void viewHidesPhysicalId() {
        ToolApproval row = pending("appr_1", "u1", "fs_write_file", "{}");
        row.setId(99L);

        var view = ApprovalService.toView(row);

        assertEquals("appr_1", view.get("approvalId"));
        assertEquals("待审批", view.get("statusLabel"));
        assertNull(view.get("id"), "自增物理主键不该对外暴露");
    }
}
