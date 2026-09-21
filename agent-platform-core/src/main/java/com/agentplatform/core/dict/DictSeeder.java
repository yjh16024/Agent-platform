package com.agentplatform.core.dict;

import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.log.LogCategory;
import com.agentplatform.core.log.LogLevel;
import com.agentplatform.core.rag.chunker.Chunker;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.model.entity.SysDictItem;
import com.agentplatform.model.entity.SysDictType;
import com.agentplatform.model.repository.SysDictItemRepository;
import com.agentplatform.model.repository.SysDictTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 内置字典的初始化与补齐（仿 {@code RbacSeeder} 的做法）。
 *
 * <h3>为什么初值要"从代码里取"而不是手抄</h3>
 * 这几类字典的**真实来源已经在代码里**：日志级别/分类是枚举、切分策略是
 * {@code @Component("recursive")} 之类的 Bean、内置工具是 {@code @Component} 的 Tool。
 * 如果在这里手抄一份，就会重新制造"两份定义必然漂移"的问题 ——
 * 而这正是做数据字典要消灭的东西。
 *
 * <p>验证过的两处细节：</p>
 * <ul>
 *   <li>{@code Map<String, Chunker>} 的 key 就是 {@code Chunker.strategy()}（Bean 名）；</li>
 *   <li>{@code Map<String, Tool>} **恰好只有内置工具** —— 因为 MCP / HTTP / 插件工具
 *       都是手动 {@code new} 出来注册进 {@code ToolRegistry} 的，不是 Spring Bean。</li>
 * </ul>
 *
 * <h3>幂等策略：只补不覆盖</h3>
 * 类型与字典项都存在就跳过，**不覆盖用户改过的标签** —— 与 {@code RbacSeeder.topUpRole}
 * 的"只补不删"一致。否则用户精心调整过的中文标签会在每次重启后被重置回去。
 *
 * <p>挂在 {@code ApplicationReadyEvent} 上（且 {@code @Lazy(false)}）：要等所有 Bean
 * 装配完毕，那两个 Map 才是完整的。</p>
 */
@Slf4j
@Component
@Lazy(false)
@RequiredArgsConstructor
public class DictSeeder {

    /** 内置字典类型编码 —— 前端按这些码取字典，改动会同时影响前端。 */
    public static final String TYPE_LOG_LEVEL = "log_level";
    public static final String TYPE_LOG_CATEGORY = "log_category";
    public static final String TYPE_CHUNK_STRATEGY = "chunk_strategy";
    public static final String TYPE_BUILTIN_TOOL = "builtin_tool";
    /** 对话侧服务商：含 deepseek / anthropic。 */
    public static final String TYPE_MODEL_PROVIDER_CHAT = "model_provider_chat";
    /** 嵌入侧服务商：含 siliconflow / zhipu。 */
    public static final String TYPE_MODEL_PROVIDER_EMBEDDING = "model_provider_embedding";

    /** 当前全部内置类型。用于清理"曾经内置、现已下线"的类型，见 {@link #purgeRetiredTypes()}。 */
    private static final List<String> BUILTIN_TYPE_CODES = List.of(
            TYPE_LOG_LEVEL,
            TYPE_LOG_CATEGORY,
            TYPE_CHUNK_STRATEGY,
            TYPE_BUILTIN_TOOL,
            TYPE_MODEL_PROVIDER_CHAT,
            TYPE_MODEL_PROVIDER_EMBEDDING);

    private final SysDictTypeRepository typeRepository;
    private final SysDictItemRepository itemRepository;

    /** 真实注册的切分器（Bean 名 = 策略名）。 */
    private final Map<String, Chunker> chunkers;
    /** 真实存在的内置工具（非 Bean 的 MCP/HTTP/插件工具不在其中）。 */
    private final Map<String, Tool> builtinTools;

    @Value("${agent-platform.tenant.default-id:default}")
    private String defaultTenantId;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        int items = 0;

        items += seedType(TYPE_LOG_LEVEL, "日志级别", "运行日志的级别筛选（取自 LogLevel 枚举）",
                fromEnum(LogLevel.values(), LEVEL_LABELS));
        items += seedType(TYPE_LOG_CATEGORY, "日志类别", "日志采集来源分类（取自 LogCategory 枚举）",
                fromEnum(LogCategory.values(), CATEGORY_LABELS));
        items += seedType(TYPE_CHUNK_STRATEGY, "文档切分策略", "知识库文档切分方式（取自真实注册的 Chunker）",
                fromChunkers());
        items += seedType(TYPE_BUILTIN_TOOL, "内置工具", "Skill 可绑定的内置工具（取自 Spring 容器里的 Tool Bean）",
                fromTools());
        items += seedType(TYPE_MODEL_PROVIDER_CHAT, "对话模型服务商", "模型设置页「默认对话模型」可选的服务商",
                fromMap(CHAT_PROVIDERS));
        items += seedType(TYPE_MODEL_PROVIDER_EMBEDDING, "嵌入模型服务商", "模型设置页「嵌入模型」可选的服务商",
                fromMap(EMBEDDING_PROVIDERS));

        purgeRetiredTypes();

        log.info("[dict] 内置字典就绪：新增字典项 {} 条（已存在的类型与项一律跳过，不覆盖用户改过的标签）", items);
    }

    // ------------------------------------------------------------------ 内置定义

    /** 日志级别 → 中文标签。枚举本身没有显示名，所以标签只能在这里给。 */
    private static final Map<String, String> LEVEL_LABELS = new LinkedHashMap<>() {{
        put("TRACE", "跟踪");
        put("DEBUG", "调试");
        put("INFO", "信息");
        put("WARN", "警告");
        put("ERROR", "错误");
    }};

    private static final Map<String, String> CATEGORY_LABELS = new LinkedHashMap<>() {{
        put("agent", "智能体");
        put("llm", "大模型");
        put("plugin", "插件");
        put("skill", "技能");
        put("tool", "工具");
        put("api", "接口");
        put("workflow", "工作流");
        put("system", "系统");
    }};

    private static final Map<String, String> CHUNKER_LABELS = new LinkedHashMap<>() {{
        put("recursive", "递归切分");
        put("semantic", "语义切分");
        put("structural", "结构切分");
    }};

    private static final Map<String, String> TOOL_LABELS = new LinkedHashMap<>() {{
        put("search", "知识库检索");
        put("calc", "计算器");
    }};

    /*
     * 服务商没有"可枚举的单一来源"（适配器是手动装配的、provider() 是实例方法），只能手写。
     *
     * 为什么拆成**两个**字典而不是一个：两侧的可选集合并不相同 ——
     *   嵌入侧有 siliconflow / zhipu 但没有 deepseek / anthropic（那两家没有 embedding 接口）；
     *   对话侧反之。
     * 而字典的模型是"一个类型 = 一个扁平列表"，表达不了子集差异 ——
     * 那就**再建一个类型**，这比给字典加"分组"概念简单得多。
     */

    /** 对话侧服务商（与 ModelSettingsPage 原来的 CHAT_PROVIDERS 对齐）。 */
    private static final Map<String, String> CHAT_PROVIDERS = new LinkedHashMap<>() {{
        put("deepseek", "deepseek");
        put("openai", "openai");
        put("qwen", "qwen（通义千问）");
        put("ernie", "ernie（文心一言）");
        put("hunyuan", "hunyuan（混元）");
        put("anthropic", "anthropic（Claude / Bailian）");
        put("local", "local（本地 Mock）");
    }};

    /** 嵌入侧服务商（与 ModelSettingsPage 原来的 EMBEDDING_PROVIDERS 对齐）。 */
    private static final Map<String, String> EMBEDDING_PROVIDERS = new LinkedHashMap<>() {{
        put("siliconflow", "siliconflow（硅基流动）");
        put("openai", "openai");
        put("qwen", "qwen（通义千问）");
        put("ernie", "ernie（文心一言）");
        put("hunyuan", "hunyuan（混元）");
        put("zhipu", "zhipu（智谱）");
        put("local", "local（本地 Mock）");
    }};

    // ------------------------------------------------------------------ 取初值

    private <E extends Enum<E>> List<Map.Entry<String, String>> fromEnum(E[] values, Map<String, String> labels) {
        List<Map.Entry<String, String>> out = new ArrayList<>(values.length);
        for (E v : values) {
            String name = v.name();
            out.add(Map.entry(name, labels.getOrDefault(name, name)));
        }
        return out;
    }

    private List<Map.Entry<String, String>> fromChunkers() {
        List<Map.Entry<String, String>> out = new ArrayList<>(chunkers.size());
        // 用 TreeSet 排一遍：Bean 的注入顺序不保证，而字典的展示顺序应当稳定
        for (String name : new TreeSet<>(chunkers.keySet())) {
            out.add(Map.entry(name, CHUNKER_LABELS.getOrDefault(name, name)));
        }
        return out;
    }

    private List<Map.Entry<String, String>> fromTools() {
        List<Map.Entry<String, String>> out = new ArrayList<>(builtinTools.size());
        for (String name : new TreeSet<>(builtinTools.keySet())) {
            out.add(Map.entry(name, TOOL_LABELS.getOrDefault(name, name)));
        }
        return out;
    }

    private List<Map.Entry<String, String>> fromMap(Map<String, String> src) {
        return new ArrayList<>(src.entrySet());
    }

    // ------------------------------------------------------------------ 落库

    /**
     * 建类型（不存在时）并补齐缺失的字典项。
     *
     * @return 本次新增的字典项条数
     */
    private int seedType(String typeCode, String typeName, String remark,
                         List<Map.Entry<String, String>> items) {
        SysDictType type = typeRepository.findByTenantIdAndTypeCode(defaultTenantId, typeCode).orElse(null);
        if (type == null) {
            typeRepository.save(SysDictType.builder()
                    .dictTypeId(IdGenerator.generate("dict"))
                    .tenantId(defaultTenantId)
                    .typeCode(typeCode)
                    .typeName(typeName)
                    .remark(remark)
                    .status("active")
                    .builtin(true)
                    .build());
            log.info("[dict] 内置字典类型已创建：{}", typeCode);
        }

        Set<String> existing = new HashSet<>();
        for (SysDictItem i : itemRepository.findByTenantIdAndTypeCodeOrderBySortOrderAscItemValueAsc(
                defaultTenantId, typeCode)) {
            existing.add(i.getItemValue());
        }

        int added = 0;
        int order = 0;
        for (Map.Entry<String, String> e : items) {
            order += 10;
            // 已存在就跳过：不覆盖用户改过的标签（见类注释的"只补不覆盖"）
            if (existing.contains(e.getKey())) {
                continue;
            }
            itemRepository.save(SysDictItem.builder()
                    .dictItemId(IdGenerator.generate("dict_item"))
                    .tenantId(defaultTenantId)
                    .typeCode(typeCode)
                    .itemValue(e.getKey())
                    .itemLabel(e.getValue())
                    .sortOrder(order)
                    .status("active")
                    .build());
            added++;
        }
        return added;
    }

    /**
     * 清理"曾经是内置、但现在已不在 {@link #BUILTIN_TYPE_CODES} 里"的类型。
     *
     * <p>为什么需要它：内置清单是会变的（例如原先只有一个 {@code model_provider}，
     * 后来按对话/嵌入拆成两个）。旧类型若留在库里，管理界面会多出一份**没人再用**的字典，
     * 而用户不知道它能不能删。</p>
     *
     * <p><b>只清理 {@code builtin=true} 的</b> —— 用户自建的类型一律不动，那是用户的数据。
     * 与 {@code RbacSeeder.syncPermissions} 里"枚举里已删除的权限点顺带清掉"同一思路。</p>
     */
    private void purgeRetiredTypes() {
        List<SysDictType> all = typeRepository.findByTenantIdOrderByTypeCodeAsc(defaultTenantId);
        for (SysDictType t : all) {
            if (!Boolean.TRUE.equals(t.getBuiltin()) || BUILTIN_TYPE_CODES.contains(t.getTypeCode())) {
                continue;
            }
            itemRepository.deleteByTenantIdAndTypeCode(defaultTenantId, t.getTypeCode());
            typeRepository.delete(t);
            log.info("[dict] 内置字典类型 {} 已不在内置清单中，连同其字典项一并移除", t.getTypeCode());
        }
    }
}
