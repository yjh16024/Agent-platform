package com.agentplatform.core.tool.approval;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.tool.fs.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改前快照与回滚的单元测试（2026-09-23）。
 *
 * <p>回滚是"用户敢让智能体改文件"的前提，所以测试重点在**还原是否准确**：
 * 已存在的文件要恢复原内容、新建的文件要删掉、没快照的不能假装成功。</p>
 */
class SnapshotServiceTest {

    @TempDir
    Path tmp;

    private Path workspaceRoot;
    private Path snapshotRoot;
    private WorkspaceService workspace;
    private SnapshotService service;

    @BeforeEach
    void setUp() throws IOException {
        workspaceRoot = tmp.resolve("workspace");
        snapshotRoot = tmp.resolve("snapshots");
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(snapshotRoot);

        workspace = new WorkspaceService();
        ReflectionTestUtils.setField(workspace, "rootConfig", workspaceRoot.toString());
        ReflectionTestUtils.setField(workspace, "enabled", true);
        ReflectionTestUtils.setField(workspace, "maxReadBytes", 262144);
        ReflectionTestUtils.setField(workspace, "maxEntries", 500);
        ReflectionTestUtils.setField(workspace, "maxMatches", 200);
        ReflectionTestUtils.setField(workspace, "maxLineChars", 300);

        service = new SnapshotService(workspace);
        ReflectionTestUtils.setField(service, "snapshotRootConfig", snapshotRoot.toString());
        ReflectionTestUtils.setField(service, "snapshotKeepDays", 7);
    }

    // ---------------- 捕获 + 回滚：已存在的文件 ----------------

    @Test
    @DisplayName("★ 回滚：已存在的文件恢复成快照那一刻的内容")
    void rollbackRestoresExistingFile() throws IOException {
        Path file = workspaceRoot.resolve("A.java");
        Files.writeString(file, "原始内容", StandardCharsets.UTF_8);

        Path snap = service.capture("appr_1", List.of("A.java"));
        assertNotNull(snap);

        // 模拟批准后执行了写操作
        Files.writeString(file, "被智能体改过的内容", StandardCharsets.UTF_8);
        assertEquals("被智能体改过的内容", Files.readString(file));

        int restored = service.rollback("appr_1");

        assertEquals(1, restored);
        assertEquals("原始内容", Files.readString(file), "应恢复成快照时的内容");
    }

    @Test
    @DisplayName("★ 回滚：改动前**不存在**的文件 → 回滚动作是删掉它")
    void rollbackDeletesNewlyCreatedFile() throws IOException {
        // 快照时文件还不存在
        service.capture("appr_2", List.of("New.java"));

        // 模拟批准后新建了文件
        Path file = workspaceRoot.resolve("New.java");
        Files.writeString(file, "新建的内容", StandardCharsets.UTF_8);
        assertTrue(Files.exists(file));

        int restored = service.rollback("appr_2");

        assertEquals(1, restored);
        assertFalse(Files.exists(file), "新建的文件回滚后应被删除");
    }

    @Test
    @DisplayName("回滚：新建的文件若已被手动删掉，不报错（幂等）")
    void rollbackToleratesAlreadyDeletedFile() {
        service.capture("appr_3", List.of("Gone.java"));

        // 文件从未创建（或已被手动删除）
        assertEquals(0, service.rollback("appr_3"));
    }

    @Test
    @DisplayName("回滚：子目录内的文件也能正确还原（目录结构要镜像）")
    void rollbackHandlesNestedPath() throws IOException {
        Path dir = workspaceRoot.resolve("src/main");
        Files.createDirectories(dir);
        Path file = dir.resolve("App.java");
        Files.writeString(file, "v1", StandardCharsets.UTF_8);

        service.capture("appr_4", List.of("src/main/App.java"));
        Files.writeString(file, "v2", StandardCharsets.UTF_8);

        service.rollback("appr_4");

        assertEquals("v1", Files.readString(file));
    }

    @Test
    @DisplayName("回滚：一次快照覆盖多个文件时全部还原")
    void rollbackRestoresMultipleFiles() throws IOException {
        Path a = workspaceRoot.resolve("a.txt");
        Path b = workspaceRoot.resolve("b.txt");
        Files.writeString(a, "a-orig", StandardCharsets.UTF_8);
        Files.writeString(b, "b-orig", StandardCharsets.UTF_8);

        service.capture("appr_5", List.of("a.txt", "b.txt"));
        Files.writeString(a, "a-new", StandardCharsets.UTF_8);
        Files.writeString(b, "b-new", StandardCharsets.UTF_8);
        Files.writeString(workspaceRoot.resolve("c.txt"), "c-new", StandardCharsets.UTF_8);

        service.rollback("appr_5");

        assertEquals("a-orig", Files.readString(a));
        assertEquals("b-orig", Files.readString(b));
    }

    // ---------------- 边界 ----------------

    @Test
    @DisplayName("捕获：路径列表为空 → 不产生快照目录（返回 null）")
    void captureEmptyReturnsNull() {
        assertNull(service.capture("appr_x", List.of()));
        assertNull(service.capture("appr_x", null));
        assertNull(service.capture(null, List.of("a.txt")));
    }

    @Test
    @DisplayName("回滚：没有快照 → 明确报错，而不是假装成功")
    void rollbackWithoutSnapshotFails() {
        BizException ex = assertThrows(BizException.class, () -> service.rollback("not-exists"));

        assertTrue(ex.getMessage().contains("没有可用的快照"), ex.getMessage());
    }

    @Test
    @DisplayName("回滚：清单缺失 → 拒绝（不能凭猜测去改文件）")
    void rollbackWithoutManifestFails() throws IOException {
        Path dir = snapshotRoot.resolve("appr_6");
        Files.createDirectories(dir);   // 只有目录，没有清单

        BizException ex = assertThrows(BizException.class, () -> service.rollback("appr_6"));

        assertTrue(ex.getMessage().contains("清单"), ex.getMessage());
    }

    @Test
    @DisplayName("越界：工作区外的路径不会被快照（解析失败即跳过）")
    void captureSkipsOutOfWorkspacePaths() {
        // 越界路径在 resolve 阶段就会抛异常，capture 会跳过它
        Path snap = service.capture("appr_7", List.of("../../etc/passwd"));

        assertNotNull(snap, "快照目录仍会建立，只是内容为空");
        assertEquals(0, service.rollback("appr_7"), "没有任何文件被备份");
    }

    @Test
    @DisplayName("清理：超过保留期的快照目录被删除")
    void purgeExpiredRemovesOldSnapshotDirs() throws IOException {
        Path old = snapshotRoot.resolve("appr_old");
        Files.createDirectories(old);
        Files.writeString(old.resolve("x.txt"), "old", StandardCharsets.UTF_8);
        // 把目录时间改成很久以前
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 30L * 24 * 3600 * 1000));

        Path fresh = snapshotRoot.resolve("appr_fresh");
        Files.createDirectories(fresh);

        int removed = service.purgeExpired();

        assertEquals(1, removed);
        assertFalse(Files.exists(old), "超期快照应被清理");
        assertTrue(Files.exists(fresh), "未超期的不该被删");
    }

    @Test
    @DisplayName("清理：keepDays 非法时回落到 7 天（不能因为配置写错就全清）")
    void purgeExpiredFallsBackToSevenDays() {
        ReflectionTestUtils.setField(service, "snapshotKeepDays", 0);

        assertEquals(7, service.snapshotKeepDays());
    }
}
