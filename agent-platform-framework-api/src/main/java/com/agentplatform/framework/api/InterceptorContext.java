package com.agentplatform.framework.api;

/**
 * 拦截器上下文：一次运行中可被拦截器读写的部分。
 *
 * <p>只放「插件确实会用到的字段」：谁在跑、跑在哪个租户/会话、当前的输入文本。
 * 不放数据库实体，也不放模型响应对象——后者会把拦截器绑死在某个实现上。</p>
 *
 * @param input 当前输入；经过 {@code beforeModel} 改写后会被替换
 */
public record InterceptorContext(String runId, String agentId, String tenantId,
                                 String sessionId, String input) {

    public InterceptorContext withInput(String newInput) {
        return new InterceptorContext(runId, agentId, tenantId, sessionId, newInput);
    }
}
