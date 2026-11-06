package com.agentplatform.framework.api;

/**
 * 会话句柄：运行时要用的**最小会话信息**。
 *
 * <p>存在的理由：平台原先让 {@code SessionService.resolve()} 直接返回 JPA 实体
 * {@code Session}，运行时于是把实体当数据载体用。这带来两个后果：
 * 换存储就得改运行时；嵌入方被迫引入一整套实体与表。
 * 句柄只保留「运行时真正读的字段」，与存储实现解耦。</p>
 *
 * <p>注意它**不含** {@code status} / {@code createdAt} 等管理面字段——
 * 那些是运营视角的东西，运行时不关心。</p>
 */
public record SessionHandle(String sessionId, String tenantId, String agentId, String userId, String title) {

    public SessionHandle {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 必填");
        }
        tenantId = (tenantId == null || tenantId.isBlank()) ? Principal.DEFAULT_TENANT : tenantId;
    }
}
