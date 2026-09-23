package com.agentplatform.core.tool.fs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭证隔离（受保护路径）的单元测试（2026-09-23）。
 *
 * <p>重点不是"正常文件能过"，而是**各种绕过写法都要被挡住** ——
 * 名单式防护最典型的失败就是"看起来是同一个文件、字符串却不相等"：
 * 加个尾随空格、换个大小写、塞进子目录，都能骗过只在字符串上做精确匹配的实现。
 * 所以下面每一组"绕过写法"都对应一类真实的躲法。</p>
 */
class ProtectedPathsTest {

    private Path root;
    private ProtectedPaths guard;

    @BeforeEach
    void setUp() {
        root = Path.of("build", "tmp-ws").toAbsolutePath().normalize();
        guard = new ProtectedPaths();   // 字段初始值即默认配置（enabled=true、无追加项）
    }

    /** 该相对路径是否被拒。 */
    private boolean blocked(String relative) {
        return guard.reason(root.resolve(relative), root) != null;
    }

    // ------------------------------------------------------------------ 正常路径（不能误伤）

    @Test
    @DisplayName("正常：普通源码与文档一律放行（安全机制误伤就等于没有）")
    void allowsOrdinaryFiles() {
        assertNull(guard.reason(root.resolve("src/main/java/Foo.java"), root));
        assertNull(guard.reason(root.resolve("README.md"), root));
        assertNull(guard.reason(root.resolve("pom.xml"), root));
        assertNull(guard.reason(root.resolve("application.yml"), root));   // 主配置：项目约定是 ${ENV:默认值}，不含真密钥
        assertNull(guard.reason(root.resolve(".gitignore"), root));
        assertNull(guard.reason(root.resolve("package.json"), root));
        assertNull(guard.reason(root.resolve("frontend/src/App.tsx"), root));
    }

    @Test
    @DisplayName("正常：与受保护项**相似但不同**的名字不能误伤")
    void doesNotBlockLookalikes() {
        // 无扩展名、只是名字里含 key —— 只有 .key 后缀才算密钥
        assertNull(guard.reason(root.resolve("keyfile"), root));
        assertNull(guard.reason(root.resolve("src/keys.txt"), root));
        // objects 只在**工作区顶层**才拦（防 git 仓库伪装），子目录里的同名文件是正常的
        assertNull(guard.reason(root.resolve("src/objects"), root));
        // .envrc 是 direnv 的配置，不在 .env 变体规则内
        assertNull(guard.reason(root.resolve(".envrc"), root));
        // 公钥不是秘密
        assertNull(guard.reason(root.resolve("id_rsa.pub"), root));
    }

    // ------------------------------------------------------------------ 凭证：基础形态

    @Test
    @DisplayName("凭证：.env 及其全部变体被拒")
    void blocksEnvVariants() {
        assertTrue(blocked(".env"));
        assertTrue(blocked(".env.local"));
        assertTrue(blocked(".env.production"));
        assertTrue(blocked(".env.development.local"));
        assertTrue(blocked("config/.env"));
        assertTrue(blocked("apps/web/configs/.env.local"));
        // 模板也算（无法可靠区分它有没有被填过真值）
        assertTrue(blocked(".env.example"));
    }

    @Test
    @DisplayName("★ 凭证：绕过写法（大小写 / 子目录）也要被拒")
    void blocksEnvBypassAttempts() {
        // Windows 文件系统不区分大小写：.ENV 就是 .env
        assertTrue(blocked(".ENV"));
        assertTrue(blocked(".Env.Local"));
        // 藏进子目录不改变性质
        assertTrue(blocked("a/b/c/.env"));
        assertTrue(blocked("deploy/.env.production"));

        // ⚠️ 这里**刻意不测** ".env " / ".env."（尾随空格与点）：
        // 写这组用例时才发现，Java 的 Path 在 Windows 上直接拒绝这类名字
        // （InvalidPathException: Trailing char），连 Path 对象都构造不出来 ——
        // 也就是说这条绕过路径到不了本类。normalize() 里仍保留归一处理作兜底，
        // 理由见该方法注释。
    }

    @Test
    @DisplayName("凭证：文件名名单（各工具存放 token 的文件）")
    void blocksCredentialFileNames() {
        assertTrue(blocked(".npmrc"));
        assertTrue(blocked(".netrc"));
        assertTrue(blocked(".pypirc"));
        assertTrue(blocked(".htpasswd"));
        assertTrue(blocked(".mcp.json"));
        assertTrue(blocked("id_rsa"));
        assertTrue(blocked("id_ed25519"));
        assertTrue(blocked("credentials.json"));
    }

    @Test
    @DisplayName("凭证：密钥与密钥库扩展名")
    void blocksCredentialExtensions() {
        assertTrue(blocked("server.key"));
        assertTrue(blocked("cert.pem"));
        assertTrue(blocked("client.p12"));
        assertTrue(blocked("bundle.pfx"));
        assertTrue(blocked("app.jks"));
        assertTrue(blocked("store.keystore"));
        assertTrue(blocked("private.ppk"));
        assertTrue(blocked("vault.kdbx"));
        // 大小写同样归一
        assertTrue(blocked("SERVER.KEY"));
    }

    @Test
    @DisplayName("凭证：凭据目录整体被拒（任意层级）")
    void blocksCredentialDirs() {
        assertTrue(blocked(".ssh/id_rsa"));
        assertTrue(blocked(".ssh/known_hosts"));
        assertTrue(blocked(".aws/credentials"));
        assertTrue(blocked(".gnupg/secring.gpg"));
        assertTrue(blocked(".kube/config"));
        assertTrue(blocked("home/user/.ssh/config"));
    }

    // ------------------------------------------------------------------ 提权路径

    @Test
    @DisplayName("★ 提权：shell 启动文件被拒（写一次 = 下次运行执行任意代码）")
    void blocksPrivilegeEscalationFiles() {
        assertTrue(blocked(".bashrc"));
        assertTrue(blocked(".zshrc"));
        assertTrue(blocked(".profile"));
        assertTrue(blocked(".bash_profile"));
        assertTrue(blocked(".gitconfig"));
        assertTrue(blocked(".ripgreprc"));
        // 任意层级（子目录里的同名文件同样危险，因为 cd 进去后就会生效）
        assertTrue(blocked("scripts/.bashrc"));
    }

    @Test
    @DisplayName("★ 提权：工作区顶层的 git 仓库伪装条目被拒（且仅在顶层）")
    void blocksBareRepoDisguiseAtRootOnly() {
        assertTrue(blocked("HEAD"));
        assertTrue(blocked("objects"));
        assertTrue(blocked("refs"));
        assertTrue(blocked("packed-refs"));
        assertTrue(blocked("head"));         // 归一后同样命中

        // 非顶层不拦 —— 子目录里出现 objects/ 很常见，拦了就是误伤
        assertNull(guard.reason(root.resolve("src/objects"), root));
        assertNull(guard.reason(root.resolve("data/refs"), root));
    }

    @Test
    @DisplayName("提权：智能体自身的配置目录被拒（改它 = 改写对自己生效的规则）")
    void blocksAgentConfigDirs() {
        assertTrue(blocked(".claude/settings.json"));
        assertTrue(blocked(".mcp.json"));
        assertTrue(blocked(".codebuddy/memory/MEMORY.md"));
        assertTrue(blocked("project/.cursor/rules.md"));
    }

    // ------------------------------------------------------------------ 配置

    @Test
    @DisplayName("配置：extra-deny 能追加保护（但默认项不受影响）")
    void extraDenyAdds() {
        ReflectionTestUtils.setField(guard, "extraDeny", List.of("application-prod.yml", "*.secret"));

        assertTrue(blocked("application-prod.yml"));
        assertTrue(blocked("config/application-prod.yml"));
        assertTrue(blocked("foo.secret"));
        // 默认项照旧
        assertTrue(blocked(".env"));
        // 未匹配的照旧放行
        assertNull(guard.reason(root.resolve("application.yml"), root));
    }

    @Test
    @DisplayName("配置：非法的 extra-deny 模式被忽略，不会让整层隔离失效")
    void invalidExtraDenyIsIgnored() {
        ReflectionTestUtils.setField(guard, "extraDeny", List.of("[invalid-glob", "ok.secret"));

        // 非法项跳过，合法项仍生效；默认项也仍生效
        assertTrue(blocked("ok.secret"));
        assertTrue(blocked(".env"));
    }

    @Test
    @DisplayName("配置：总开关关闭后全部放行（这是明确的取舍，不是默认行为）")
    void disabledAllowsEverything() {
        ReflectionTestUtils.setField(guard, "enabled", false);

        assertFalse(guard.enabled());
        assertNull(guard.reason(root.resolve(".env"), root));
        assertFalse(guard.isProtectedDirName(".ssh"));
    }

    @Test
    @DisplayName("目录名判定：供遍历时 SKIP_SUBTREE 用")
    void protectedDirNames() {
        assertTrue(guard.isProtectedDirName(".ssh"));
        assertTrue(guard.isProtectedDirName(".aws"));
        assertTrue(guard.isProtectedDirName(".claude"));
        assertTrue(guard.isProtectedDirName(".SSH"));   // 归一

        assertFalse(guard.isProtectedDirName("src"));
        assertFalse(guard.isProtectedDirName("node_modules"));   // 那个由 SKIP_DIRS 负责
    }

    @Test
    @DisplayName("拒绝消息要说明'不可豁免'，否则模型会反复换写法重试")
    void denyMessageExplainsNoExemption() {
        String reason = guard.reason(root.resolve(".env"), root);
        assertNotNull(reason);
        String msg = guard.denyMessage(".env", reason);

        assertTrue(msg.contains("无法豁免"));
        assertTrue(msg.contains("凭证隔离"));
        // 消息里不能回显文件内容（这里本来也没有内容，但确保措辞里不带"内容如下"之类）
        assertFalse(msg.contains("内容如下"));
    }
}
