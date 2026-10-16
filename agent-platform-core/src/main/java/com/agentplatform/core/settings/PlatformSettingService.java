package com.agentplatform.core.settings;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.settings.PlatformSettingDtos.SettingGroup;
import com.agentplatform.core.settings.PlatformSettingDtos.SettingItem;
import com.agentplatform.core.settings.PlatformSettingDtos.SettingsView;
import com.agentplatform.core.settings.PlatformSettingDtos.UpdateResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 平台设置的读写服务 —— 「平台设置」页的后端，也是各读点在**运行期**取值的来源。
 *
 * <h3>值的两层来源（这是理解本类的关键）</h3>
 * <pre>
 *   覆盖层（本文件 data/platform-settings.properties）  ← 「平台设置」页写入
 *        ↓ 没有则回落
 *   基础层（Environment：环境变量 > 系统属性 > 命令行 > 配置文件）
 *        ↓ 都没有则回落
 *   注册表 fallback（与 application.yml / @Value 的默认一致）
 * </pre>
 * <ul>
 *   <li>「当前值」= 覆盖层 ?? 基础层 ?? fallback；</li>
 *   <li>「恢复默认」= 从覆盖文件删掉那一行（基础层是运维的地盘，页面不碰它）；</li>
 *   <li><b>运维的环境变量优先级始终高于页面</b> —— 它们既是救命通道（线上临时回退），
 *       也是本功能的安全边界（页面写错了，一句 {@code SPRING_AI_ENABLED=false} 就能压回去）。</li>
 * </ul>
 *
 * <h3>★ 读「基础层」时为什么要排除两个 PropertySource（见 {@link #SKIPPED_SOURCES}）</h3>
 * {@link PlatformSettingsInitializer} 会把覆盖文件注入 Environment（供装配期读取），
 * 而如果这里直接 {@code environment.getProperty(key)}，读到的将是**被自己写过的值**，
 * 于是"恢复默认后仍显示旧值""覆盖与基础分不清"。更隐蔽的是：**只跳过注入源还不够** ——
 * Spring Boot 的 {@code configurationProperties} 合并投影源排在最前面、且会把注入层的值
 * 再暴露一次（名字不同，跳过检查形同虚设）。两处都要跳，{@link #base(String)} 才算真的读到
 * "页面之外的配置"。2026-10-15 在真实实例上复现过这个双源问题，单测里已补回归用例。
 *
 * <h3>生效时机（与注册表的分工）</h3>
 * 覆盖写入后：
 * <ul>
 *   <li>{@code restartRequired=false} 的项：**立即生效** —— 读点每次执行时都经过本服务
 *       （见各读点里的 {@code enabled()} 方法），覆盖层已是新值；</li>
 *   <li>{@code restartRequired=true} 的项：写入成功但**不生效**，接口在
 *       {@code restartRequiredKeys} 里如实返回，由界面弹窗告知"需重启"。</li>
 * </ul>
 */
@Slf4j
@Service
public class PlatformSettingService {

    /** 用 ConfigurableEnvironment 而不是 Environment：{@link #base} 需要遍历 PropertySource 列表。 */
    private final ConfigurableEnvironment environment;
    private final Path file;

    /**
     * 覆盖层快照。写操作整体替换（不原地修改），读操作无锁 ——
     * 与 {@code ModelConfigService.cached} 同一模式，但更轻（不可变 Map）。
     */
    private volatile Map<String, String> overrides;

    public PlatformSettingService(ConfigurableEnvironment environment,
                                  @Value("${agent-platform.settings.file:./data/platform-settings.properties}") String filePath) {
        this.environment = environment;
        this.file = Path.of(filePath).toAbsolutePath().normalize();
        this.overrides = new LinkedHashMap<>(PlatformSettingsFile.load(this.file));
        if (!this.overrides.isEmpty()) {
            log.info("[settings] 平台设置覆盖已加载：{} 项（{}）", this.overrides.size(), this.file);
        }
    }

    // ------------------------------------------------------------------ 运行期读取（供各读点调用）

    /** 布尔值：覆盖层 ?? 基础层 ?? fallback。 */
    public boolean bool(String key, boolean fallback) {
        String v = raw(key);
        return v == null ? fallback : "true".equalsIgnoreCase(v.trim());
    }

    /** 整数值：解析失败回落 fallback（坏值不该让读点炸）。 */
    public long longValue(String key, long fallback) {
        String v = raw(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 逗号分隔清单（保留顺序、去空白、丢空项）。 */
    public List<String> list(String key) {
        String v = raw(key);
        if (v == null || v.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 当前生效的原始字符串值；没有任何来源时返回 null。 */
    public String raw(String key) {
        String ov = overrides.get(key);
        if (ov != null) {
            return ov;
        }
        Object base = base(key);
        return base == null ? null : String.valueOf(base);
    }

    // ------------------------------------------------------------------ 视图与写入（供接口调用）

    /** 完整视图（按注册表分组）。 */
    public SettingsView view() {
        Map<String, List<SettingItem>> byGroup = new LinkedHashMap<>();
        for (PlatformSettingRegistry.SettingDef def : PlatformSettingRegistry.definitions()) {
            byGroup.computeIfAbsent(def.group(), g -> new ArrayList<>()).add(item(def));
        }
        List<SettingGroup> groups = byGroup.entrySet().stream()
                .map(e -> new SettingGroup(e.getKey(), List.copyOf(e.getValue())))
                .toList();
        return new SettingsView(runtimeMode(), groups);
    }

    /**
     * 写入若干项并持久化。
     *
     * @return 新视图 + **本次因值变化而需要重启的项**（只含 restartRequired 且值确实变了的键；
     *         界面据此弹窗 —— 只标"需重启"而不管值有没有变，会让重复保存也弹窗）
     */
    public synchronized UpdateResult apply(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw BizException.badRequest("没有要保存的设置项");
        }
        Map<String, String> before = snapshot(values.keySet());
        Map<String, String> next = new LinkedHashMap<>(overrides);
        for (Map.Entry<String, String> e : values.entrySet()) {
            PlatformSettingRegistry.SettingDef def = require(e.getKey());
            requireEditable(def);
            next.put(def.key(), normalize(def, e.getValue()));
        }
        persist(next);
        this.overrides = next;
        return result(before, values.keySet());
    }

    /** 恢复若干项为配置文件 / 环境变量的值（= 删掉覆盖层里的键）。 */
    public synchronized UpdateResult reset(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw BizException.badRequest("没有要恢复的设置项");
        }
        Map<String, String> before = snapshot(keys);
        Map<String, String> next = new LinkedHashMap<>(overrides);
        for (String key : keys) {
            PlatformSettingRegistry.SettingDef def = require(key);
            requireEditable(def);
            next.remove(def.key());
        }
        persist(next);
        this.overrides = next;
        return result(before, keys);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 读「基础层」时必须跳过的源。
     *
     * <p><b>两个都要跳，缺一个就会读到"自己写过的值"（2026-10-15 线上复现的 bug）</b>：</p>
     * <ol>
     *   <li>{@code platformSettings} —— 覆盖文件注入层，是我们自己写的；</li>
     *   <li>{@code configurationProperties} —— Spring Boot 的**合并投影源**
     *       （{@code ConfigurationPropertySources.attach} 插在最前面，用于 @ConfigurationProperties 绑定）。
     *       它会把**所有底层源**的属性合并暴露出来，**包括我们注入的那一层** ——
     *       只跳过 ① 是不够的：投影源换个名字，值照旧漏出来。</li>
     * </ol>
     * <p>为什么单测当初没抓到：{@code MockEnvironment} 里没有这个投影源（它由真实启动流程插入），
     * 所以"只跳过 ①"在单测里全绿、一上真实环境下就表现为
     * 「恢复默认后当前值仍是旧值、且不提示重启」——见
     * {@code PlatformSettingServiceTest.baseSkipsConfigurationPropertiesProjection}。</p>
     */
    /** Spring Boot 合并投影源的约定名（{@code ConfigurationPropertySources.attach} 插入；其常量在 Boot 4.x 是 private）。 */
    private static final String CONFIGURATION_PROPERTIES_SOURCE = "configurationProperties";

    private static final Set<String> SKIPPED_SOURCES = Set.of(
            PlatformSettingsInitializer.SOURCE_NAME,
            CONFIGURATION_PROPERTIES_SOURCE);

    /**
     * 基础层取值：遍历 Environment 的 PropertySource，**跳过 {@link #SKIPPED_SOURCES}**。
     * 顺序即优先级（Spring 的约定：越靠前越优先），因此环境变量 / 命令行天然压过配置文件。
     */
    private Object base(String key) {
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (SKIPPED_SOURCES.contains(ps.getName())) {
                continue;
            }
            try {
                if (ps.containsProperty(key)) {
                    return resolvePlaceholders(ps.getProperty(key));
                }
            } catch (RuntimeException ignored) {
                // 个别 source（如 random）对任意键都会尝试求值并可能抛错 —— 视为未命中
            }
        }
        return null;
    }

    /**
     * 解析配置源里的占位符原文。
     *
     * <p><b>为什么必须自己做这一步</b>：application.yml 里写的是
     * {@code enabled: ${MEMORY_AUTO_PROFILE_ENABLED:false}} —— 配置源里存的是**未解析的原文**，
     * 解析它本来是 {@code environment.getProperty(key)} 的职责；而 {@link #base} 为了跳过注入源
     * 是直接读 PropertySource 的，于是这个职责就落到了这里。
     * 漏掉的表现是「当前值」显示成 {@code ${MEMORY_AUTO_PROFILE_ENABLED:false}} 这种原文 ——
     * 页面看起来像坏了，还会直接误导用户（2026-10-15 实测踩中）。</p>
     */
    private Object resolvePlaceholders(Object raw) {
        if (!(raw instanceof String s) || !s.contains("${")) {
            return raw;
        }
        try {
            return environment.resolvePlaceholders(s);
        } catch (RuntimeException e) {
            return s;   // 解析失败（如循环引用）宁可显示原文，也不让整个视图请求失败
        }
    }

    private String effective(PlatformSettingRegistry.SettingDef def) {
        String ov = overrides.get(def.key());
        if (ov != null) {
            return ov;
        }
        Object base = base(def.key());
        if (base != null) {
            return String.valueOf(base);
        }
        return def.fallback();
    }

    private SettingItem item(PlatformSettingRegistry.SettingDef def) {
        boolean locked = isLocked(def);
        return new SettingItem(
                def.key(),
                def.label(),
                def.description(),
                def.type().name(),
                effective(def),
                def.options() == null ? List.of() : def.options(),
                def.restartRequired(),
                overrides.containsKey(def.key()),
                locked,
                locked ? def.lockedReason() : null);
    }

    private PlatformSettingRegistry.SettingDef require(String key) {
        PlatformSettingRegistry.SettingDef def = PlatformSettingRegistry.find(key);
        if (def == null) {
            throw BizException.badRequest("未知设置项：" + key + "（页面可能已过期，请刷新后重试）");
        }
        return def;
    }

    private void requireEditable(PlatformSettingRegistry.SettingDef def) {
        if (isLocked(def)) {
            throw BizException.badRequest("「" + def.label() + "」在当前环境被强制锁定，不允许修改。"
                    + def.lockedReason());
        }
    }

    /** 当前环境是否强制锁定该项（目前只有桌面 / 内置库模式下的 Kafka 事件总线）。 */
    private boolean isLocked(PlatformSettingRegistry.SettingDef def) {
        return def.lockedReason() != null && isEmbedded();
    }

    private boolean isEmbedded() {
        for (String p : environment.getActiveProfiles()) {
            if ("embedded".equals(p)) {
                return true;
            }
        }
        return false;
    }

    private String runtimeMode() {
        return isEmbedded() ? "embedded" : "server";
    }

    /**
     * 类型校验 + 标准化。
     * <p>校验放在写入侧而不是读取侧：页面写进来的值会被各读点直接使用，
     * 必须在入口挡住脏值（否则错误会以"开关时灵时不灵"的形式散到各处）。</p>
     */
    private String normalize(PlatformSettingRegistry.SettingDef def, String rawValue) {
        String v = rawValue == null ? "" : rawValue.trim();
        if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            throw BizException.badRequest("「" + def.label() + "」的值不能包含换行符");
        }
        if (v.length() > 2000) {
            throw BizException.badRequest("「" + def.label() + "」的值过长（上限 2000 字符）");
        }
        switch (def.type()) {
            case BOOL -> {
                if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                    throw BizException.badRequest("「" + def.label() + "」只接受 true / false");
                }
                return v.toLowerCase();
            }
            case NUMBER -> {
                long n;
                try {
                    n = Long.parseLong(v);
                } catch (NumberFormatException e) {
                    throw BizException.badRequest("「" + def.label() + "」需要一个整数");
                }
                if (n < 0) {
                    throw BizException.badRequest("「" + def.label() + "」不能为负数");
                }
                return String.valueOf(n);
            }
            case ENUM -> {
                if (def.options() == null || !def.options().contains(v)) {
                    throw BizException.badRequest("「" + def.label() + "」只接受：" + String.join(" / ",
                            def.options() == null ? List.of() : def.options()));
                }
                return v;
            }
            case LIST -> {
                return v;   // 逗号分隔清单：空串 = 清空（合法），逐项内容不做白名单（只是内存比对，无注入面）
            }
        }
        throw BizException.internal("未支持的类型：" + def.type());
    }

    /** 操作前的 effective 快照（仅对关心的键）。 */
    private Map<String, String> snapshot(Collection<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : keys) {
            PlatformSettingRegistry.SettingDef def = PlatformSettingRegistry.find(key);
            if (def != null) {
                out.put(key, effective(def));
            }
        }
        return out;
    }

    /** 组装写入结果：新视图 + 值确实变化且需重启的键。 */
    private UpdateResult result(Map<String, String> before, Collection<String> keys) {
        List<String> restartKeys = new ArrayList<>();
        for (String key : keys) {
            PlatformSettingRegistry.SettingDef def = PlatformSettingRegistry.find(key);
            if (def == null || !def.restartRequired()) {
                continue;
            }
            if (!Objects.equals(before.get(key), effective(def))) {
                restartKeys.add(key);
            }
        }
        return new UpdateResult(view(), List.copyOf(restartKeys));
    }

    private void persist(Map<String, String> next) {
        try {
            PlatformSettingsFile.save(file, next);
        } catch (IOException e) {
            // 文件写失败**不改内存**（抛异常即中止，overrides 保持旧值）——
            // "内存里变了、文件里没变"会让下次重启回退，用户以为是随机失灵
            throw BizException.internal("平台设置写入失败：" + e.getMessage(), e);
        }
        log.info("[settings] 平台设置已更新（{} 项覆盖）：{}", next.size(), file);
    }
}
