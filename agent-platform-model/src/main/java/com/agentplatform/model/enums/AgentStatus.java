package com.agentplatform.model.enums;

/**
 * 智能体生命周期状态机。
 * <p>流转：{@code draft → published → deprecated → archived}</p>
 */
public enum AgentStatus {
    /** 草稿（可编辑，尚未发布） */
    draft,
    /** 已发布（运行中可被引用） */
    published,
    /** 已弃用（停止新会话，存量会话继续） */
    deprecated,
    /** 已归档（软删，保留历史版本快照） */
    archived
}