package com.agentplatform.framework.api;

import reactor.core.publisher.Flux;

/**
 * 框架对外的唯一运行入口。
 *
 * <p>一个实现只做一件事：把 {@link RunCommand} 跑成 {@link RunResult} 或 {@link RunEvent} 流。
 * 它不负责智能体配置从哪来、会话存哪、工具怎么注册——这些由实现方通过自己的策略装配
 * （见 {@code docs/framework-plan.md} §3 的三条边界）。
 *
 * <p><b>两种调用方式必须语义等价</b>：{@link #run} 的返回值应等于
 * {@link #runStream} 终止事件 {@link RunEvent.Completed#result()} 的载荷。
 * 这条由实现方的契约测试保证，不是调用方需要判断的事。
 */
public interface AgentRuntime {

    /**
     * 同步运行（阻塞直到结束）。
     *
     * @throws RuntimeException 实现方定义的失败；调用方若需要错误分类，请改用 {@link #runStream}
     */
    RunResult run(RunCommand command);

    /**
     * 流式运行。
     *
     * <p>返回的 {@link Flux} 必须满足 {@link RunEvent} 的四条契约（首个为 Started、
     * seq 严格递增、唯一终止事件且位于末尾）。错误可以以 {@link RunEvent.Failed} 的形式
     * 正常结束，也可以通过 {@code Flux.error} 抛出——但**不要两者都用**。
     */
    Flux<RunEvent> runStream(RunCommand command);
}
