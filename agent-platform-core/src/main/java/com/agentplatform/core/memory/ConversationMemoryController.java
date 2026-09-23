package com.agentplatform.core.memory;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.security.rbac.RbacContext;
import com.agentplatform.core.security.rbac.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 向量记忆（历史对话语义召回）的运维接口。
 *
 * <p>只暴露两个"动作"接口，没有查询接口 —— 向量记忆不是给用户浏览的数据，
 * 它只是一段被召回的上下文。用户能看到并管理的是<strong>长期画像</strong>
 * （{@code /api/v1/memory/profile}），两者刻意分开：一个是用户声明的事实、
 * 一个是系统从对话里推断的近似，混在一个界面里会让人分不清"哪条是我说的"。</p>
 *
 * <p>身份同样取自 {@link RbacContext#userId()}，不接受入参（理由见
 * {@code UserFactController} 的类注释）。</p>
 */
@RestController
@RequestMapping("/api/v1/memory/vector")
@RequiredArgsConstructor
public class ConversationMemoryController {

    private final ConversationMemoryService service;

    /**
     * 重建该用户的向量索引，返回索引条数。
     *
     * <p><b>为什么需要这个接口</b>：默认的 in-memory 向量库是**进程内 Map**，
     * 桌面版每次重启都会清空 —— 没有重建入口的话，"向量记忆"在桌面版上永远是空的，
     * 用户会觉得这个功能根本没生效。Web 部署用 Milvus 时数据是持久的，
     * 只在索引丢失后才需要手动重建。</p>
     */
    @PostMapping("/rebuild")
    @RequiresPermission("profile:manage")
    public ApiResponse<Map<String, Object>> rebuild() {
        int indexed = service.rebuild(RbacContext.tenantId(), RbacContext.userId());
        return ApiResponse.ok(Map.of("indexed", indexed));
    }

    /**
     * 清除该用户的全部向量记忆，返回清除条数。
     *
     * <p>与长期画像的清除并列：向量记忆是从对话里自动抽出来的，
     * 用户更该有一个"别用我的历史对话"的开关。</p>
     */
    @DeleteMapping("/purge")
    @RequiresPermission("profile:manage")
    public ApiResponse<Map<String, Object>> purge() {
        int deleted = service.clearAll(RbacContext.tenantId(), RbacContext.userId());
        return ApiResponse.ok(Map.of("deleted", deleted));
    }
}
