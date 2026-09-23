package com.agentplatform.model.enums;

/**
 * 工具审批状态。
 *
 * <p>刻意只有四种，且**没有"执行中"**这一态：批准与执行是同一个动作的两半
 * （同一把事务里改状态 + 执行），不需要中间态。若将来执行改成异步，
 * 才需要考虑补一个 {@code executing} —— 现在加只会多出一堆"卡在 executing 的记录"要清理。</p>
 *
 * <p>{@link #expired} 是给"陈年申请"用的兜底：一条待审批记录放了几十天没人管，
 * 说明那个上下文早已过去，此时若还有人去点批准，执行的是一次**与当前对话无关**的写操作 ——
 * 这比拒绝更危险。所以过期不是清理垃圾，而是一道安全阀。</p>
 */
public enum ToolApprovalStatus {

    /** 待用户确认。 */
    pending("待审批"),

    /** 已批准并已执行（执行失败也会落到这里，失败原因在 error_msg）。 */
    approved("已批准"),

    /** 用户拒绝，未执行。 */
    rejected("已拒绝"),

    /** 超过保留期未处理，自动作废（防止陈旧申请被误批准）。 */
    expired("已过期");

    private final String label;

    ToolApprovalStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
