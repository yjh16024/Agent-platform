package com.agentplatform.model.enums;

/**
 * 通知类型（前端按此分组与上色）。
 *
 * <p>值与项目既有枚举一致采用小写，并用 {@code @Enumerated(EnumType.STRING)} 存字符串 ——
 * 这样以后在中间插入新值也不会让历史数据的含义发生偏移（按序号存储的经典坑）。</p>
 */
public enum NotificationType {

    /** 系统类：维护公告、版本升级、配置变更提醒。 */
    system,

    /** 任务类：智能体运行失败、文档摄取完成、导入导出结束。 */
    task,

    /** 配额类：用量接近或超出限额。 */
    quota,

    /** 安全类：越权尝试被拒、登录异常、密钥失效。 */
    security
}
