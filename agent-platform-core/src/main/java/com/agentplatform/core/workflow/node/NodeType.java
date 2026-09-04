package com.agentplatform.core.workflow.node;

/**
 * 工作流节点类型（对应 §3.2 节点 Schema 的 type 枚举）。
 */
public enum NodeType {
    Start,
    End,
    LLM,
    KnowledgeBase,
    Function,
    Tool,
    Skill,
    Plugin,
    Agent,
    Condition,
    Parallel,
    Loop,
    Http,
    Transform
}