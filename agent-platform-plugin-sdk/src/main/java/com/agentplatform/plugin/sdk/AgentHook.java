package com.agentplatform.plugin.sdk;

import com.agentplatform.plugin.sdk.model.HookPoint;

/**
 * 运行钩子 SPI（自动回复 / 脱敏 / TTS / 降级兜底等即在此实现）。
 *
 * <h3>返回值约定（宿主按此解析，2026-09-22 补全）</h3>
 * <table border="1">
 *   <caption>各钩子点的返回值语义</caption>
 *   <tr><th>钩子点</th><th>返回</th><th>效果</th></tr>
 *   <tr><td rowspan="3">{@code before_llm}</td>
 *       <td>String</td><td><b>短路</b>：不调 LLM，直接作为最终回复</td></tr>
 *   <tr><td>{@code Map{input: String}}</td><td><b>改写输入</b>：改后文本传给 LLM</td></tr>
 *   <tr><td>null</td><td>透传</td></tr>
 *   <tr><td rowspan="2">{@code after_llm}</td>
 *       <td>Map</td><td><b>附加产物</b>（如 {@code audio_url}）。<b>改不了输出文本</b></td></tr>
 *   <tr><td>null</td><td>无附加</td></tr>
 *   <tr><td rowspan="2">{@code before_output}</td>
 *       <td>String / {@code Map{output: String}}</td><td><b>替换最终输出</b>（脱敏 / 合规改写）</td></tr>
 *   <tr><td>null</td><td>透传</td></tr>
 *   <tr><td rowspan="2">{@code on_error}</td>
 *       <td>String / {@code Map{reply: String}}</td><td><b>兜底回复</b>：LLM 异常时用它收场，异常不再上抛</td></tr>
 *   <tr><td>null</td><td>不兜底，异常照常上抛</td></tr>
 * </table>
 *
 * <p>Map 形式下 {@code input}/{@code output}/{@code reply} 各自有几个等价键（见
 * {@code AgentPipeline}）。历史遗留：{@code after_llm} 曾被文档描述为"改写输出"，
 * 实际它只收附加产物 —— 要改输出请用 {@code before_output}。</p>
 *
 * <p><b>注意</b>：钩子只在<b>非流式</b>链路（{@code AgentRuntimeService.run()}）触发，
 * 流式（{@code runStream()}）不经过管线。</p>
 */
public interface AgentHook extends Plugin {

    /**
     * 钩子触发点。
     */
    HookPoint point();

    /**
     * 执行钩子逻辑。
     *
     * <p>异常会被宿主捕获并降级跳过（只记日志，不影响其它钩子与主流程），
     * 所以插件不必自己包 try/catch。</p>
     *
     * @param ctx 钩子上下文（含输入数据；{@code on_error} 时 metadata 里带 {@code exception}）
     * @return 处理结果，语义见类注释的返回值约定
     */
    Object invoke(HookContext ctx);
}