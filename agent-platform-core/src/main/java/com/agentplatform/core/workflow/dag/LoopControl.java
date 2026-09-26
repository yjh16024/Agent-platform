package com.agentplatform.core.workflow.dag;

/**
 * 循环控制指令 —— {@code LoopNodeExecutor} 的返回值，由 {@link DagEngine} 解释执行。
 *
 * <h3>为什么不让执行器自己跑循环</h3>
 * 执行器只拿到 {@code (node, ctx)}，拿不到 {@code WorkflowDefinition}，也就无法沿 {@code next}
 * 链执行循环体；而且"重复执行节点"要绕过引擎的环保护（{@code executed} 集合），那是引擎的内部机制。
 * 所以分工是：**执行器负责解析并校验配置，引擎负责执行循环** —— 与 {@code Condition} 的思路一致
 * （那个也是执行器返回目标节点 id、引擎负责跳转）。
 *
 * <h3>循环体的边界怎么定（重要）</h3>
 * 循环体**不能**通过 {@code next} / {@code branches} 指回自己 —— 那会让图变成环，
 * 被 {@code WorkflowSchemaValidator.hasCycle()} 在保存期拒绝。所以循环体入口写在
 * {@code config.loop_body} 里（环检测不看 config）。
 *
 * <p>运行期的边界判定是：<b>从 {@code bodyEntry} 沿 {@code next} 链往下走，
 * 直到遇到 Loop 节点自身的下一个节点（{@code loop.nextIds()} 的目标）或链的尽头为止</b>。
 * 也就是说循环体的最后一个节点，它的 {@code next} 应当指向"循环结束后要去的那个节点"。</p>
 *
 * <pre>
 *   loop_0 (Loop, config.loop_body = body_1)  ──next──▶  after
 *   body_1 ──next──▶ body_2 ──next──▶ after
 *   ⇒ 循环体 = [body_1, body_2]，每轮重跑；走完 after 即视为本轮结束
 * </pre>
 *
 * @param bodyEntry     循环体入口节点 id（来自 {@code config.loop_body}）
 * @param maxIterations 迭代上限（安全阀，解析时已被夹在 1..1000）
 * @param timeoutMs     总超时毫秒（安全阀，到点即停止而非抛异常）
 * @param condition     继续条件表达式（如 {@code ${i} < 5}）；为 null 表示跑满 {@code maxIterations} 次。
 *                      在**每轮结束时**求值，为假则退出。
 * @param indexVar      迭代序号写入的变量名（从 0 开始计数）；为 null 表示不写
 */
public record LoopControl(
        String bodyEntry,
        int maxIterations,
        long timeoutMs,
        String condition,
        String indexVar) {
}
