package com.agentplatform.core.tool.fs;

import com.agentplatform.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 工作区根约束的单元测试（2026-09-23）。
 *
 * <p>这是 {@code fs_*} 工具的**唯一安全边界** —— 这里漏一个口子，
 * 后面的工具再怎么写都是白搭。所以测试的重点不是"正常路径能读"，
 * 而是**各种越界写法都要被挡住**，尤其是符号链接（纯字符串校验挡不住）。</p>
 */
class WorkspaceServiceTest {

    @TempDir
    Path tmp;

    private Path root;
    private WorkspaceService service;

    @BeforeEach
    void setUp() throws IOException {
        root = tmp.resolve("workspace");
        Files.createDirectories(root);
        Files.writeString(root.resolve("hello.txt"), "hello world\nline two\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "class App {}\n", StandardCharsets.UTF_8);

        service = new WorkspaceService();
        ReflectionTestUtils.setField(service, "rootConfig", root.toString());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxReadBytes", 262144);
        ReflectionTestUtils.setField(service, "maxEntries", 500);
        ReflectionTestUtils.setField(service, "maxMatches", 200);
        ReflectionTestUtils.setField(service, "maxLineChars", 300);
    }

    @AfterEach
    void tearDown() {
        // @TempDir 由 JUnit 负责清理
    }

    // ---------------- 正常路径 ----------------

    @Test
    @DisplayName("正常：相对路径能解析到工作区内的真实文件")
    void resolvesRelativePath() {
        Path p = service.resolve("hello.txt", true);

        assertTrue(Files.isRegularFile(p));
        assertEquals("hello world\nline two\n", readText(p));
    }

    @Test
    @DisplayName("正常：./ 与开头 / 都归一（三种写法等价）")
    void normalizesLeadingSlashAndDot() {
        Path a = service.resolve("src/App.java", true);
        Path b = service.resolve("./src/App.java", true);
        Path c = service.resolve("/src/App.java", true);

        assertEquals(a, b);
        assertEquals(a, c);
    }

    @Test
    @DisplayName("正常：空的相对路径表示工作区根")
    void blankMeansRoot() throws IOException {
        assertEquals(root.toRealPath(), service.resolve("", true));
        assertEquals(root.toRealPath(), service.resolve(null, true));
    }

    @Test
    @DisplayName("正常：relative() 统一用 / 分隔")
    void relativeUsesForwardSlash() throws IOException {
        Path p = service.resolve("src/App.java", true);

        assertEquals("src/App.java", service.relative(p));
    }

    // ---------------- 越界（关键） ----------------

    @Test
    @DisplayName("★ 越界：../ 穿越被拒（normalize + startsWith 挡住）")
    void rejectsParentTraversal() {
        assertThrows(BizException.class, () -> service.resolve("../../etc/passwd", true));
        assertThrows(BizException.class, () -> service.resolve("../outside.txt", true));
        assertThrows(BizException.class, () -> service.resolve("src/../../outside.txt", true));
    }

    @Test
    @DisplayName("★ 越界：绝对路径被拒（Path.resolve 遇到绝对路径会丢弃 base，必须显式挡掉）")
    void rejectsAbsolutePath() {
        // Windows 盘符 / Unix 根：都应以"必须是相对路径"为由拒绝
        assertThrows(BizException.class,
                () -> service.resolve(root.getRoot().toString(), true));
        assertThrows(BizException.class, () -> service.resolve("C:/Windows/system32", true));
        assertThrows(BizException.class, () -> service.resolve("~/secrets", true));
    }

    @Test
    @DisplayName("★ 越界：符号链接指向工作区外 → 拒绝（纯字符串校验挡不住这一种）")
    void rejectsSymlinkEscape() throws IOException {
        Path outside = tmp.resolve("outside-secret.txt");
        Files.writeString(outside, "TOP SECRET", StandardCharsets.UTF_8);
        Path link = root.resolve("link-to-outside.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows 上非管理员/未开开发者模式时创建软链会失败，此时跳过该断言
            assumeTrue(false, "当前环境不支持创建符号链接，跳过：" + e.getMessage());
        }

        // 字符串上看 "link-to-outside.txt" 完全合法，但真实路径在工作区外
        assertThrows(BizException.class, () -> service.resolve("link-to-outside.txt", true));
    }

    @Test
    @DisplayName("★ 越界：符号链接指向工作区内的目录 → 允许（不能把正常用法一起误杀）")
    void allowsSymlinkInsideWorkspace() throws IOException {
        Path link = root.resolve("link-src");
        try {
            Files.createSymbolicLink(link, root.resolve("src"));
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "当前环境不支持创建符号链接，跳过：" + e.getMessage());
        }

        Path p = service.resolve("link-src/App.java", true);

        assertTrue(Files.isRegularFile(p));
    }

    @Test
    @DisplayName("越界：跳过名单内的目录不可访问（.git / node_modules 等）")
    void rejectsSkipDirs() {
        assertThrows(BizException.class, () -> service.resolve(".git/config", true));
        assertThrows(BizException.class, () -> service.resolve("node_modules/foo/index.js", true));
        assertThrows(BizException.class, () -> service.resolve("target/classes/A.class", true));
    }

    @Test
    @DisplayName("越界：路径不存在时报 not found（而不是静默返回）")
    void missingPathThrows() {
        assertThrows(BizException.class, () -> service.resolve("nope.txt", true));
    }

    @Test
    @DisplayName("越界：总开关关闭时一律拒绝")
    void disabledRejectsAll() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertFalse(service.available());
        assertThrows(BizException.class, () -> service.resolve("hello.txt", true));
    }

    @Test
    @DisplayName("mustExist=false 时允许解析尚不存在的路径（为将来的写工具预留）")
    void allowsNonExistentWhenNotRequired() {
        Path p = service.resolve("new-file.txt", false);

        assertTrue(p.startsWith(root));
        assertFalse(Files.exists(p));
    }

    // ---------------- 凭证隔离（第四道防线） ----------------

    @Test
    @DisplayName("★ 凭证隔离：工作区内的 .env / 密钥 / shell 配置一律拒绝（读与写都拒）")
    void rejectsProtectedPaths() throws IOException {
        Files.writeString(root.resolve(".env"), "JWT_SECRET=real-secret\n", StandardCharsets.UTF_8);

        // 读场景（mustExist=true）
        assertThrows(BizException.class, () -> service.resolve(".env", true));
        assertThrows(BizException.class, () -> service.resolve(".bashrc", true));
        assertThrows(BizException.class, () -> service.resolve("server.key", true));
        // ★ 写场景（mustExist=false）必须同样被拦 ——
        // 否则模型可以给别的程序换一份密钥、或写个 .bashrc 等下次执行
        assertThrows(BizException.class, () -> service.resolve(".env", false));
        assertThrows(BizException.class, () -> service.resolve(".gitconfig", false));
        assertThrows(BizException.class, () -> service.resolve(".claude/settings.json", false));
    }

    @Test
    @DisplayName("★ 凭证隔离：绕过写法（大小写 / 子目录 / 深层）同样被拒")
    void rejectsProtectedPathBypasses() {
        assertThrows(BizException.class, () -> service.resolve(".ENV", false));
        assertThrows(BizException.class, () -> service.resolve("config/.env.local", false));
        assertThrows(BizException.class, () -> service.resolve("a/b/c/.env", false));
        assertThrows(BizException.class, () -> service.resolve("deep/.ssh/id_rsa", false));

        // 注：".env "（尾随空格）刻意不在这里断言 —— 实测 Java 的 Path 在 Windows 上
        // 直接拒绝这种名字（InvalidPathException: Trailing char），
        // 它在进入本服务的名单检查之前就被 JDK 挡掉了，断言它只会得到一个假信息。
    }

    @Test
    @DisplayName("凭证隔离：遍历时跳过凭据目录，且不把 .env 当可读文本（grep 搜不出内容）")
    void protectedPathsInvisibleToTraversal() throws IOException {
        Files.createDirectories(root.resolve(".ssh"));
        Files.writeString(root.resolve(".ssh/id_rsa"), "PRIVATE KEY MATERIAL", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".env"), "SECRET=1\n", StandardCharsets.UTF_8);

        assertTrue(service.shouldSkipDir(root.resolve(".ssh")));
        assertFalse(service.readableText(root.resolve(".env")));
        assertFalse(service.readableText(root.resolve(".ssh/id_rsa")));
    }

    @Test
    @DisplayName("凭证隔离：普通文件完全不受影响（回归 —— 误伤等于逼人关掉这层保护）")
    void protectedPathsKeepNormalFilesWorking() {
        assertDoesNotThrow(() -> service.resolve("hello.txt", true));
        assertDoesNotThrow(() -> service.resolve("src/App.java", true));
        assertDoesNotThrow(() -> service.resolve("application.yml", false));
        assertTrue(service.readableText(root.resolve("hello.txt")));
    }

    // ---------------- 类型判定 ----------------

    @Test
    @DisplayName("文本判定：常见源码/配置后缀算文本")
    void textByExtension() throws IOException {
        assertTrue(service.readableText(root.resolve("src/App.java")));
        assertTrue(service.readableText(root.resolve("hello.txt")));
    }

    @Test
    @DisplayName("文本判定：已知二进制后缀不算文本")
    void binaryByExtension() throws IOException {
        Path png = root.resolve("logo.png");
        Files.write(png, new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        assertFalse(service.readableText(png));
        assertTrue(service.isBinaryByName(png));
    }

    private static String readText(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
