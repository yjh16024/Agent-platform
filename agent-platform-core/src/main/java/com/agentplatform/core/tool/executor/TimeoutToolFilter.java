package com.agentplatform.core.tool.executor;

import com.agentplatform.common.util.TraceContext;
import com.agentplatform.core.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 统一超时过滤器（责任链最内一环，紧贴工具执行）—— 工具执行的**兜底超时**。
 *
 * <h3>为什么需要"兜底"而不是"精确控制"</h3>
 * 各工具其实都有自己的合理超时，而且**只有它们自己知道该设多少**：
 * 沙箱脚本 30 秒（`SandboxMcpClient`）、stdio MCP 请求 60 秒、HTTP 由 OkHttp 决定。
 * 那些是**业务超时**，不该被一个全局值粗暴覆盖。
 *
 * <p>缺的是另一种东西：**没有任何一环保证"这个调用最终会返回"**。
 * 一个第三方 MCP server 卡住、一个工具忘了设超时、一次阻塞读永远等不到数据 ——
 * 表现都是"对话停在那里不动了"，而且没有统一的观察点。这一环就是那道保险：
 * 到了上限就放弃等待，让模型能拿到结果继续往下走。</p>
 *
 * <h3>⚠️ 超时 = 放弃等待，不等于终止执行</h3>
 * {@code Future.cancel(true)} 会 interrupt，但**不响应中断的阻塞调用照样跑下去**
 * （{@code Process.waitFor}、Socket 读、某些 JNI 调用）。真正的强杀能力在各工具自己手里
 * （如沙箱子进程用 {@code destroyForcibly}）。所以这里的语义必须说清楚：
 * <ul>
 *   <li>它保证的是"**调用方不会无限期挂住**"，不是"副作用不会发生"；</li>
 *   <li>因此拒绝消息里明确写了**不要立即重试** —— 那可能造成重复写入 / 重复请求。</li>
 * </ul>
 *
 * <h3>为什么默认值是 600 秒（看着很大）</h3>
 * 因为**写类工具的"就地确认"会阻塞等待用户点确认，默认最多 5 分钟**。
 * 兜底超时若小于它，就会把正在等用户点按钮的调用判死 —— 那等于把刚做好的
 * "点一下就能继续"的体验破坏掉。所以默认值必须给审批留足余量，
 * 它的职责是拦住"无限卡住"，而不是压缩正常的等待。
 *
 * <h3>为什么用虚拟线程执行后续链</h3>
 * 因为需要"在另一个执行体上跑、在主线程限时等"这一件事。虚拟线程按需创建、
 * 阻塞时开销极小，正好适合（工具执行绝大多数时间都花在等待上）。
 * <b>同时必须重新绑定 {@code TraceContext}</b>：ScopedValue 不随新线程继承
 * （区别于 ThreadLocal 的 inheritable），不重绑的话工具内部的日志会各自生成随机 traceId，
 * 一次运行的日志就被打散了。
 */
@Slf4j
@Component
@Order(50)
public class TimeoutToolFilter implements ToolFilter {

    /** 超时后的固定提示（见类注释"放弃等待不等于终止执行"）。 */
    private static final String ADVICE =
            "注意：超时只是停止等待，该操作可能仍在后台继续，**不要立即重复调用同一个工具**"
                    + "（可能造成重复写入或重复请求）；如需重试，请先确认上一次的结果。";

    /**
     * 全局兜底超时（秒）。{@code <= 0} 表示关闭这一环。
     *
     * <p>默认 600 秒，理由见类注释（要给审批的就地确认留出 5 分钟）。</p>
     */
    @Value("${agent-platform.tool.timeout.default-seconds:600}")
    private long defaultSeconds = 600L;

    /**
     * 按工具覆盖超时，格式 {@code 工具名=秒,工具名=秒}（如 {@code fs_write_file=900}）。
     *
     * <p>用于"某些工具确实需要更长/更短"的特例，而不必调大全局值
     * （调大全局值等于对所有工具都放松）。</p>
     */
    @Value("${agent-platform.tool.timeout.overrides:}")
    private String overridesRaw = "";

    private volatile Map<String, Long> overrides;

    @Override
    public ToolResult doFilter(ToolInvocation invocation, ToolChain chain) {
        long timeoutMs = timeoutMillis(invocation.toolName());
        if (timeoutMs <= 0) {
            return chain.apply(invocation);
        }

        String toolName = invocation.toolName();
        // 先取上下文：ScopedValue 要在新线程里重新绑定（见类注释）
        String traceId = TraceContext.traceId();
        String runId = TraceContext.runId();
        String tenantId = invocation.ctx() == null ? null : invocation.ctx().tenantId();

        FutureTask<ToolResult> task = new FutureTask<>(() -> chain.apply(invocation));
        Thread.ofVirtual()
                .name("tool-timeout-" + toolName)
                .start(() -> TraceContext.withContext(traceId, runId, tenantId, task));

        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            log.warn("[tool] 执行超时（{} ms，已放弃等待）：{}", timeoutMs, toolName);
            return ToolResult.fail("工具 " + toolName + " 执行超时（" + (timeoutMs / 1000) + " 秒），已放弃等待。"
                    + ADVICE);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return ToolResult.fail("工具 " + toolName + " 的等待被中断。");
        } catch (ExecutionException e) {
            // 链内异常本应已被 ToolChain 收敛成 fail；真漏到这里也转成不动摇的结果
            Throwable cause = e.getCause();
            String msg = cause == null || cause.getMessage() == null ? "未知错误" : cause.getMessage();
            return ToolResult.fail("工具 " + toolName + " 执行失败：" + msg);
        }
    }

    /** 该工具的超时毫秒数；{@code <= 0} 表示不启用。 */
    private long timeoutMillis(String toolName) {
        Map<String, Long> map = overrides;
        if (map == null) {
            synchronized (this) {
                if (overrides == null) {
                    overrides = parseOverrides(overridesRaw);
                }
                map = overrides;
            }
        }
        Long specific = map.get(toolName);
        long seconds = specific != null ? specific : defaultSeconds;
        return seconds <= 0 ? 0 : seconds * 1000L;
    }

    /** 解析 {@code 名称=秒,名称=秒}；非法项跳过并留痕（不要因一个笔误让整环失效）。 */
    static Map<String, Long> parseOverrides(String raw) {
        Map<String, Long> out = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String item : raw.split(",")) {
            String t = item.trim();
            if (t.isEmpty()) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq <= 0 || eq == t.length() - 1) {
                log.warn("[tool] 忽略非法的超时覆盖项「{}」（应为 工具名=秒）", t);
                continue;
            }
            String name = t.substring(0, eq).trim();
            try {
                out.put(name, Long.parseLong(t.substring(eq + 1).trim()));
            } catch (NumberFormatException e) {
                log.warn("[tool] 忽略非法的超时秒数「{}」", t);
            }
        }
        return out;
    }
}
