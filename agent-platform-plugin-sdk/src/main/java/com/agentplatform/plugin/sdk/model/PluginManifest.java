package com.agentplatform.plugin.sdk.model;

import java.util.List;
import java.util.Map;

/**
 * 插件 Manifest（plugin.yaml 的解析结果）。
 * <p>插件身份、能力贡献、依赖、权限的单一声明。</p>
 *
 * @param id           全局唯一 ID
 * @param name         名称
 * @param version      语义化版本
 * @param description  描述
 * @param author       作者
 * @param entry        入口（type=java/script/http/mcp, main_class=实现类）
 * @param contributes  能力贡献（tools/hooks/resources/ui）
 * @param requires     运行约束（platform_version, plugins 依赖）
 * @param permissions  最小权限声明
 * @param runtime      运行时约束（isolation/memory_mb/timeout_ms）
 */
public record PluginManifest(
        String id,
        String name,
        String version,
        String description,
        String author,
        Entry entry,
        Contributes contributes,
        Requires requires,
        Map<String, Object> permissions,
        Runtime runtime
) {
    public record Entry(String type, String main_class) {
    }

    public record Contributes(
            List<ToolDef> tools,
            List<HookDef> hooks,
            List<ResourceDef> resources,
            /** 界面贡献（**声明式**组件，宿主渲染）。见 {@link UiDef} 的说明。 */
            List<UiDef> ui,
            /**
             * 可执行界面贡献（2026-10-19，D 档）：**插件携带的前端脚本，宿主加载后在页面内执行**。
             *
             * <p>与 {@code ui} 的区别只有一个：**谁来渲染**。{@code ui} 由宿主用受控白名单渲染，
             * 这里由插件自己渲染（同上下文，能力全开）。</p>
             *
             * <p>信任前提与插件 jar 完全一致 —— **装插件即信任它的前端代码**
             * （jar 本来就是任意 Java 代码）。因此不做沙箱；但保留 {@code isolate} 开关，
             * 默认 Shadow DOM（样式不污染宿主、也改不到宿主结构），需要"改宿主界面"时显式关掉。</p>
             */
            List<UiScriptDef> ui_scripts
    ) {
        public record ToolDef(String name, String description, Map<String, Object> input_schema) {
        }

        public record HookDef(String point, String handler) {
        }

        public record ResourceDef(String id, String type) {
        }

        /**
         * 一条界面贡献：**插件声明"往哪个位置放什么东西"，宿主用 React 渲染它**。
         *
         * <h3>★ 为什么是"声明式"而不是"插件提供前端代码"</h3>
         * 平台里确实有一条"执行第三方 JS"的通道（皮肤运行时），但那是为<b>外观</b>设计的、
         * 且<b>无沙箱</b>（皮肤与宿主同上下文）。外观能容忍"任意 DOM 与网络"，
         * 功能性 UI（按钮、表单、数据展示）不能 —— 那会把风险从"难看"升级为"功能与数据"。
         *
         * <p>所以这里只允许**宿主已知的几种受控组件**，插件给出数据、宿主负责渲染与生命周期。
         * 这与皮肤自定义协议（{@code skin/customization.ts}）是同一套思路。</p>
         *
         * <h3>动作与数据都走工具通道</h3>
         * 点击触发 {@link ActionDef#tool()}、动态数据来自 {@link DataSourceDef#tool()} ——
         * 两者都是**已经存在的工具调用机制**（{@code POST /tools/{name}/invoke}）。
         * 于是插件**永远不需要接触前端代码**，宿主也不必新增任何调用通道。
         *
         * @param id         在**本插件内**唯一（宿主用它做 React key 与去重）
         * @param slot       槽位 id，取值见平台的 UI 槽位清单（如
         *                   {@code conversation.session.header.actions}）。**认不出的槽位会被忽略**，
         *                   这样新槽位可以随时增加而不让旧插件报错
         * @param type       受控组件类型：{@code button} / {@code badge} / {@code card} /
         *                   {@code list} / {@code link}。**认不出的类型会被跳过**（不是报错）
         * @param label      显示文本。**一律按纯文本渲染** —— 它是不可信输入，绝不作为 HTML 注入
         * @param order      同槽位内的排序，小的在前；为空按 0
         * @param href       仅 {@code link} 用：跳转地址
         * @param action     点击行为；为空表示不可点击
         * @param dataSource 动态数据来源；为空表示只显示静态 {@code label}
         */
        public record UiDef(
                String id,
                String slot,
                String type,
                String label,
                Integer order,
                String href,
                ActionDef action,
                DataSourceDef dataSource
        ) {
        }

        /**
         * 界面元素的点击行为。
         *
         * @param kind 目前只支持 {@code tool}（调用本插件贡献的某个工具）
         * @param tool 工具名；必须在同一 manifest 的 {@code contributes.tools} 里声明过
         */
        public record ActionDef(String kind, String tool) {
        }

        /**
         * 界面元素的动态数据来源。
         *
         * <p>宿主会调用该工具、把返回的文本渲染进组件。约定工具返回**可直接展示的短文本**
         * （如 "¥12.34"），而不是给机器读的 JSON —— 因为渲染方是宿主，不是插件。</p>
         *
         * @param tool 工具名；必须在本 manifest 的 {@code contributes.tools} 里声明过
         */
        public record DataSourceDef(String tool) {
        }

        /**
         * 一条**可执行**界面贡献：插件自带一段前端脚本，宿主负责加载与生命周期。
         *
         * <h3>加载方式</h3>
         * 宿主把 {@code entry} 拼成 {@code /api/v1/plugins/{id}/web/<entry>} 注入
         * {@code <script>}，脚本调用 {@code window.__apPlugin__.register(pluginId, { apply(ctx) })}
         * 自注册；{@code apply} 的返回值是 disposer（卸载时调用）。
         *
         * <h3>与 {@link UiDef} 的关系</h3>
         * 两者在服务端的输出里**合并为同一个列表**（前端只多一个 {@code type=script} 分支），
         * 但声明结构分开：{@link UiDef} 全部字段都是"宿主渲染所需的数据"，
         * 而这里只有"从哪加载"与"隔离与否"——描述的东西不同，硬塞进一个 record 会让另一边半数字段恒为 null。
         *
         * @param id      在**本插件内**唯一（宿主用它做 React key 与去重）
         * @param slot    槽位 id（新增的 {@code global.overlay} 表示整页覆盖层）
         * @param entry   脚本路径，**相对插件根**（如 {@code web/pet.js}）；必须在 {@code web/} 下
         * @param style   样式路径（可选，同样相对插件根）
         * @param isolate 是否用 Shadow DOM 隔离渲染容器：默认 {@code true}；
         *                需要修改宿主界面结构时才显式设 {@code false}
         * @param order   同槽位内的排序，小的在前；为空按 0
         * @param label   诊断用名称（加载失败时显示在提示里）
         */
        public record UiScriptDef(
                String id,
                String slot,
                String entry,
                String style,
                Boolean isolate,
                Integer order,
                String label
        ) {
        }
    }

    public record Requires(String platform_version, List<String> plugins) {
    }

    public record Runtime(String isolation, Integer memory_mb, Integer timeout_ms) {
    }
}