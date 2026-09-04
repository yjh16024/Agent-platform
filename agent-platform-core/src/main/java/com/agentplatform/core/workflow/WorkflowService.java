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