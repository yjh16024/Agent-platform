package com.agentplatform.core.memory;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.security.rbac.RbacContext;
import com.agentplatform.core.security.rbac.RequiresPermission;
import com.agentplatform.model.entity.UserFact;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆（个人画像）接口。
 *
 * <h3>身份来自请求，不来自参数（重要）</h3>
 * 所有方法都<b>不接受</b> {@code userId} 入参，一律用 {@link RbacContext#userId()} ——
 * 它读的是 {@code JwtAuthFilter} 用 token 里的值**覆盖写入**的 {@code X-User-Id} 头，
 * 请求方无法伪造。若做成入参，A 就能改 B 的画像（而画像会进 B 的对话上下文，
 * 等于一种注入攻击）。
 *
 * <p>权限码只回答"能不能用画像功能"，不回答"能看谁的画像" —— 后者由上面这条身份约定
 * 加仓储方法签名（查询必带 tenantId + userId）共同保证，与通知模块的处理一致。</p>
 */
@RestController
@RequestMapping("/api/v1/memory/profile")
@RequiredArgsConstructor
public class UserFactController {

    private final UserFactService service;

    /** 查询当前用户的全部画像。 */
    @GetMapping
    @RequiresPermission("profile:read")
    public ApiResponse<List<UserFactView>> list() {
        List<UserFactView> out = service.list(RbacContext.tenantId(), RbacContext.userId())
                .stream().map(UserFactView::of).toList();
        return ApiResponse.ok(out);
    }

    /**
     * 新增或更新一条画像（按 {@code key} upsert）。
     *
     * <p>用 POST 承载 upsert 而不是把"新增/编辑"拆成两个接口：前端那边用户看到的
     * 就是一个表单，保存时不必先判断"这条是新的还是已有的"。</p>
     */
    @PostMapping
    @RequiresPermission("profile:manage")
    public ApiResponse<UserFactView> save(@RequestBody ProfileRequest req) {
        UserFact saved = service.save(RbacContext.tenantId(), RbacContext.userId(),
                req == null ? null : req.key(),
                req == null ? null : req.value(),
                req == null ? null : req.category());
        return ApiResponse.ok(UserFactView.of(saved));
    }

    /** 更新指定画像（可改键名 / 内容 / 分类，值为 null 表示不改）。 */
    @PutMapping("/{factId}")
    @RequiresPermission("profile:manage")
    public ApiResponse<UserFactView> update(@PathVariable String factId, @RequestBody ProfileRequest req) {
        UserFact updated = service.update(RbacContext.tenantId(), RbacContext.userId(), factId,
                req == null ? null : req.key(),
                req == null ? null : req.value(),
                req == null ? null : req.category());
        return ApiResponse.ok(UserFactView.of(updated));
    }

    /** 删除一条画像。 */
    @DeleteMapping("/{factId}")
    @RequiresPermission("profile:manage")
    public ApiResponse<Void> delete(@PathVariable String factId) {
        service.delete(RbacContext.tenantId(), RbacContext.userId(), factId);
        return ApiResponse.ok(null);
    }

    /**
     * 一键清除全部画像（用户的隐私权利）。
     *
     * <p>刻意用独立的 {@code /purge} 路径而不是 {@code DELETE /}：后者与
     * "删一条"只差一个路径参数，前端漏传 ID 就会把整个画像清空 —— 这种后果
     * 不该由一个省略参数的动作触发。Spring 精确路径优先，两者不会冲突。</p>
     */
    @DeleteMapping("/purge")
    @RequiresPermission("profile:manage")
    public ApiResponse<Map<String, Object>> purge() {
        long deleted = service.clearAll(RbacContext.tenantId(), RbacContext.userId());
        return ApiResponse.ok(Map.of("deleted", deleted));
    }

    /**
     * 画像的对外视图。
     *
     * <p>不直接返回实体：实体带自增物理主键 {@code id}，对外只应暴露业务 ID
     * {@code factId}（与项目里通知/审计的处理一致）。</p>
     */
    public record UserFactView(String factId, String key, String value,
                               String category, String categoryLabel,
                               String source, String sourceLabel,
                               String createdAt, String updatedAt) {

        static UserFactView of(UserFact f) {
            return new UserFactView(
                    f.getFactId(), f.getFactKey(), f.getFactValue(),
                    f.getCategory() == null ? null : f.getCategory().name(),
                    f.getCategory() == null ? null : f.getCategory().label(),
                    f.getSource() == null ? null : f.getSource().name(),
                    f.getSource() == null ? null : f.getSource().label(),
                    f.getCreatedAt() == null ? null : f.getCreatedAt().toString(),
                    f.getUpdatedAt() == null ? null : f.getUpdatedAt().toString());
        }
    }

    /** 写入请求（新增与更新共用；更新时字段为 null 表示不改）。 */
    public record ProfileRequest(String key, String value, String category) {
    }
}
