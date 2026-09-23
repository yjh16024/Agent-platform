package com.agentplatform.model.enums;

/**
 * 通知级别（前端据此决定是否高亮、是否计入"需处理"）。
 *
 * <p>刻意只留三档，与 {@code LogLevel} 的取舍一致：级别过多会让"该不该看"这件事
 * 从判断变成分类学，而界面能表达的视觉差异也就三档。</p>
 */
public enum NotificationLevel {

    /** 一般信息，看过即可。 */
    info,

    /** 需要注意，但不影响当下操作。 */
    warn,

    /** 已经或即将造成影响，需要处理。 */
    error
}
