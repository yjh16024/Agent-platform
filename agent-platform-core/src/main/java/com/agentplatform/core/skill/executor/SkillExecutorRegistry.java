package com.agentplatform.core.skill.executor;

import com.agentplatform.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 执行器注册表（命令模式的 Invoker：持有全部命令实现并按需分发）。
 * <p>
 * Spring 容器中的全部 {@link SkillExecutor} 实现会被自动收集（按类型索引）；
 * 执行时：显式指定 {@code type} 则精确命中，未指定则按 {@link SkillExecutor#supports}
 * 顺序探测，兜底使用 {@code prompt} 执行器（Skill 的最小语义即提示词）。
 * </p>
 */
@Slf4j
@Component
public class SkillExecutorRegistry {

    private static final String DEFAULT_TYPE = "prompt";

    private final Map<String, SkillExecutor> executors = new LinkedHashMap<>();

    /** 主构造（Spring 注入全部实现）——多构造时必须显式标注 @Autowired。 */
    @Autowired
    public SkillExecutorRegistry(List<SkillExecutor> beans) {
        if (beans != null) {
            beans.forEach(this::register);
        }
        log.info("[skills] 已注册 Skill 执行器: {}", executors.keySet());
    }

    /** 便捷构造（单测 / 手动装配）。 */
    public SkillExecutorRegistry() {
        this(List.of());
    }

    /** 注册执行器（同名覆盖）。 */
    public void register(SkillExecutor executor) {
        executors.put(executor.type(), executor);
    }

    /** 按类型获取。 */
    public SkillExecutor get(String type) {
        SkillExecutor e = executors.get(type == null ? "" : type.toLowerCase());
        if (e == null) {
            throw BizException.notFound("skill executor", type);
        }
        return e;
    }

    /**
     * 解析命令对应的执行器：显式 type 优先，否则按 supports 探测，最后兜底 prompt。
     */
    public SkillExecutor resolve(SkillCommand command) {
        if (command != null && command.type() != null && !command.type().isBlank()) {
            return get(command.type());
        }
        for (SkillExecutor e : executors.values()) {
            if (e.supports(command)) {
                return e;
            }
        }
        return get(DEFAULT_TYPE);
    }

    /** 已注册的执行器清单（类型 + 说明）。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        executors.forEach((k, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", k);
            row.put("description", v.description());
            rows.add(row);
        });
        return rows;
    }
}
