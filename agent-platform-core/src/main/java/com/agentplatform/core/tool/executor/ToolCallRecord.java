package com.agentplatform.core.tool.executor;

import com.agentplatform.core.tool.ToolResult;
import tools.jackson.databind.JsonNode;

/**
 * 一次工具调用的记录 —— 供前端「工具调用可视化」展示。
 *
 * <h3>为什么需要它</h3>
 * 在此之前，工具调用只以两种形式存在：回灌给模型的 {@code role=tool} 消息，
 * 以及一行结构化日志（{@code tool.call name=... latency=...}）。
 * **用户侧完全看不到** —— agent 在后台 grep 了 200 个文件、读了 3 个、改写了 1 个，
 * 界面上只是"想了一会儿然后给出答案"。工具越多，这个盲区越大：
 * 用户无法判断它是查过了才回答、还是压根没查就编。
 *
 * <h3>没有"序号"字段（刻意）</h3>
 * 调用顺序就是收集顺序（工具循环是顺序执行的），前端按数组下标渲染即可。
 * 加一个由调用方填的 {@code seq} 只会多出一个"谁传错了"的失败模式，
 * 而它本来不存在。
 *
 * <h3>截断是这里做的，不是前端做的</h3>
 * {@code fs_write_file} 的 {@code content} 参数可能是整个文件（几万字符），
 * {@code fs_grep} 的输出可能是几百条命中。原样塞进响应会让一次对话的 payload
 * 膨胀几十倍，而且这些内容**对"看清楚发生了什么"几乎没有增量价值**。
 * 所以在上限处截断 —— 它同时也是**响应体积的唯一闸门**，
 * 因此放在 record 的紧凑构造器里（任何构造路径都绕不过去）。
 *
 * @param name      工具名
 * @param arguments 入参 JSON（已截断）
 * @param success   是否成功
 * @param output    结果文本（已截断）
 * @param error     错误信息（失败时，已截断）
 * @param latencyMs 耗时（毫秒）—— 让用户能看出"哪一步慢"
 */
public record ToolCallRecord(
        String name,
        String arguments,
        boolean success,
        String output,
        String error,
        long latencyMs
) {

    /** 入参保留上限：够看清"改哪个文件的哪一段"，又不至于把整个文件内容搬进响应。 */
    private static final int MAX_ARGS_CHARS = 2000;

    /** 结果保留上限：比入参略宽（结果才是用户最想看的），但仍要拦得住 grep 的大批量命中。 */
    private static final int MAX_OUTPUT_CHARS = 4000;

    private static final int MAX_ERROR_CHARS = 1000;

    /** 统一截断（任何构造路径都会经过）—— 见类注释"截断是这里做的"。 */
    public ToolCallRecord {
        arguments = truncate(arguments, MAX_ARGS_CHARS);
        output = truncate(output, MAX_OUTPUT_CHARS);
        error = truncate(error, MAX_ERROR_CHARS);
    }

    /**
     * 从一次实际执行构造记录。
     *
     * <p>把 {@code JsonNode → String} 的转换收在这里，避免调用方各写一遍
     * （两条工具循环都要构造它，重复的转换逻辑必然会出现不一致）。</p>
     */
    public static ToolCallRecord of(String toolName, JsonNode args,
                                    ToolResult result, long latencyMs) {
        String arguments = args == null ? "{}" : args.toString();
        String output = null;
        String error = null;
        boolean success = false;
        if (result != null) {
            success = result.success();
            output = textOf(result.output());
            error = result.error();
        }
        return new ToolCallRecord(toolName, arguments, success, output, error, latencyMs);
    }

    /** JsonNode → 文本：字符串节点取原文（否则会多出一层引号），其余走 JSON 序列化。 */
    private static String textOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…（已截断，原长 " + s.length() + " 字符）";
    }
}
