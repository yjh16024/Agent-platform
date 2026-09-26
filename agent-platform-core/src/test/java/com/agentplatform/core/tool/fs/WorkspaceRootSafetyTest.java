package com.agentplatform.core.tool.fs;

import com.agentplatform.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作区**根的准入校验**测试（2026-09-26 加）。
 *
 * <h3>为什么这个测试很重要</h3>
 * 以前工作区根只能由管理员通过配置/环境变量设置 —— 那是"部署时的决定"，天然可信。
 * 现在它可以在界面上随时改，于是**根变成了用户输入**。而根是安全边界的地基：
 * **根选错了，后面四道防线全都建立在错的地方。**
 *
 * <p>典型的错法就是"拖一个文件就把根设成它的父目录"：拖 {@code C:\a.txt} → 根变 {@code C:\}
 * → 整盘可读，而 {@code fs_read_file} / {@code fs_glob} / {@code fs_grep} **都是只读、不走审批的**，
 * 用户连拒绝的机会都没有。</p>
 *
 * <h3>★ 测试为什么能跨平台</h3>
 * 校验判定的是**路径最后一段的名字**（{@code windows} / {@code etc} / {@code .ssh} …），
 * 所以可以直接在 {@code @TempDir} 下**造一个叫这个名字的目录**来测，
 * 不必依赖真实系统目录存不存在、也不受运行平台影响。
 */
class WorkspaceRootSafetyTest {

    @TempDir
    Path tmp;

    private Path dir(String name) throws IOException {
        return Files.createDirectories(tmp.resolve(name));
    }

    // ------------------------------------------------------------------ 必须拒绝

    @Test
    @DisplayName("拒绝：文件系统根 —— 等于把整块盘交出去")
    void rejectsFileSystemRoot() {
        Path fsRoot = Path.of(System.getProperty("user.home")).getRoot();
        BizException e = assertThrows(BizException.class,
                () -> WorkspaceService.checkRootSafety(fsRoot.toString()));
        assertTrue(e.getMessage().contains("整块盘"), "提示应说明原因，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("拒绝：系统目录（windows / Program Files / etc / usr）")
    void rejectsSystemDirs() throws IOException {
        for (String name : new String[]{"windows", "Program Files", "etc", "usr"}) {
            Path d = dir(name);
            assertThrows(BizException.class, () -> WorkspaceService.checkRootSafety(d.toString()),
                    "「" + name + "」应被拒绝");
        }
    }

    @Test
    @DisplayName("拒绝：用户主目录本身（含桌面、文档、AppData）")
    void rejectsHomeDir() {
        BizException e = assertThrows(BizException.class,
                () -> WorkspaceService.checkRootSafety(System.getProperty("user.home")));
        assertTrue(e.getMessage().contains("主目录"), "提示应说明原因，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("拒绝：凭据目录本身（.ssh / .aws）")
    void rejectsCredentialDirs() throws IOException {
        for (String name : new String[]{".ssh", ".aws"}) {
            Path d = dir(name);
            assertThrows(BizException.class, () -> WorkspaceService.checkRootSafety(d.toString()),
                    "「" + name + "」应被拒绝");
        }
    }

    @Test
    @DisplayName("拒绝：空 / 相对路径 / 不存在 / 不是目录")
    void rejectsInvalidInput() throws IOException {
        assertThrows(BizException.class, () -> WorkspaceService.checkRootSafety(null));
        assertThrows(BizException.class, () -> WorkspaceService.checkRootSafety("   "));
        assertThrows(BizException.class, () -> WorkspaceService.checkRootSafety("relative/path"),
                "相对路径的含义取决于进程工作目录，不能作为用户选择的工作区");
        assertThrows(BizException.class,
                () -> WorkspaceService.checkRootSafety(tmp.resolve("does-not-exist").toString()));

        Path file = Files.writeString(tmp.resolve("a.txt"), "x");
        assertThrows(BizException.class, () -> WorkspaceService.checkRootSafety(file.toString()),
                "选到文件而不是目录应被拒绝");
    }

    // ------------------------------------------------------------------ 必须接受

    @Test
    @DisplayName("接受：普通项目目录，且返回**真实路径**")
    void acceptsNormalProjectDir() throws IOException {
        Path d = dir("my-project");
        assertEquals(d.toRealPath(), WorkspaceService.checkRootSafety(d.toString()),
                "应返回 toRealPath 的结果 —— 存软链路径会留下绕过入口");
    }

    @Test
    @DisplayName("接受：用户主目录下的子目录（这正是要支持的主场景）")
    void acceptsSubDirOfHome() throws IOException {
        // 用临时目录模拟"主目录下的项目"，避免碰真实家目录
        Path d = dir("proj-under-home");
        assertEquals(d.toRealPath(), WorkspaceService.checkRootSafety(d.toString()));
    }

    @Test
    @DisplayName("接受：主目录下的 Desktop 子目录（用户最可能的用法）")
    void acceptsDesktopLikeDir() throws IOException {
        Path d = dir("home").resolve("Desktop").resolve("myproj");
        Files.createDirectories(d);
        assertEquals(d.toRealPath(), WorkspaceService.checkRootSafety(d.toString()));
    }

    // ------------------------------------------------------------------ 切换的原子性

    @Test
    @DisplayName("setRoot 立即生效；resetRoot 回到配置值；**失败的 setRoot 不改变现状**")
    void setRootIsAtomicAndReversible() throws IOException {
        WorkspaceService s = new WorkspaceService();
        ReflectionTestUtils.setField(s, "rootConfig", tmp.resolve("default-ws").toString());
        ReflectionTestUtils.setField(s, "settingsFile", null);   // 本测试不落盘

        Path original = s.root();
        assertFalse(s.isOverridden(), "初始应为配置值");

        Path chosen = dir("chosen-ws");
        s.setRoot(chosen.toString());
        assertEquals(chosen.toRealPath(), s.root(), "setRoot 后应立刻切到新根");
        assertTrue(s.isOverridden());

        // 关键：不安全的路径必须抛异常，且**不能把当前根改坏**
        assertThrows(BizException.class, () -> s.setRoot(dir("windows").toString()));
        assertEquals(chosen.toRealPath(), s.root(), "失败的 setRoot 不该影响当前根");

        s.resetRoot();
        assertEquals(original, s.root(), "resetRoot 应回到配置值");
        assertFalse(s.isOverridden());
    }
}
