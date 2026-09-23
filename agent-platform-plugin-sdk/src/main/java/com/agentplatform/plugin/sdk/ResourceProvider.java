package com.agentplatform.plugin.sdk;

import com.agentplatform.plugin.sdk.model.ResourceDescriptor;

import java.util.List;

/**
 * 资源托管 SPI —— 插件把自己持有的<b>外部依赖</b>暴露给宿主，供同智能体下的其它插件复用。
 *
 * <p>解决的现实问题：没有它时，每个需要外部服务的插件都得在自己的 {@code config} 里
 * 放一份明文凭据，多个插件用同一个服务就得多份拷贝、各自轮换。有了它，<b>一个插件声明、
 * 多方按 id 取用</b>，宿主得以在中间做统一托管。</p>
 *
 * <h3>两个方法的分工（2026-09-22 重新设计）</h3>
 * <ul>
 *   <li>{@link #provideResources()} —— <b>声明</b>有哪些资源。必须<b>无副作用、可反复调用</b>：
 *       挂载时会被调一次用于登记，界面/诊断也会在不初始化连接的前提下调它列清单。</li>
 *   <li>{@link #provide(String)} —— <b>惰性给出</b>资源本体。只有真正被 resolve 时才调，
 *       所以建连接、取密钥这类动作应当放在这里而不是 {@code provideResources()}。</li>
 * </ul>
 *
 * <h3>怎么被别人用</h3>
 * <pre>
 *   // 提供方
 *   public class MyVault implements ResourceProvider {
 *       public List&lt;ResourceDescriptor&gt; provideResources() {
 *           return List.of(ResourceDescriptor.of("my-vault:openai", ResourceTypes.SECRET));
 *       }
 *       public Object provide(String resourceId) { return apiKey; }
 *       public String resourceType() { return ResourceTypes.SECRET; }
 *   }
 *
 *   // 使用方（同一个智能体上）
 *   public void onAttach(PluginContext ctx) {
 *       Object key = ctx.registrar().resolveResource("my-vault:openai");
 *   }
 * </pre>
 *
 * <h3>可见性</h3>
 * <p><b>只能解析到「同一智能体」上的资源</b>：跨智能体不可见（否则挂到 A 的插件会把
 * 自己的凭据泄露给 B）。判定依据是注册时的 attach 作用域，插件无需也无法指定归属。</p>
 */
public interface ResourceProvider extends Plugin {

    /**
     * 声明本插件提供的资源清单。
     *
     * <p><b>必须是纯声明</b>：不能在这里建连接或读密钥 —— 宿主在挂载与列表展示时都会调用它。</p>
     *
     * @return 资源描述列表；没有则返回空列表（不要返回 null）
     */
    List<ResourceDescriptor> provideResources();

    /**
     * 按 ID 给出资源本体（惰性，只有被 resolve 时才调）。
     *
     * @param resourceId {@link #provideResources()} 里声明过的 ID
     * @return 资源对象；无法提供时返回 null（宿主会把 null 视为"解析失败"）
     */
    Object provide(String resourceId);

    /**
     * 本插件主要提供的资源类型，取值见
     * {@link com.agentplatform.plugin.sdk.model.ResourceTypes}。
     *
     * <p>用于界面分组与日志；单个插件声明的多个资源<b>不要求</b>都属这一类型
     * （每个 {@link ResourceDescriptor} 自带 {@code resourceType}）。</p>
     */
    String resourceType();
}
