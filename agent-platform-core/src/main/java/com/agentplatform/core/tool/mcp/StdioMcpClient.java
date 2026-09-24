package com.agentplatform.core.tool.mcp;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 MCP 客户端（**stdio 传输**）—— 通过子进程的 stdin/stdout 走 JSON-RPC。
 *
 * <h3>它解锁什么</h3>
 * 在此之前平台只有 streamable-http，于是**官方 filesystem / git 这类靠 {@code npx} /
 * {@code uvx} 拉起的 server 完全挂不上** —— 也就是整个 npm/uvx MCP 生态用不了。
 * 补上 stdio 是打开这个生态的最短路径。
 *
 * <h3>与 HTTP 实现的本质差别：一个长驻进程，而不是一次请求</h3>
 * HTTP 版每次调用发一个请求、拿到响应、结束，**无状态**；stdio 版要**先拉起子进程并维持**，
 * 之后所有交互都在这条通道上，响应还可能**交错**（服务器可以穿插发通知）。
 * 所以这里必须自己做三件事：
 * <ol>
 *   <li><b>请求-响应配对</b>：每条请求带自增 id，用一个 {@code id → CompletableFuture} 的表
 *       挂起等待，读线程按 id 唤醒对应等待者（不能"发一条读一条" —— 交错时会对错）；</li>
 *   <li><b>进程生命周期</b>：惰性启动一次、复用、关闭时按规范顺序收敛（见 {@link #close()}）；</li>
 *   <li><b>异常收敛</b>：子进程一旦退出，**所有在等的请求必须立刻失败** ——
 *       否则每个调用都要白等到超时才报错（见 {@link #failAllPending}）。</li>
 * </ol>
 *
 * <h3>⚠️ 安全代价（必须知道）</h3>
 * stdio 的本质是"**平台替你执行一条本地命令**"，而且这条命令通常还会去下载代码
 * （{@code npx -y <包>} / {@code uvx <包>}）。命令白名单能挡住"换了别的可执行文件"，
 * 但**挡不住参数** —— {@code npx -y 任意包} 一样会执行那个包里的代码。
 * 所以：
 * <ul>
 *   <li>注册入口必须是**管理员级权限**的操作，不能让模型自己发起；</li>
 *   <li>默认白名单只给最常见的几个启动器，且可通过配置收紧；</li>
 *   <li>这条链路与 {@code shell.run} 的风险等级相同 —— 区别只在于"命令由人配、不由模型生成"。</li>
 * </ul>
 *
 * <p>协议依据：MCP 规范 2025-06-18（传输与生命周期两节）—— 消息为**换行分隔的 UTF-8
 * JSON-RPC** 且不得含内嵌换行；stdout 是专属协议通道（服务器不得往里写日志，日志走 stderr）；
 * 初始化后客户端**必须**发送 {@code notifications/initialized}。</p>
 */
@Slf4j
public class StdioMcpClient implements McpClient {

    /** 协议版本（与 HTTP 实现保持一致，便于同一份 server 同时挂两种传输）。 */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private static final long DEFAULT_TIMEOUT_MS = 60_000L;

    /** 单行响应长度上限：防止 server 往 stdout 里吐一坨非协议内容把内存吃光。 */
    private static final int MAX_LINE_CHARS = 4 * 1024 * 1024;

    private final List<String> command;
    private final Map<String, String> extraEnv;
    private final Path workingDir;
    private final long timeoutMs;

    private final Object startLock = new Object();
    private final AtomicLong idSeq = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private volatile Process process;
    private volatile BufferedWriter toServer;
    private volatile boolean started;
    private volatile boolean closed;
    /** 握手得到的 server 名称（仅用于日志与展示）。 */
    private volatile String serverName = "";

    public StdioMcpClient(List<String> command, Map<String, String> env, Path workingDir, Long timeoutMs) {
        if (command == null || command.isEmpty()) {
            throw BizException.badRequest("MCP stdio 需要提供启动命令（command）");
        }
        this.command = List.copyOf(command);
        this.extraEnv = env == null ? Map.of() : Map.copyOf(env);
        this.workingDir = workingDir;
        this.timeoutMs = timeoutMs == null || timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
    }

    @Override
    public String transport() {
        return "stdio";
    }

    @Override
    public List<McpToolSpec> listTools() {
        ensureStarted();
        JsonNode result = call("tools/list", Map.of());
        List<McpToolSpec> tools = new ArrayList<>();
        JsonNode arr = result.path("tools");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                JsonNode schema = t.has("inputSchema") ? t.get("inputSchema") : t.get("input_schema");
                if (schema != null && schema.isNull()) {
                    schema = null;
                }
                tools.add(new McpToolSpec(
                        t.path("name").asText(),
                        t.path("description").asText(""),
                        schema));
            }
        }
        return tools;
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        ensureStarted();
        JsonNode result = call("tools/call",
                Map.of("name", toolName, "arguments", arguments == null ? Map.of() : arguments));

        StringBuilder sb = new StringBuilder();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
        }
        String text = sb.toString();
        if (result.path("isError").asBoolean(false)) {
            // 与 HTTP 实现保持一致：工具级失败作为异常抛出，由上游转成 ToolResult.fail
            throw BizException.internal("MCP 工具 " + toolName + " 执行失败: "
                    + (text.isEmpty() ? result.toString() : text));
        }
        return text;
    }

    /** 展示名（server 自报 + 启动命令）。 */
    public String label() {
        return serverName.isBlank() ? String.join(" ", command) : serverName;
    }

    /**
     * 子进程是否还在运行（诊断与测试用）。
     *
     * <p>存在的理由很实际：stdio 的失败模式之一是**孤儿进程** ——
     * 连接"看起来关了"但 server 还挂在后台。只有能直接问"它还活着吗"，
     * 才能把这件事写成断言（而不是间接猜测）。</p>
     */
    public boolean processAlive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 惰性启动子进程并完成握手。
     *
     * <p>启动与握手都在锁内：两个工具同时首次调用时，不能拉起两个进程。</p>
     */
    private void ensureStarted() {
        if (started && !closed) {
            return;
        }
        synchronized (startLock) {
            if (started && !closed) {
                return;
            }
            if (closed) {
                throw BizException.internal("MCP stdio 连接已关闭，无法继续调用");
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                if (workingDir != null) {
                    pb.directory(workingDir.toFile());
                }
                // stderr 单独收：规范规定 stdout 是**专属协议通道**，
                // 服务器往 stdout 写日志就是它的 bug；而我们自己的诊断信息只能走 stderr。
                pb.redirectErrorStream(false);
                pb.environment().putAll(extraEnv);

                Process p = pb.start();
                this.process = p;
                this.toServer = new BufferedWriter(
                        new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));

                // 读线程用虚拟线程：它们主要是阻塞在 readLine 上，不占平台线程
                Thread.ofVirtual().name("mcp-stdio-out").start(() -> readLoop(p));
                Thread.ofVirtual().name("mcp-stdio-err").start(() -> errLoop(p));

                started = true;
                handshake();
            } catch (IOException e) {
                started = false;
                failAllPending("MCP stdio 子进程启动失败");
                throw BizException.internal("MCP stdio 子进程启动失败（命令：" + String.join(" ", command)
                        + "）：" + e.getMessage(), e);
            }
        }
    }

    /**
     * 初始化握手（规范要求：initialize 必须是**第一次**交互）。
     *
     * <p>⚠️ 收到 {@code InitializeResult} 之后**必须**再发一条
     * {@code notifications/initialized} 通知 —— 服务器在收到它之前不应该接受正常操作。
     * 现有的 {@code HttpMcpClient} 漏了这一步（HTTP 场景服务端多半容忍），
     * 但 stdio 下的实现更严格，不能省。</p>
     */
    private void handshake() {
        JsonNode result = call("initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agent-platform", "version", "1.0.0")));
        serverName = result.path("serverInfo").path("name").asText("");
        String negotiated = result.path("protocolVersion").asText("");
        notify("notifications/initialized", null);
        log.info("[mcp-stdio] 已连接 {}（协议 {}，命令：{}）",
                serverName.isBlank() ? "(未命名)" : serverName, negotiated, String.join(" ", command));
    }

    /**
     * 关闭连接（按规范的 stdio 收敛顺序：关输入流 → 等退出 → SIGTERM → SIGKILL）。
     *
     * <p>必须把"等进程自己退出"放在强杀之前：正常退出能让 server 自己清理临时状态；
     * 直接 destroy 可能留下半截文件或孤儿进程。</p>
     */
    @Override
    public void close() {
        synchronized (startLock) {
            if (closed) {
                return;
            }
            closed = true;
            started = false;

            BufferedWriter w = toServer;
            if (w != null) {
                try {
                    w.close();   // ① 关输入流：这是告诉 server"可以退出了"的标准信号
                } catch (IOException ignored) {
                    // 进程可能已经死了，关流失败无影响
                }
            }
            Process p = process;
            if (p != null) {
                try {
                    if (!p.waitFor(3, TimeUnit.SECONDS)) {
                        p.destroy();                                   // ② SIGTERM
                        if (!p.waitFor(2, TimeUnit.SECONDS)) {
                            p.destroyForcibly();                       // ③ SIGKILL
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    p.destroyForcibly();
                } catch (Exception e) {
                    log.debug("[mcp-stdio] 关闭子进程时出错（忽略）：{}", e.getMessage());
                }
            }
            failAllPending("MCP stdio 连接已关闭");
            log.info("[mcp-stdio] 已关闭 {}", label());
        }
    }

    // ------------------------------------------------------------------ JSON-RPC

    /**
     * 发起一次请求并等待响应。
     *
     * <p>等待用 {@code CompletableFuture} 而不是"写一行读一行"：服务器的响应**可能交错**
     * （中间穿插 logging / progress 通知），按 id 配对才是对的。</p>
     */
    private JsonNode call(String method, Map<String, Object> params) {
        long id = idSeq.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        payload.put("params", params == null ? Map.of() : params);

        try {
            writeLine(JsonUtils.toJson(payload));
        } catch (Exception e) {
            pending.remove(id);
            throw BizException.internal("MCP stdio 写入失败（" + method + "）：" + e.getMessage());
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            // 规范：请求超时应发送取消通知，而不是静默丢弃
            notify("notifications/cancelled", Map.of("requestId", id, "reason", "timeout"));
            throw BizException.internal("MCP stdio 调用超时（" + timeoutMs + "ms）：" + method
                    + "（已发送取消通知）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw BizException.internal("MCP stdio 调用被中断：" + method);
        } catch (ExecutionException e) {
            pending.remove(id);
            Throwable cause = e.getCause();
            String msg = cause == null ? "未知错误" : cause.getMessage();
            throw BizException.internal("MCP stdio 调用失败（" + method + "）：" + msg);
        }
    }

    /** 发送一条**通知**（无 id、不等响应）。 */
    private void notify(String method, Map<String, Object> params) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("method", method);
            if (params != null) {
                payload.put("params", params);
            }
            writeLine(JsonUtils.toJson(payload));
        } catch (Exception e) {
            // 通知失败不该影响主流程（例如连接刚断时发取消通知）
            log.debug("[mcp-stdio] 发送通知 {} 失败（忽略）：{}", method, e.getMessage());
        }
    }

    /** 写一行 JSON（换行分隔是协议要求，且消息内不得含换行 —— 序列化结果天然满足）。 */
    private void writeLine(String json) throws IOException {
        BufferedWriter w = toServer;
        if (w == null) {
            throw new IOException("输出流不可用");
        }
        synchronized (this) {
            w.write(json);
            w.newLine();
            w.flush();   // 必须立刻 flush：server 在等这一行，缓冲住就是死锁
        }
    }

    /**
     * 读 stdout（**协议通道**）。
     *
     * <p>线程结束时（对端关闭或进程退出）把还在等的请求全部失败掉 —— 见
     * {@link #failAllPending}。</p>
     */
    private void readLoop(Process p) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                dispatch(line);
            }
        } catch (IOException e) {
            log.debug("[mcp-stdio] stdout 读取结束：{}", e.getMessage());
        } catch (Exception e) {
            log.warn("[mcp-stdio] stdout 读取异常：{}", e.getMessage());
        } finally {
            failAllPending("MCP stdio 子进程已退出或关闭了输出流");
        }
    }

    /** 分派一行消息：有 id 的当响应，其余当通知。 */
    private void dispatch(String line) {
        String t = line == null ? "" : line.trim();
        if (t.isEmpty()) {
            return;
        }
        if (t.length() > MAX_LINE_CHARS) {
            log.warn("[mcp-stdio] 忽略超长输出行（{} 字符）—— server 可能往 stdout 写了非协议内容", t.length());
            return;
        }
        JsonNode node;
        try {
            node = JsonUtils.toJsonNode(t);
        } catch (Exception e) {
            // 规范禁止 server 往 stdout 写日志；真写了就只能是忽略，但要留痕（否则是"莫名丢响应"）
            log.debug("[mcp-stdio] 忽略非 JSON 的 stdout 输出：{}", brief(t));
            return;
        }
        JsonNode idNode = node.get("id");
        if (idNode == null || idNode.isNull()) {
            // 通知（logging / progress / 服务器主动请求）—— 当前不需要处理，留 debug 便于排查
            log.debug("[mcp-stdio] 收到通知：{}", node.path("method").asText("?"));
            return;
        }
        long id = idNode.asLong();
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            log.debug("[mcp-stdio] 收到无人等待的响应 id={}（可能已超时）", id);
            return;
        }
        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(new IllegalStateException(
                    error.path("message").asText(error.toString())));
            return;
        }
        future.complete(node.path("result"));
    }

    /** 读 stderr（**日志通道**）。 */
    private void errLoop(Process p) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    log.debug("[mcp-stdio:{}] {}", serverName.isBlank() ? "err" : serverName, line);
                }
            }
        } catch (Exception e) {
            log.debug("[mcp-stdio] stderr 读取结束：{}", e.getMessage());
        }
    }

    /**
     * 把还在等待的请求全部失败掉。
     *
     * <p>不做这一步的代价很具体：子进程一死，所有在等的调用都要各自**干等到超时**才报错
     * （默认 60 秒），而且报的是"超时"这种误导性的原因 —— 真正的原因是进程没了。</p>
     */
    private void failAllPending(String reason) {
        if (pending.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>(pending.keySet());
        for (Long id : ids) {
            CompletableFuture<JsonNode> f = pending.remove(id);
            if (f != null) {
                f.completeExceptionally(new IllegalStateException(reason));
            }
        }
        log.debug("[mcp-stdio] 已失败 {} 个等待中的请求：{}", ids.size(), reason);
    }

    private static String brief(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
