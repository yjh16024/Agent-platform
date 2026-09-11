package com.agentplatform.core.workflow;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.workflow.dag.DagEngine;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.core.workflow.schema.WorkflowSchemaValidator;
import com.agentplatform.model.entity.WorkflowDef;
import com.agentplatform.model.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工作流服务（编排画布 API 后端：定义 CRUD + 执行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowRepository repository;
    private final WorkflowSchemaValidator validator;
    private final DagEngine dagEngine;

    /**
     * 创建/保存工作流定义（校验后落库）。
     */
    @Transactional
    public WorkflowDef create(String tenantId, String name, String description, WorkflowDefinition definition) {
        validator.validate(definition);
        WorkflowDef def = WorkflowDef.builder()
                .workflowId(IdGenerator.generate("wf"))
                .tenantId(tenantId)
                .name(name)
                .description(description)
                .definition(JsonUtils.mapper().convertValue(definition, Map.class))
                .status("draft")
                .build();
        return repository.save(def);
    }

    /**
     * 更新工作流定义（画布「保存」入口）。
     * <p>校验后覆盖 definition / name / description；workflowId、status、createdAt 不变。</p>
     */
    @Transactional
    public WorkflowDef update(String tenantId, String workflowId, String name, String description,
                              WorkflowDefinition definition) {
        validator.validate(definition);
        WorkflowDef def = getOrThrow(tenantId, workflowId);
        if (name != null && !name.isBlank()) {
            def.setName(name);
        }
        def.setDescription(description);
        def.setDefinition(JsonUtils.mapper().convertValue(definition, Map.class));
        log.info("Updated workflow {} [{}] with {} nodes", def.getName(), workflowId,
                definition.nodes() == null ? 0 : definition.nodes().size());
        return repository.save(def);
    }

    /**
     * 发布工作流：把当前草稿 definition 存为已发布快照并递增版本号。
     * <p>发布后线上执行仍走 {@code definition}（可继续编辑草稿），
     * 已发布快照用于回滚 —— 与 Agent 的「版本快照 + 发布」语义一致。</p>
     */
    @Transactional
    public WorkflowDef publish(String tenantId, String workflowId) {
        WorkflowDef def = getOrThrow(tenantId, workflowId);
        WorkflowDefinition current = JsonUtils.mapper().convertValue(def.getDefinition(), WorkflowDefinition.class);
        validator.validate(current);
        def.setPublishedDefinition(def.getDefinition());
        def.setPublishedVersion(nextVersion(def.getPublishedVersion()));
        def.setPublishedAt(LocalDateTime.now());
        def.setStatus("published");
        log.info("Published workflow {} [{}] as {}", def.getName(), workflowId, def.getPublishedVersion());
        return repository.save(def);
    }

    /**
     * 回滚：把 definition 恢复为已发布快照（无快照时拒绝）。
     */
    @Transactional
    public WorkflowDef rollback(String tenantId, String workflowId) {
        WorkflowDef def = getOrThrow(tenantId, workflowId);
        if (def.getPublishedDefinition() == null) {
            throw BizException.validation("Workflow has no published version to roll back to");
        }
        def.setDefinition(def.getPublishedDefinition());
        log.info("Rolled back workflow {} [{}] to {}", def.getName(), workflowId, def.getPublishedVersion());
        return repository.save(def);
    }

    /** v1.0.0 → v1.0.1（无历史时从 v1.0.0 起）。 */
    private String nextVersion(String current) {
        if (current == null || current.isBlank()) {
            return "v1.0.0";
        }
        try {
            String[] parts = current.replace("v", "").split("\\.");
            int patch = Integer.parseInt(parts[2]) + 1;
            return "v" + parts[0] + "." + parts[1] + "." + patch;
        } catch (RuntimeException e) {
            return "v1.0.0";
        }
    }

    /**
     * 列工作流。
     */
    @Transactional(readOnly = true)
    public List<WorkflowDef> list(String tenantId) {
        return repository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    }

    /**
     * 获取工作流定义。
     */
    @Transactional(readOnly = true)
    public WorkflowDefinition get(String tenantId, String workflowId) {
        WorkflowDef def = getOrThrow(tenantId, workflowId);
        return JsonUtils.mapper().convertValue(def.getDefinition(), WorkflowDefinition.class);
    }

    /**
     * 执行工作流。
     */
    public Map<String, Object> execute(String tenantId, String workflowId, Map<String, Object> input) {
        WorkflowDefinition definition = get(tenantId, workflowId);
        WorkflowContext ctx = dagEngine.execute(definition, input);
        return ctx.all();
    }

    /**
     * 调试执行：返回最终变量 + 每个节点的执行轨迹（画布「调试面板」用）。
     *
     * @return {@code {variables: {...}, steps: [{nodeId, type, name, status, durationMs, output, error}]}}
     */
    public Map<String, Object> debug(String tenantId, String workflowId, Map<String, Object> input) {
        WorkflowDefinition definition = get(tenantId, workflowId);
        DagEngine.TracedResult traced = dagEngine.executeWithTrace(definition, input);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("variables", traced.context().all());
        out.put("steps", traced.steps());
        return out;
    }

    /**
     * 删除工作流（物理删除）。
     * <p>原先仅置 status=archived，而列表查询不过滤状态，导致「删除后仍可见、仍可操作」。
     * 用户点删除的预期是删掉，因此改为物理删除。</p>
     */
    @Transactional
    public void delete(String tenantId, String workflowId) {
        WorkflowDef def = getOrThrow(tenantId, workflowId);
        repository.delete(def);
        log.info("Deleted workflow {} [{}]", def.getName(), workflowId);
    }

    private WorkflowDef getOrThrow(String tenantId, String workflowId) {
        return repository.findByTenantIdAndWorkflowId(tenantId, workflowId)
                .orElseThrow(() -> BizException.notFound("workflow", workflowId));
    }
}