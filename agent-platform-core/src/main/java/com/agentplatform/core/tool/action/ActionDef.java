package com.agentplatform.core.tool.action;

import com.agentplatform.common.exception.BizException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个「项目动作」的定义：名字 + 说明 + 命令 + 受限参数。
 *
 * <h3>命令来自哪里（这是整条链路的安全前提）</h3>
 * {@code command} **只会**来自两个地方：平台内置常量，或用户在配置里写的模板。
 * 模型**永远不能**提供命令本身，它只能选择跑哪个动作、并给出少量参数值。
 *
 * <h3>★ 参数白名单是这里唯一的注入屏障</h3>
 * 命令在 Windows 上要经 {@code cmd.exe /c} 执行（否则找不到 {@code mvn.cmd} 这类批处理壳），
 * 而 {@code cmd} 会把 {@code & | > ; ^} 当控制符解析。命令模板是人写的、可信，
 * 但**参数值来自模型** —— 所以参数必须只允许"不含任何 shell 元字符"的字符集。
 *
 * <p>默认字符集 {@code [A-Za-z0-9._\-]+} 是**刻意收紧**的：不含空格、斜杠、引号、
 * 重定向与管道符。要放宽（比如允许路径带 {@code /}）就得自己写 {@code pattern}，
 * 并清楚那意味着把该风险接回自己手里。</p>
 */
public record ActionDef(
        String name,
        String description,
        List<String> command,
        List<Param> params,
        long timeoutSeconds
) {

    /** 默认的参数白名单：字母数字与 {@code . _ -}。 */
    public static final String DEFAULT_PATTERN = "[A-Za-z0-9._\\-]+";

    /** 默认超时（秒）：构建类动作用时通常远超普通工具调用。 */
    public static final long DEFAULT_TIMEOUT_SECONDS = 300L;

    /**
     * 一个动作参数。
     *
     * @param name        参数名（同时是命令模板里的占位符，写作 {@code {{name}}}）
     * @param description 给模型看的说明
     * @param required    是否必填
     * @param pattern     允许的字符集（正则，**整串匹配**）
     */
    public record Param(String name, String description, boolean required, String pattern) {

        public static Param required(String name, String description) {
            return new Param(name, description, true, DEFAULT_PATTERN);
        }

        public static Param optional(String name, String description) {
            return new Param(name, description, false, DEFAULT_PATTERN);
        }
    }

    /** 无参动作。 */
    public static ActionDef of(String name, String description, List<String> command) {
        return new ActionDef(name, description, command, List.of(), DEFAULT_TIMEOUT_SECONDS);
    }

    /** 带参动作。 */
    public static ActionDef of(String name, String description, List<String> command,
                               List<Param> params, long timeoutSeconds) {
        return new ActionDef(name, description, command, params,
                timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds);
    }

    public List<String> paramNames() {
        List<String> out = new ArrayList<>(params.size());
        params.forEach(p -> out.add(p.name()));
        return out;
    }

    /**
     * 渲染成真正要执行的命令：校验参数 → 替换占位符 → 丢掉空段。
     *
     * <p>"丢掉空段"解决的是这种写法：{@code ["git","diff","--","{{path}}"]} ——
     * 用户没给 {@code path} 时，"--" 后面挂一个空参数会让命令报错。
     * 含未提供值的占位符的元素整体丢弃，语义正好是"这个可选参数没给"。</p>
     *
     * @param values 模型给的参数值（可空）
     */
    public List<String> render(Map<String, String> values) {
        Map<String, String> safe = new HashMap<>();
        for (Param p : params) {
            String raw = values == null ? null : values.get(p.name());
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) {
                if (p.required()) {
                    throw BizException.badRequest("动作 " + name + " 缺少必需参数 " + p.name()
                            + "（" + p.description() + "）");
                }
                continue;
            }
            if (!value.matches(p.pattern())) {
                throw BizException.badRequest("参数 " + p.name() + " 含不被允许的字符：'"
                        + value + "'（允许的字符集：" + p.pattern() + "）。"
                        + "该限制用于防止经 shell 解析注入，请只传普通字符。");
            }
            safe.put(p.name(), value);
        }

        List<String> out = new ArrayList<>();
        for (String segment : command) {
            String rendered = segment;
            for (Map.Entry<String, String> e : safe.entrySet()) {
                rendered = rendered.replace("{{" + e.getKey() + "}}", e.getValue());
            }
            if (rendered.isBlank()) {
                continue;
            }
            if (rendered.contains("{{")) {
                // 还有没被替换的占位符（该参数未提供）→ 整段丢弃
                continue;
            }
            out.add(rendered);
        }
        if (out.isEmpty()) {
            throw BizException.internal("动作 " + name + " 渲染后没有可执行的命令（检查参数配置）");
        }
        return out;
    }
}
