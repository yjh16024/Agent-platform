package com.agentplatform.core.tool.mcp;

import com.agentplatform.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * stdio 传输的单元测试（2026-09-24）。
 *
 * <p>重点有两块：**命令白名单的归一化**（不归一就等于没白名单 —— 见
 * {@code McpClientFactory.allowedStdioCommand} 的注释），以及**对着真实子进程的端到端**。
 * 后者刻意用真的 {@code node} 跑一个假 server（{@code fake-mcp-server.js}），
 * 而不是 mock 一个"会说话的流"：stdio 的正确性恰恰在于换行分隔、请求-响应配对、
 * 握手顺序、关流退出这些**与真实进程交互**的细节里，mock 掉它们等于只验证了我们自己的假设。</p>
 */
class StdioMcpClientTest {

    // ------------------------------------------------------------------ 白名单

    @Test
    @DisplayName("★ 白名单：剥掉目录与扩展名后比对（npx / npx.cmd / 全路径都算同一个命令）")
    void whitelistNormalizesPathAndExtension() {
        McpClientFactory factory = new McpClientFactory(Path.of("."), Path.of("."));

        // 三种写法都必须是同一个命令 —— 否则 Windows 用户会一个都匹配不上，
        // 而"配了白名单却全拒"的下一步就是有人把白名单清空
        assertDoesNotThrow(() -> factory.createStdio(List.of("npx", "-y", "pkg"), Map.of(), null, null));
        assertDoesNotThrow(() -> factory.createStdio(List.of("npx.cmd", "-y", "pkg"), Map.of(), null, null));
        assertDoesNotThrow(() -> factory.createStdio(
                List.of("C:\\Program Files\\nodejs\\npx.cmd", "-y", "pkg"), Map.of(), null, null));
        assertDoesNotThrow(() -> factory.createStdio(List.of("/usr/local/bin/node", "srv.js"), Map.of(), null, null));
    }

    @Test
    @DisplayName("★ 白名单：不在名单内的可执行文件被拒（含空 / null 命令）")
    void whitelistRejectsOthers() {
        McpClientFactory factory = new McpClientFactory(Path.of("."), Path.of("."));

        assertThrows(BizException.class, () -> factory.createStdio(List.of("curl", "evil.sh"), Map.of(), null, null));
        assertThrows(BizException.class, () -> factory.createStdio(List.of("bash", "-c", "x"), Map.of(), null, null));
        assertThrows(BizException.class, () -> factory.createStdio(List.of(), Map.of(), null, null));
        assertThrows(BizException.class, () -> factory.createStdio(null, Map.of(), null, null));
    }

    @Test
    @DisplayName("工厂：stdio 是受支持的端点类型，且没有命令时拒绝")
    void factorySupportsStdio() {
        McpClientFactory factory = new McpClientFactory(Path.of("."), Path.of("."));

        assertTrue(factory.supports("stdio"));
        assertTrue(McpClientFactory.SUPPORTED_TYPES.contains("stdio"));
        // 走端点描述这条路（create(McpEndpoint)）也要能到 stdio 分支
        assertThrows(BizException.class, () -> factory.create(
                McpClientFactory.McpEndpoint.stdio(null, Map.of(), null, null)));
    }

    // ------------------------------------------------------------------ 生命周期

    @Test
    @DisplayName("close 幂等；从未启动就 close 不抛异常")
    void closeIsIdempotent() {
        StdioMcpClient client = new StdioMcpClient(List.of("node", "-e", ""), Map.of(), null, 1000L);

        assertDoesNotThrow(client::close);
        assertDoesNotThrow(client::close);
    }

    @Test
    @DisplayName("★ 启动失败要立刻抛出可读异常（而不是干等到超时）")
    void startFailureIsImmediate() {
        StdioMcpClient client = new StdioMcpClient(
                List.of("definitely-not-a-real-command-xyz"), Map.of(), null, 30_000L);
        try {
            BizException e = assertThrows(BizException.class, client::listTools);
            assertTrue(e.getMessage().contains("启动失败"), "异常应说明是启动失败：" + e.getMessage());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("关闭后继续调用：给出明确原因，而不是抛奇怪的 NPE")
    void callAfterCloseIsClear() {
        StdioMcpClient client = new StdioMcpClient(List.of("node", "-e", ""), Map.of(), null, 1000L);
        client.close();

        BizException e = assertThrows(BizException.class, client::listTools);
        assertTrue(e.getMessage().contains("已关闭"), "异常应说明连接已关闭：" + e.getMessage());
    }

    // ------------------------------------------------------------------ 端到端

    @Test
    @DisplayName("★ 端到端：拉起真实子进程 → 握手 → 发现工具 → 调用 → 关闭（缺 node 则跳过）")
    void endToEndAgainstRealProcess() throws Exception {
        assumeTrue(nodeAvailable(), "当前环境没有可用的 node，跳过 stdio 端到端测试");
        URL res = getClass().getResource("/fake-mcp-server.js");
        assumeTrue(res != null, "缺少测试资源 fake-mcp-server.js");
        Path server = Path.of(res.toURI());
        assumeTrue(Files.isRegularFile(server), "假 server 脚本不是普通文件：" + server);

        StdioMcpClient client = new StdioMcpClient(
                List.of("node", server.toString()), Map.of(), null, 30_000L);
        try {
            // ① 握手 + 发现（内部会依次发 initialize → notifications/initialized → tools/list）
            List<McpToolSpec> tools = client.listTools();
            assertEquals(2, tools.size(), "应发现 2 个工具");
            assertEquals("echo", tools.get(0).name());
            assertEquals("boom", tools.get(1).name());

            // ② 调用：参数应被完整透传（含中文）
            assertEquals("echo:你好", client.callTool("echo", Map.of("text", "你好")));

            // ③ 工具级失败：isError=true 应转成异常（与 HTTP 实现的行为一致）
            assertThrows(BizException.class, () -> client.callTool("boom", Map.of()));

            // ④ 连续调用可用（证明长驻进程被复用，而不是每次重启）
            assertEquals("echo:again", client.callTool("echo", Map.of("text", "again")));
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("★ 关闭后子进程真的退出（不留孤儿进程）")
    void closeTerminatesChildProcess() throws Exception {
        assumeTrue(nodeAvailable(), "当前环境没有可用的 node，跳过");
        URL res = getClass().getResource("/fake-mcp-server.js");
        assumeTrue(res != null, "缺少测试资源");
        Path server = Path.of(res.toURI());

        StdioMcpClient client = new StdioMcpClient(
                List.of("node", server.toString()), Map.of(), null, 30_000L);
        client.listTools();          // 确保进程真的起来了
        assertTrue(client.processAlive(), "启动后子进程应当存活");

        client.close();

        // close() 的第一步是关输入流；假 server 收到 EOF 后 exit(0)，
        // 所以 close() 返回时进程就应该已经没了。若这里为真 → 后台会残留 node 进程。
        assertFalse(client.processAlive(), "close() 后子进程不应还活着（孤儿进程）");
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
