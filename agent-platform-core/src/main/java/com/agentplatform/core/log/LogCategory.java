package com.agentplatform.core.log;

import com.agentplatform.framework.api.Tool;

/**
 * 日志类别（采集来源）。
 */
public enum LogCategory {
    agent, llm, plugin, skill, tool, api, workflow, system
}
