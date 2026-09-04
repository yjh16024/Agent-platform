package com.agentplatform.core.agent.service;

import com.agentplatform.common.dto.PageResult;
import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.agent.dto.AgentCreateRequest;
import com.agentplatform.core.agent.dto.AgentPatchRequest;
import com.agentplatform.core.agent.dto.AgentResponse;
import com.agentplatform.core.events.ConfigChangeEvent;
import com.agentplatform.core.events.EventBus;
import com.agentplatform.core.events.KafkaTopicConfig;
import com.agentplatform.core.model.secret.ModelBindingService;
import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.enums.AgentStatus;
import com.agentplatform.model.enums.Visibility;
import com.agentplatform.model.record.Capabilities;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.Persona;
import com.agentplatform.model.repository.AgentDefinitionRepository;
import com.agentplatform.model.repository.AgentPluginRepository;
import com.agentplatform.model.repository.AgentVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 智能体管理服务（CRUD + 配置合并）。
 * <p>
 * 智能体管理是「配置中心 + 注册中心」：配置即资产，改配置即改 Agent，
 * 每次 run 启动时按 agent_id 加载 EffectiveConfig 实现热更新。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentDefinitionRepository agentDefinitionRepository;
    private final AgentConfigValidator configValidator;
    private final ModelBindingService modelBindingService;

    /** 可选事件总线：配置变更广播（无 broker 时 null，静默跳过）。 */
    @Autowired(required = false)
    private EventBus eventBus;

    /** 可选仓储：级联清理用（单测未注入时跳过级联，保持既有构造签名）。 */
    @Autowired(required = false)
    private AgentVersionRepository agentVersionRepository;

    @Autowired(required = false)
    private AgentPluginRepository agentPluginRepository;

    /**
     * 创建智能体（返回 agent_id + draft 版本）。
     */
    @Transactional
    public AgentResponse create(AgentCreateRequest req, String tenantId) {
        String agentId = IdGenerator.generate("agent");

        AgentDefinition def = new AgentDefinition();
        def.setAgentId(agentId);
        def.setTenantId(resolveTenantId(req.tenantId(), tenantId));
        def.setName(req.name());
        def.setAvatar(req.avatar());
        def.setDescription(req.description());
        def.setPersona(req.persona() == null ? new Persona(null, null, null, null, null, null, null, null) : req.persona());
        def.setSystemPrompt(req.systemPrompt());
        def.setGenerationConfig(req.generationConfig() == null ? GenerationConfig.defaults() : req.generationConfig());
        def.setCapabilities(req.capabilities() == null ? Capabilities.empty() : req.capabilities());
        def.setModelBinding(modelBindingService.seal(req.modelBinding()));
        def.setStatus(AgentStatus.draft);
        def.setVisibility(req.visibility() == null ? Visibility.private_ : req.visibility());

        configValidator.validateOrThrow(def);

        def = agentDefinitionRepository.save(def);
        publishChange("created", def);
        log.info("Created agent {} [{}] by tenant {}", def.getName(), agentId, def.getTenantId());
        return toResponse(def);
    }

    /**
     * 全量更新（PUT）。
     */
    @Transactional
    public AgentResponse update(String agentId, AgentCreateRequest req, String tenantId) {
        AgentDefinition def = getOrThrow(tenantId, agentId);
        def.setName(req.name() == null ? def.getName() : req.name());
        def.setAvatar(req.avatar());
        def.setDescription(req.description());
        def.setPersona(req.persona() == null ? def.getPersona() : req.persona());
        def.setSystemPrompt(req.systemPrompt() == null ? def.getSystemPrompt() : req.systemPrompt());
        def.setGenerationConfig(req.generationConfig() == null ? def.getGenerationConfig() : req.generationConfig());
        def.setCapabilities(req.capabilities() == null ? def.getCapabilities() : req.capabilities());
        if (req.modelBinding() != null) {
            def.setModelBinding(modelBindingService.seal(req.modelBinding(), def.getModelBinding()));
        }
        if (req.visibility() != null) {
            def.setVisibility(req.visibility());
        }
        configValidator.validateOrThrow(def);
        def = agentDefinitionRepository.save(def);
        publishChange("updated", def);
        return toResponse(def);
    }

    /**
     * 局部更新（PATCH，仅传差异字段）。
     */
    @Transactional
    public AgentResponse patch(String agentId, AgentPatchRequest req, String tenantId) {
        AgentDefinition def = getOrThrow(tenantId, agentId);

        if (req.name() != null) {
            def.setName(req.name());
        }
        if (req.avatar() != null) {
            def.setAvatar(req.avatar());
        }
        if (req.description() != null) {
            def.setDescription(req.description());
        }
        if (req.persona() != null) {
            def.setPersona(mergePersona(def.getPersona(), req.persona()));
        }
        if (req.systemPrompt() != null) {
            def.setSystemPrompt(req.systemPrompt());
        }
        if (req.generationConfig() != null) {
            def.setGenerationConfig(def.getGenerationConfig().merge(req.generationConfig()));
        }
        if (req.capabilities() != null) {
            def.setCapabilities(req.capabilities());
        }
        if (req.modelBinding() != null) {
            def.setModelBinding(modelBindingService.seal(req.modelBinding(), def.getModelBinding()));
        }
        if (req.status() != null) {
            def.setStatus(AgentStatus.valueOf(req.status()));
        }
        if (req.visibility() != null) {
            def.setVisibility(Visibility.fromDb(req.visibility()));
        }

        configValidator.validateOrThrow(def);
        def = agentDefinitionRepository.save(def);
        publishChange("updated", def);
        return toResponse(def);
    }

    /**
     * 合并人格字段（PATCH 仅覆盖非空维度）。
     */
    private Persona mergePersona(Persona base, Persona patch) {
        return new Persona(
                patch.tone() != null ? patch.tone() : base.tone(),
                patch.style() != null ? patch.style() : base.style(),
                patch.role() != null ? patch.role() : base.role(),
                patch.warmth() != null ? patch.warmth() : base.warmth(),
                patch.expertise() != null ? patch.expertise() : base.expertise(),
                patch.proactiveness() != null ? patch.proactiveness() : base.proactiveness(),
                patch.catchphrases() != null ? patch.catchphrases() : base.catchphrases(),
                patch.forbidden() != null ? patch.forbidden() : base.forbidden()
        );
    }

    /**
     * 获取详情（含 EffectiveConfig）。
     */
    @Transactional(readOnly = true)
    public AgentResponse get(String agentId, String tenantId) {
        return toResponse(getOrThrow(tenantId, agentId));
    }

    /**
     * 分页查询。
     */
    @Transactional(readOnly = true)
    public PageResult<AgentResponse> list(String tenantId, String q, AgentStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<AgentDefinition> result = agentDefinitionRepository.search(tenantId, q, status, pageable);
        return PageResult.from(result, this::toResponse);
    }

    /**
     * 删除智能体（物理删除 + 级联清理版本快照与插件绑定）。
     * <p>
     * <b>为什么改为物理删除</b>：原先只把 status 置为 {@code archived}，而列表查询
     * 默认不过滤状态，导致「提示已归档，但页面上仍然看得见、还能操作」。
     * 用户点删除的预期就是"删掉"，因此这里做物理删除；历史版本快照与插件绑定
     * 随之级联清理，避免留下孤儿数据。会话消息保留（属于用户对话资产）。
     * </p>
     */
    @Transactional
    public void delete(String agentId, String tenantId) {
        AgentDefinition def = getOrThrow(tenantId, agentId);
        if (agentVersionRepository != null) {
            agentVersionRepository.deleteByAgentId(agentId);
        }
        if (agentPluginRepository != null) {
            agentPluginRepository.deleteByAgentId(agentId);
        }
        agentDefinitionRepository.delete(def);
        publishChange("deleted", def);
        log.info("Deleted agent {} [{}]", def.getName(), agentId);
    }

    /**
     * 克隆（复制为新 Agent）。
     */
    @Transactional
    public AgentResponse clone(String agentId, String tenantId) {
        AgentDefinition src = getOrThrow(tenantId, agentId);
        AgentDefinition copy = new AgentDefinition();
        BeanUtils.copyProperties(src, copy, "id", "agentId", "currentVersion", "createdAt", "updatedAt", "status");
        copy.setId(null);
        copy.setAgentId(IdGenerator.generate("agent"));
        copy.setName(src.getName() + " (clone)");
        copy.setStatus(AgentStatus.draft);
        copy.setCurrentVersion(null);
        copy.setModelBinding(null);   // 克隆体不继承原智能体的模型密钥
        copy = agentDefinitionRepository.save(copy);
        publishChange("cloned", copy);
        return toResponse(copy);
    }

    /**
     * 更新当前版本号（发布/回滚时调用）。
     */
    @Transactional
    public void updateCurrentVersion(String agentId, String tenantId, String version) {
        AgentDefinition def = getOrThrow(tenantId, agentId);
        def.setCurrentVersion(version);
        if (version != null) {
            def.setStatus(AgentStatus.published);
        }
        agentDefinitionRepository.save(def);
        publishVersionChange(version == null ? "unpublished" : "published", def, version);
    }

    /**
     * 更新能力绑定（插件挂载/卸载时调用）。
     */
    @Transactional
    public void updateCapabilities(String tenantId, String agentId, Capabilities capabilities) {
        AgentDefinition def = getOrThrow(tenantId, agentId);
        def.setCapabilities(capabilities);
        agentDefinitionRepository.save(def);
    }

    /**
     * 获取实体（含校验与异常）。
     */
    public AgentDefinition getOrThrow(String tenantId, String agentId) {
        return agentDefinitionRepository.findByTenantIdAndAgentId(tenantId, agentId)
                .orElseThrow(() -> BizException.notFound("agent", agentId));
    }

    /**
     * 转为响应 DTO。
     */
    public AgentResponse toResponse(AgentDefinition def) {
        return new AgentResponse(
                def.getAgentId(),
                def.getTenantId(),
                def.getName(),
                def.getAvatar(),
                def.getDescription(),
                def.getPersona(),
                def.getSystemPrompt(),
                def.getGenerationConfig(),
                def.getCapabilities(),
                modelBindingService.view(def.getModelBinding()),
                def.getStatus() == null ? null : def.getStatus().name(),
                def.getVisibility() == null ? null : def.getVisibility().toDb(),
                def.getCurrentVersion(),
                List.of(),   // 插件贡献能力在 Phase 4 填充
                AgentResponse.ValidationResult.pass(),
                def.getCreatedAt(),
                def.getUpdatedAt()
        );
    }

    private String resolveTenantId(String reqTenantId, String defaultTenantId) {
        return reqTenantId != null ? reqTenantId : defaultTenantId;
    }

    /**
     * 广播配置变更事件（尽力而为，总线缺失/不可用时静默）。
     */
    private void publishChange(String event, AgentDefinition def) {
        publishVersionChange(event, def, null);
    }

    private void publishVersionChange(String event, AgentDefinition def, String version) {
        if (eventBus == null) {
            return;
        }
        try {
            eventBus.publish(KafkaTopicConfig.CONFIG_TOPIC, def.getAgentId(),
                    new ConfigChangeEvent(event, def.getTenantId(), def.getAgentId(), def.getName(), version));
        } catch (Exception ignored) {
            // 事件广播失败不影响主流程
        }
    }
}