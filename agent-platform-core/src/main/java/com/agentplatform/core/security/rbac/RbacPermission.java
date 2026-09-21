package com.agentplatform.core.security.rbac;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限点清单 —— **整个权限体系的唯一权威来源**。
 *
 * <h3>为什么用枚举而不是常量类或数据库表</h3>
 * <ul>
 *   <li>注解 {@code @RequiresPermission("agent:write")} 里写的是字符串，编译器管不了拼写；
 *       枚举把可用权限收在一处，写错就在代码评审时暴露，而不是上线后表现为"权限怎么都不生效"；</li>
 *   <li>值同时驱动建表数据（{@code RbacSeeder} 启动时把枚举同步进 {@code sys_permission}），
 *       不存在"代码里有、库里没有"的分叉；</li>
 *   <li>{@link #group()} 直接充当界面权限树的父节点，少维护一份分组配置。</li>
 * </ul>
 *
 * <h3>粒度约定</h3>
 * 只分到「模块 + 读写」两级（{@code agent:read} / {@code agent:write}），不做到按钮级。
 * 原因是本项目 17 个 Controller 的接口数量远大于业务概念数量，按钮级权限会得到
 * 一个没人愿意维护的清单；模块级已经足够支撑"管理员 / 操作员 / 只读"三种角色的差异。
 */
public enum RbacPermission {

    // ---- 智能体 ----
    AGENT_READ("agent:read", "查看智能体", "智能体"),
    AGENT_WRITE("agent:write", "编辑智能体", "智能体"),
    AGENT_INVOKE("agent:invoke", "运行智能体与对话", "智能体"),

    // ---- 知识库（RAG）----
    KB_READ("kb:read", "查看知识库", "知识库"),
    KB_WRITE("kb:write", "编辑知识库与文档", "知识库"),

    // ---- 工具 ----
    TOOL_READ("tool:read", "查看工具", "工具"),
    TOOL_WRITE("tool:write", "注册与修改工具", "工具"),
    TOOL_INVOKE("tool:invoke", "调用工具", "工具"),

    // ---- 工作流 ----
    WORKFLOW_READ("workflow:read", "查看工作流", "工作流"),
    WORKFLOW_WRITE("workflow:write", "编辑与发布工作流", "工作流"),

    // ---- 会话与文件 ----
    // 拆成"看"与"改"两档：会话历史与上传文件都是敏感数据，
    // 只读角色（viewer）应当能看，但不该能删。
    SESSION_READ("session:read", "查看会话历史", "会话"),
    SESSION_MANAGE("session:manage", "删除会话", "会话"),
    FILE_READ("file:read", "查看与下载文件", "文件"),
    FILE_MANAGE("file:manage", "上传与删除文件", "文件"),

    // ---- 模型 ----
    // 模型配置里含厂商密钥（AES-GCM 加密存储），必须比"看配置"更严：统一按管理粒度管控。
    MODEL_MANAGE("model:manage", "配置模型与密钥", "模型"),

    // ---- 扩展（插件 / Skills / 皮肤）----
    PLUGIN_MANAGE("plugin:manage", "管理插件", "扩展"),
    SKILL_MANAGE("skill:manage", "管理 Skills", "扩展"),
    SKIN_MANAGE("skin:manage", "管理皮肤", "扩展"),

    // ---- 运维 ----
    LOG_READ("log:read", "查看运行日志", "运维"),
    // 清理日志是**不可逆**操作，必须与"看日志"分开：否则只读角色就能抹掉审计痕迹。
    LOG_MANAGE("log:manage", "清理与采集日志", "运维"),
    QUOTA_MANAGE("quota:manage", "配置租户配额", "运维"),

    // ---- 系统管理（本题新增的模块）----
    USER_MANAGE("user:manage", "管理用户", "系统管理"),
    ROLE_MANAGE("role:manage", "管理角色与权限", "系统管理"),

    // ---- 数据字典 ----
    // 读码以 :read 结尾 → 自动进 readOnlyCodes()，所以 viewer 也会拿到。
    // 这是有意的：前端每个下拉都要读字典，viewer 拿不到就会满页空下拉。
    // 写码放进 systemCodes() → operator 拿不到，只有 admin 能改。
    // 理由：字典是**全局影响面最大**的配置 —— 改错一个标签，所有引用它的页面都跟着变。
    DICT_READ("dict:read", "查看数据字典", "系统管理"),
    DICT_WRITE("dict:write", "管理数据字典", "系统管理"),

    // ---- 操作日志 / 审计 ----
    // 读码同样以 :read 结尾 → 自动进 readOnlyCodes()，三个内置角色都能看
    //（审计是"给人看的"，只让 admin 看反而会让运维查不到问题）。
    // 但**清理审计记录**是不可逆操作，且能抹掉追责线索，所以单独一个 manage 码、只给 admin。
    AUDIT_READ("audit:read", "查看操作日志", "运维"),
    AUDIT_MANAGE("audit:manage", "清理操作日志", "运维"),

    // ---- 统计报表 ----
    // 读码以 :read 结尾 → 自动进 readOnlyCodes()，三个内置角色都能看（报表是"给人看的"）。
    // **刻意不设 report:manage**：报表只有查询聚合、不改任何数据，做不出"写操作"这件事，
    // 加一个权限码只会让清单变长而没有实际管控对象。
    REPORT_READ("report:read", "查看统计报表", "运维");

    private final String code;
    private final String label;
    private final String group;

    RbacPermission(String code, String label, String group) {
        this.code = code;
        this.label = label;
        this.group = group;
    }

    public String code() {
        return code;
    }

    /**
     * 中文显示名。
     * <p>
     * ⚠️ **不能叫 {@code name()}** —— {@link Enum#name()} 是 final 的，声明同名方法会直接编译报错
     * （写成 {@code name()} 时编译器的提示是"无法覆盖 java.lang.Enum 中的 name()，被覆盖的方法为 final"）。
     * 这也是我把访问器统一写成 {@code code()/label()/group()} 这种 record 风格的原因：
     * 它天然避开 {@code getName()} 之类的 Bean 命名冲突，语义也更紧。
     * </p>
     */
    public String label() {
        return label;
    }

    public String group() {
        return group;
    }

    /** 全部权限码（保留声明顺序，界面分组渲染时顺序稳定）。 */
    public static Set<String> allCodes() {
        return Arrays.stream(values())
                .map(RbacPermission::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 只读权限（以 {@code :read} 结尾）——viewer 角色的权限集。 */
    public static Set<String> readOnlyCodes() {
        return Arrays.stream(values())
                .map(RbacPermission::code)
                .filter(c -> c.endsWith(":read"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 系统管理类权限码，operator 角色需要排除它们。
     * <p>注意 {@code DICT_READ} **不在**这里：读字典是普适能力（每个页面下拉都要用），
     * operator 与 viewer 都该有；只有 {@code DICT_WRITE} 需要收紧到 admin。</p>
     */
    public static Set<String> systemCodes() {
        return Set.of(USER_MANAGE.code, ROLE_MANAGE.code, DICT_WRITE.code, AUDIT_MANAGE.code);
    }
}
