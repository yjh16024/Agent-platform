package com.agentplatform.core.memory;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.security.rbac.RequiresPermission;
import com.agentplatform.core.security.rbac.RbacContext;
import com.agentplatform.model.entity.UserFact;
import com.agentplatform.model.entity.UserFactCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆：**自动抽取候选**的查看与确认（「用户确认」环节的接口层）。
 *
 * <p>挂在 {@code /api/v1/memory/profile/candidates} 之下，与 {@code UserFactController}
 * （正式画像）同属画像功能。路径更长且方法不重叠，不会与正式画像那边的
 * /profile/&#123;factId&#125; 冲突。</p>
 *
 * <h3>身份来自请求，不来自参数（与 UserFactController 同一约定）</h3>
 * 所有方法都<b>不接受</b> {@code userId} 入参，一律用 {@link RbacContext#userId()} ——
 * 它读的是 {@code JwtAuthFilter} 用 token 里的值覆盖写入的 {@code X-User-Id} 头，请求方伪造不了。
 * 若做成入参，A 就能确认/忽略 B 的候选，进而影响 B 的对话上下文。
 *
 * <p>权限码只回答"能不能用这个功能"，不回答"能看谁的" —— 后者由上述身份约定
 * 加仓储方法签名（查询必带 tenantId + userId）共同保证。</p>
 */
@RestController
@RequestMapping("/api/v1/memory/profile/candidates")
@RequiredArgsConstructor
public class UserFactCandidateController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserFactCandidateService service;

    /** 待确认列表（最新在前）。 */
    @GetMapping
    @RequiresPermission("profile:read")
    public ApiResponse<List<CandidateView>> list() {
        List<CandidateView> out = service.pending(RbacContext.tenantId(), RbacContext.userId())
                .stream().map(CandidateView::of).toList();
        return ApiResponse.ok(out);
    }

    /** 待确认条数（界面角标用；单独一个端点避免为拿计数而拉全量）。 */
    @GetMapping("/count")
    @RequiresPermission("profile:read")
    public ApiResponse<Map<String, Object>> count() {
        long n = service.countPending(RbacContext.tenantId(), RbacContext.userId());
        return ApiResponse.ok(Map.of("pending", n));
    }

    /**
     * 采纳一条候选：搬进正式画像（来源标 auto），随后会被注入对话上下文。
     *
     * <p>用 POST 而不是 PUT：这不是"修改候选"，而是触发一次搬迁动作
     * （候选被删除、正式画像被创建），语义上更接近命令。</p>
     */
    @PostMapping("/{candidateId}/adopt")
    @RequiresPermission("profile:manage")
    public ApiResponse<Map<String, Object>> adopt(@PathVariable String candidateId) {
        UserFact fact = service.adopt(RbacContext.tenantId(), RbacContext.userId(), candidateId);
        return ApiResponse.ok(Map.of("factId", fact.getFactId(), "key", fact.getFactKey()));
    }

    /**
     * 忽略一条候选。
     *
     * <p>记录**不会删除**，而是打上拒绝时间戳 —— 抽取侧据此跳过该键，
     * 避免下一轮对话又把同一件事抽出来重新打扰（详见服务类注释）。</p>
     */
    @PostMapping("/{candidateId}/reject")
    @RequiresPermission("profile:manage")
    public ApiResponse<Void> reject(@PathVariable String candidateId) {
        service.reject(RbacContext.tenantId(), RbacContext.userId(), candidateId);
        return ApiResponse.ok(null);
    }

    /** 采纳全部待确认候选。 */
    @PostMapping("/adopt-all")
    @RequiresPermission("profile:manage")
    public ApiResponse<Map<String, Object>> adoptAll() {
        int n = service.adoptAll(RbacContext.tenantId(), RbacContext.userId());
        return ApiResponse.ok(Map.of("adopted", n));
    }

    /** 忽略全部待确认候选（会记住这些键，不再重复抽取）。 */
    @PostMapping("/reject-all")
    @RequiresPermission("profile:manage")
    public ApiResponse<Map<String, Object>> rejectAll() {
        int n = service.rejectAll(RbacContext.tenantId(), RbacContext.userId());
        return ApiResponse.ok(Map.of("rejected", n));
    }

    /**
     * 一键清除全部候选 —— **含已忽略的记录**。
     *
     * <p>用独立的 {@code /purge} 路径而不是 {@code DELETE /}，理由与正式画像那边一致：
     * 后者与"忽略一条"只差路径参数，前端漏传就会清空整个待办区。</p>
     *
     * <p>连已忽略记录一起清掉是刻意的：用户说"别记我的事"时，
     * 留着"他曾经拒绝过 X"本身也是一种记录。</p>
     */
    @DeleteMapping("/purge")
    @RequiresPermission("profile:manage")
    public ApiResponse<Map<String, Object>> purge() {
        long deleted = service.clearAll(RbacContext.tenantId(), RbacContext.userId());
        return ApiResponse.ok(Map.of("deleted", deleted));
    }

    /**
     * 候选的对外视图。
     *
     * <p>不直接返回实体：实体带自增物理主键 {@code id}，对外只暴露业务 ID
     * {@code candidateId}（与 {@code UserFactController.UserFactView} 的处理一致）。</p>
     *
     * <p>带上 {@code sourceSessionId} / {@code extractedBy} 是为了**可解释**：
     * 用户看到一条系统猜的画像时，有权知道它是从哪儿、由哪个模型得出来的。</p>
     */
    public record CandidateView(String candidateId, String key, String value,
                                String category, String categoryLabel,
                                String sourceSessionId, String extractedBy,
                                String createdAt) {

        static CandidateView of(UserFactCandidate c) {
            return new CandidateView(
                    c.getCandidateId(),
                    c.getFactKey(),
                    c.getFactValue(),
                    c.getCategory() == null ? "other" : c.getCategory().name(),
                    c.getCategory() == null ? "其他" : c.getCategory().label(),
                    c.getSourceSessionId(),
                    c.getExtractedBy(),
                    c.getCreatedAt() == null ? null : c.getCreatedAt().format(FMT));
        }
    }
}
