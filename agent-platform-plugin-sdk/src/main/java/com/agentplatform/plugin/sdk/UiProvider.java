package com.agentplatform.plugin.sdk;

import com.agentplatform.plugin.sdk.model.PluginManifest;

import java.util.List;

/**
 * 界面贡献 SPI —— 插件借此往宿主界面注入**受控的**界面元素。
 *
 * <h3>与 {@code contributes.ui}（manifest 声明）的关系</h3>
 * 两者产出的都是 {@link PluginManifest.Contributes.UiDef}，区别只在**谁写的**：
 * <ul>
 *   <li><b>外部 jar 插件</b>：在 {@code plugin.yaml} 的 {@code contributes.ui} 里声明 ——
 *       它们是独立分发的，宿主编译期看不见它们，只能靠清单；</li>
 *   <li><b>内置插件</b>：实现本接口 —— 代码就在宿主里，用 Java 表达比手写一份 YAML 直观。</li>
 * </ul>
 *
 * <h3>★ 为什么是"声明"而不是"给一段前端代码"</h3>
 * 平台里确实有一条执行第三方 JS 的通道（皮肤运行时），但那条是给<b>外观</b>用的、且<b>无沙箱</b>。
 * 外观出问题最坏是难看；而按钮、表单、数据展示一旦被第三方代码接管，
 * 风险就从"难看"变成"功能与数据"。所以这里只允许宿主已知的几种组件，
 * 插件给数据、宿主渲染 —— 与皮肤自定义协议（{@code skin/customization.ts}）同一思路。
 *
 * <h3>三条约定</h3>
 * <ol>
 *   <li><b>必须无副作用、可反复调用</b>：宿主在挂载与列表展示时都会调它；</li>
 *   <li><b>引用的工具必须先声明</b>：{@code action.tool} / {@code dataSource.tool} 指向的名字
 *       必须出现在 {@link ToolProvider#provideTools()} 里 —— 宿主会校验，指向不存在的工具会被丢弃；</li>
 *   <li><b>认不出的槽位与组件类型会被静默跳过</b>（不是报错）。这样宿主增加新槽位、
 *       或插件用了新版才有的组件类型时，旧的一侧只是少显示一块，而不是整个插件装不上。</li>
 * </ol>
 */
public interface UiProvider extends Plugin {

    /**
     * 声明本插件的界面贡献。
     *
     * @return 界面元素列表；没有则返回空列表（不要返回 null）
     */
    List<PluginManifest.Contributes.UiDef> provideUi();
}
