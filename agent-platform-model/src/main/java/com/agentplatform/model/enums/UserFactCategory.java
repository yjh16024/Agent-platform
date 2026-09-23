package com.agentplatform.model.enums;

/**
 * 长期记忆（用户画像）的分类。
 *
 * <p>只做「分组展示」用，不参与任何注入决策 —— 注入时按 key 拼成一段文本，
 * 分类只影响前端列表上色的分组标题。刻意保持极少几个值：分类一多，
 * 用户填一条画像时先要纠结"这算背景还是偏好"，反而降低填写意愿。</p>
 */
public enum UserFactCategory {

    /** 偏好：回答风格、语言、单位等。 */
    preference("偏好"),

    /** 背景：职业、所在行业、技术栈等稳定事实。 */
    background("背景"),

    /** 目标：当前在做的事、想达成的结果（有生命周期，但通常跨多次会话）。 */
    goal("目标"),

    /** 其他：不好归类的内容，也是未指定时的默认值。 */
    other("其他");

    private final String label;

    UserFactCategory(String label) {
        this.label = label;
    }

    /** 中文展示名（前端直接用，避免前端再维护一份映射）。 */
    public String label() {
        return label;
    }

    /** 宽松解析：非法值回落到 {@link #other}，不抛异常（入参来自表单，不该让脏值 500）。 */
    public static UserFactCategory of(String raw) {
        if (raw == null || raw.isBlank()) {
            return other;
        }
        for (UserFactCategory c : values()) {
            if (c.name().equalsIgnoreCase(raw.trim())) {
                return c;
            }
        }
        return other;
    }
}
