/**
 * 白雾框架契约层（framework-api）。
 *
 * <p><b>硬约束</b>（由 CI 的 ArchUnit 与依赖树门禁强制，见 {@code docs/framework-plan.md} §2.4 / §5.1）：
 * <ul>
 *   <li>不得出现 {@code org.springframework.*}、{@code jakarta.persistence.*}、{@code org.springframework.ai.*} 的 import</li>
 *   <li>不得使用 JDK 预览特性（父 pom 已不再开启；本模块仍显式清空编译参数，作为防止回流的护栏）</li>
 *   <li>只表达「平台无关」的语义：不出现 message 表、插件、皮肤、配额表等平台概念</li>
 * </ul>
 *
 * <p><b>P-1 交付范围</b>：{@link com.agentplatform.framework.api.RunCommand}、
 * {@link com.agentplatform.framework.api.RunEvent}、
 * {@link com.agentplatform.framework.api.RunResult}、
 * {@link com.agentplatform.framework.api.AgentRuntime}、
 * {@link com.agentplatform.framework.api.Principal}、
 * {@link com.agentplatform.framework.api.InputPart}、
 * {@link com.agentplatform.framework.api.ToolOptions}。
 *
 * <p>{@code Tool} / {@code ModelAdapter} 从 {@code agent-platform-core} 迁入属 P0' 的机械改造
 * （改 import + 去掉对 core 内部类型的引用），不在本阶段重复定义，避免出现两套同名契约。
 */
package com.agentplatform.framework.api;
