package com.agentplatform.core.agent.service;

import com.agentplatform.model.entity.AgentDefinition;

/**
 * 配置校验责任链（责任链模式）。
 * <p>
 * 保存前逐条校验：提示词长度、参数合法域、模板变量可解析、
 * 模型/工具/知识库/插件存在性与权限、租户配额策略。
 * 各校验规则以独立 Bean 实现，Spring 自动收集为责任链。
 * </p>
 */
public interface AgentConfigValidator {

    /**
     * 执行校验，违规时抛出 {@link com.agentplatform.common.exception.BizException}。
     */
    void validate(AgentDefinition def);

    /**
     * 校验并抛异常（组合调用）。
     */
    void validateOrThrow(AgentDefinition def);
}