package com.agentplatform.framework.springai;

/**
 * Advisor 执行顺序（数值越小越先执行）。
 *
 * <p><b>顺序本身是语义</b>，不是可调参数：
 * <ul>
 *   <li>长度守卫必须最早——先看预算，再决定往里加什么；</li>
 *   <li>记忆注入晚于守卫——加进来的内容也会被守卫看到（所以守卫留了余量）；</li>
 *   <li>插件钩子晚于记忆——插件能看见记忆，且它可以<b>短路</b>，短路后不应再付记忆的 token；</li>
 *   <li>RAG 晚于插件——被短路时不必再去检索。</li>
 * </ul>
 *
 * <p>参考：技术方案 §4.3.4。
 */
public final class AdvisorOrders {

    public static final int GUARD = 50;
    public static final int MEMORY = 100;
    public static final int PLUGIN_HOOK = 150;
    public static final int RAG = 200;
    public static final int LOGGING = 500;

    private AdvisorOrders() {
    }
}
