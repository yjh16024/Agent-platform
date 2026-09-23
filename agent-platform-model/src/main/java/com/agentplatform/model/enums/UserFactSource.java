package com.agentplatform.model.enums;

/**
 * 长期记忆（用户画像）的来源。
 *
 * <p><b>为什么现在就留这个字段</b>：按 backlog 的决定，长期记忆分两步走 ——
 * 先做「用户主动填写」，自动抽取后置。但字段现在就加上，因为：</p>
 * <ul>
 *   <li>加一个枚举值比改表便宜得多（迁移一旦执行就不可回改）；</li>
 *   <li>将来自动抽取上线后，必须能把它与用户手填的**区分开展示** ——
 *       用户要有机会看到"系统替我记了什么"并能逐条确认/删除，
 *       否则自动抽取就是在用户不知情的情况下改写他的画像。</li>
 * </ul>
 */
public enum UserFactSource {

    /** 用户主动填写（当前唯一的来源）。 */
    manual("用户填写"),

    /** 系统自动抽取（**当前未启用**，字段预留给后续实现）。 */
    auto("系统提取");

    private final String label;

    UserFactSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 宽松解析：非法值回落到 {@link #manual}（默认按"用户填的"处理更安全）。 */
    public static UserFactSource of(String raw) {
        if (raw == null || raw.isBlank()) {
            return manual;
        }
        for (UserFactSource s : values()) {
            if (s.name().equalsIgnoreCase(raw.trim())) {
                return s;
            }
        }
        return manual;
    }
}
