package com.agentplatform.model.enums;

/**
 * 智能体/插件的可见范围。
 */
public enum Visibility {
    /** 私有（仅创建者可见） */
    private_,
    /** 团队可见 */
    team,
    /** 公开 */
    public_,
    /** 市场上架 */
    marketplace;

    /** 与数据库存储字符串的互转（避免 JSON 序列化为 private/public 关键字歧义）。 */
    public String toDb() {
        return switch (this) {
            case private_ -> "private";
            case team -> "team";
            case public_ -> "public";
            case marketplace -> "marketplace";
        };
    }

    public static Visibility fromDb(String v) {
        return switch (v == null ? "" : v) {
            case "team" -> team;
            case "public" -> public_;
            case "marketplace" -> marketplace;
            default -> private_;
        };
    }
}