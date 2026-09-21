package com.agentplatform.model.enums;

/**
 * 用户状态。
 * <p>
 * 只有两个值是有意的：不做「锁定 / 待激活」这类状态机，
 * 因为本项目没有邮件激活与自动锁定策略，多出来的状态只会成为无人处理的死状态。
 * </p>
 */
public enum SysUserStatus {

    /** 正常，可登录。 */
    active,

    /** 已停用：拒绝登录，但保留其历史数据与审计痕迹。 */
    disabled
}
