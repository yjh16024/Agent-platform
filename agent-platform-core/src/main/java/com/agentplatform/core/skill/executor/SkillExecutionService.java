package com.agentplatform.core.skill.executor;

import com.agentplatform.core.skill.SkillService;
import com.agentplatform.model.entity.SkillDef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Skill 执行门面（Invoker 入口）：装配命令对象 → 由注册表选择执行器 → 执行并返回结果。
 * <p>
 * 调用方（Controller / 运行时）只依赖本服务与 {@link SkillExecutionResult}，
 * 不感知任何具体执行器实现；新增执行器仅影响注册表，不改本类。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillExecutionService {

    private final SkillService skillService;
    private final SkillExecutorRegistry registry;

    /**
     * 执行一个 Skill 命令。
     *
     * @param tenantId 租户
     * @param skillId  Skill ID
     * @param type     执行器类型（可空，空则由注册表推断）
     * @param command  命令（脚本路径 / HTTP 端点 / 提示词变量，可空）
     * @param args     入参
     */
    public SkillExecutionResult run(String tenantId, String skillId, String type,
                                    String command, Map<String, Object> args) {
        SkillDef def = skillService.get(tenantId, skillId);
        SkillCommand cmd = SkillCommand.of(tenantId, skillId, def.getName(), dirOf(def),
                type, command, args, def.getPromptTemplate());
        SkillExecutor executor = registry.resolve(cmd);
        SkillExecutionResult result = executor.execute(cmd);
        log.info("[skills] execute skill={} type={} success={} {}ms",
                skillId, executor.type(), result.success(), result.durationMs());
        return result;
    }

    /** 可用执行器清单。 */
    public List<Map<String, Object>> executors() {
        return registry.list();
    }

    private String dirOf(SkillDef def) {
        if (def.getManifest() == null) {
            return null;
        }
        Object v = def.getManifest().get("dir");
        return v == null ? null : String.valueOf(v);
    }
}
