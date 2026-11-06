package com.agentplatform.framework.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次运行为请求（框架唯一入口 {@link AgentRuntime#run} 的入参）。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>不可变</b>：builder 产出后不可修改，避免运行时把它当可变上下文传来传去</li>
 *   <li><b>显式身份</b>：{@code principal} 而不是从 ThreadLocal / 请求头隐式取（B3）</li>
 *   <li><b>无平台表假设</b>：不出现 message、agent_def、租户表等概念；只有 id 与 metadata</li>
 * </ul>
 */
public record RunCommand(
        String agentId,
        List<InputPart> input,
        String sessionId,
        String tenantId,
        Principal principal,
        String model,
        ModelCredentials credentials,
        String systemPrompt,
        ToolOptions tools,
        Map<String, Object> extra,
        Map<String, Object> metadata) {

    public RunCommand {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 必填：框架不提供默认智能体");
        }
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("input 至少包含一个内容块");
        }
        input = List.copyOf(input);
        tenantId = (tenantId == null || tenantId.isBlank()) ? Principal.DEFAULT_TENANT : tenantId;
        tools = (tools == null) ? ToolOptions.none() : tools;
        credentials = (credentials == null) ? ModelCredentials.none() : credentials;
        extra = (extra == null) ? Map.of() : Map.copyOf(extra);
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
    }

    /** 纯文本快路：等价于 {@code builder(agentId).text(text).build()}。 */
    public static RunCommand ofText(String agentId, String text) {
        return builder(agentId).text(text).build();
    }

    public static Builder builder(String agentId) {
        return new Builder(agentId);
    }

    /** 拼接全部 {@link InputPart.Text}；{@link InputPart.Ref} 不参与，需宿主自行解析。 */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (InputPart part : input) {
            if (part instanceof InputPart.Text t) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    public List<InputPart.Ref> refs() {
        List<InputPart.Ref> refs = new ArrayList<>();
        for (InputPart part : input) {
            if (part instanceof InputPart.Ref r) {
                refs.add(r);
            }
        }
        return List.copyOf(refs);
    }

    public String meta(String key) {
        Object v = metadata.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static final class Builder {

        private final String agentId;
        private final List<InputPart> input = new ArrayList<>();
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private String sessionId;
        private String tenantId;
        private Principal principal;
        private String model;
        private Map<String, Object> extra = Map.of();
        private String systemPrompt;
        private ModelCredentials credentials = ModelCredentials.none();
        private ToolOptions tools;

        private Builder(String agentId) {
            this.agentId = agentId;
        }

        public Builder text(String text) {
            if (text != null && !text.isBlank()) {
                this.input.add(new InputPart.Text(text));
            }
            return this;
        }

        public Builder part(InputPart part) {
            if (part != null) {
                this.input.add(part);
            }
            return this;
        }

        public Builder parts(List<InputPart> parts) {
            if (parts != null) {
                parts.forEach(this::part);
            }
            return this;
        }

        public Builder session(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder tenant(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** 本轮系统提示词覆盖；null 表示用运行时默认值（平台按人格/模板/Skill 组装后传入）。 */
        /** 每请求凭证（平台由 ModelBindingService 解析后传入）。 */
        public Builder credentials(ModelCredentials credentials) {
            this.credentials = credentials;
            return this;
        }

        /** 适配器专用附加数据（如多模态图片 `images`），框架只做透传。 */
        public Builder extra(Map<String, Object> extra) {
            this.extra = extra;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder tools(ToolOptions tools) {
            this.tools = tools;
            return this;
        }

        public Builder meta(String key, Object value) {
            if (key != null) {
                this.metadata.put(key, value);
            }
            return this;
        }

        public RunCommand build() {
            return new RunCommand(agentId, input, sessionId, tenantId, principal, model, credentials,
                    systemPrompt, tools, extra, metadata);
        }
    }
}
