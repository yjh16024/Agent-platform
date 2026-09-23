package com.agentplatform.core.tool.approval;

import java.util.function.Consumer;

/**
 * 审批唤醒的**跨实例广播通道**。
 *
 * <h3>它存在的理由</h3>
 * "就地确认"模式靠 {@code ApprovalService} 里一张**进程内**的等待表
 * （{@code approvalId → CompletableFuture}）来唤醒阻塞中的工具调用。
 * 单实例（含桌面版）完全没问题；但**多实例部署**时会出现这种情况：
 *
 * <pre>
 *   实例 A：用户发起对话 → 工具阻塞等待
 *   实例 B：用户点「批准」  ← 负载均衡把请求分到了另一个实例
 *   ⇒ B 改了数据库状态、A 的等待表里却没有任何人被告知，工具一直等到超时降级
 * </pre>
 *
 * <p>用户看到的是"我明明点了批准，它却说等待超时"，而且**只在多实例下偶发** ——
 * 这类问题最难查。所以留出这个通道：批准动作除了唤醒本进程的等待者，
 * 还广播一条消息；各实例收到后检查自己的等待表，有就唤醒。</p>
 *
 * <h3>为什么默认是"什么都不做"的实现</h3>
 * 广播依赖 Redis（{@link RedisApprovalWakeChannel}）。而没有 Redis 的部署
 * （桌面版就是）**必然是单实例**，本来就不存在这个问题 ——
 * 此时广播是纯浪费。所以默认用 {@link LocalOnlyWakeChannel}，
 * 有 Redis 时才自动换成真正广播的实现。
 *
 * <p>换实现不需要改 {@code ApprovalService} 的任何逻辑：它只管
 * {@code publish(...)} 与注册回调，不知道底下是 Redis 还是空气。</p>
 */
public interface ApprovalWakeChannel {

    /**
     * 广播"某条审批有决定了"。
     *
     * <p>实现**不应抛异常**：广播失败最多让别的实例晚一点（或等到超时降级），
     * 不该让正在处理用户请求的这条链路跟着失败。</p>
     */
    void publish(String approvalId);

    /**
     * 注册收到广播时的处理（通常就是去唤醒本地等待表）。
     *
     * <p>由 {@code ApprovalService} 在启动后调用一次。</p>
     *
     * <p>回调参数是 {@code approvalId} —— 它由 {@code IdGenerator} 生成、**全局唯一**，
     * 所以跨实例传递它不需要再带租户（接收方按它查一次记录即可）。</p>
     */
    void onWake(Consumer<String> handler);
}
