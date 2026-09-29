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
            /** 界面贡献（前端插槽）。见 {@link UiDef} 的说明。 */
            List<UiDef> ui
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
    }

    public record Requires(String platform_version, List<String> plugins) {
    }

    public record Runtime(String isolation, Integer memory_mb, Integer timeout_ms) {
    }
}