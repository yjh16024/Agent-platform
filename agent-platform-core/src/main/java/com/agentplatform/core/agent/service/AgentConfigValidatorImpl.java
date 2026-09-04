package com.agentplatform.core.agent.service;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.ModelBinding;
import com.agentplatform.model.record.Persona;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置校验器实现（责任链）。
 * <p>
 * 内置校验规则：
 * <ol>
 *   <li>提示词长度限制</li>
 *   <li>参数合法域（temperature 0~2、warmth 0~1 等）</li>
 *   <li>模板变量 {{var}} 语法检查</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
public class AgentConfigValidatorImpl implements AgentConfigValidator {

    private static final int MAX_PROMPT_LENGTH = 100_000;
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_\\-\\.]+)\\s*}}");

    @Override
    public void validate(AgentDefinition def) {
        List<String> errors = new ArrayList<>();

        // 规则 1：提示词长度
        if (def.getSystemPrompt() == null || def.getSystemPrompt().isBlank()) {
            errors.add("system_prompt 不能为空");
        } else if (def.getSystemPrompt().length() > MAX_PROMPT_LENGTH) {
            errors.add("system_prompt 长度超限（> " + MAX_PROMPT_LENGTH + "）");
        }

        // 规则 2：生成参数合法域
        GenerationConfig gc = def.getGenerationConfig();
        if (gc != null) {
            if (gc.temperature() != null && (gc.temperature() < 0 || gc.temperature() > 2)) {
                errors.add("temperature 必须在 [0, 2] 区间");
            }
            if (gc.topP() != null && (gc.topP() < 0 || gc.topP() > 1)) {
                errors.add("top_p 必须在 [0, 1] 区间");
            }
            if (gc.maxTokens() != null && gc.maxTokens() < 1) {
                errors.add("max_tokens 必须 >= 1");
            }
        }

        // 规则 3：人格维度合法域
        Persona p = def.getPersona();
        if (p != null) {
            if (p.warmth() != null && (p.warmth() < 0 || p.warmth() > 1)) {
                errors.add("warmth 必须在 [0, 1] 区间");
            }
            if (p.expertise() != null && (p.expertise() < 0 || p.expertise() > 1)) {
                errors.add("expertise 必须在 [0, 1] 区间");
            }
            if (p.proactiveness() != null && (p.proactiveness() < 0 || p.proactiveness() > 1)) {
                errors.add("proactiveness 必须在 [0, 1] 区间");
            }
        }

        // 规则 3.5：模型绑定——选择了具体云服务商时必须填写 API Key（直连必填）
        ModelBinding mb = def.getModelBinding();
        if (mb != null && mb.isConfigured()) {
            String provider = mb.provider() == null ? "" : mb.provider().trim().toLowerCase();
            boolean cloud = !provider.isBlank() && !"local".equals(provider)
                    && !"mock".equals(provider) && !"auto".equals(provider);
            if (cloud && (mb.apiKey() == null || mb.apiKey().isBlank())) {
                errors.add("已选择服务商 " + mb.provider() + "，请填写 API Key");
            }
        }

        // 规则 4：模板变量语法（变变量可检查 {{var}} 是否成对）
        if (def.getSystemPrompt() != null) {
            Matcher m = TEMPLATE_VAR.matcher(def.getSystemPrompt());
            while (m.find()) {
                String name = m.group(1);
                if (name.isBlank()) {
                    errors.add("system_prompt 包含空变量占位符 {{}}");
                    break;
                }
            }
        }

        if (!errors.isEmpty()) {
            throw BizException.validation(String.join("; ", errors));
        }
    }

    @Override
    public void validateOrThrow(AgentDefinition def) {
        validate(def);
    }
}