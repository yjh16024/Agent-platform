package com.agentplatform.model.enums;

/**
 * 解决建议类型。
 */
public enum SolutionType {
    /** 自动修复（提供可执行的自动修复命令/API） */
    AUTO_FIX,
    /** 人工操作（给出人类可读步骤） */
    MANUAL,
    /** 文档链接（跳转到相关文档） */
    DOC_LINK
}