package com.agentplatform.core.session;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.common.dto.PageResult;
import com.agentplatform.core.session.SessionDtos.CreateSessionRequest;
import com.agentplatform.core.session.SessionDtos.MessageDto;
import com.agentplatform.core.session.SessionDtos.SessionDetail;
import com.agentplatform.core.session.SessionDtos.SessionSummary;
import com.agentplatform.core.session.SessionDtos.UpdateSessionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话管理接口（RESTful，camelCase）。
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    /** 创建会话。 */
    @PostMapping
    public ApiResponse<SessionSummary> create(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody(required = false) CreateSessionRequest req) {
        return ApiResponse.ok(sessionService.create(tenantId, req == null ? new CreateSessionRequest(null, null, null) : req));
    }

    /**
     * 把整轮对话保存进会话历史（用户点「新对话」时调用）。
     * <p>body（camelCase）：{@code agentId}、{@code userId}、{@code title} 可选；
     * {@code messages:[{role,content}]} 必填。返回新会话摘要。</p>
     */
    @PostMapping("/import")
    public ApiResponse<SessionSummary> importConversation(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody ImportRequest req) {
        if (req.messages() == null || req.messages().isEmpty()) {
            return ApiResponse.error("BAD_REQUEST", "messages 不能为空");
        }
        return ApiResponse.ok(sessionService.importConversation(tenantId,
                req.agentId(), req.userId(), req.title(),
                req.messages().stream()
                        .map(m -> new SessionService.ImportMessage(m.role(), m.content()))
                        .toList()), "saved");
    }

    /** 会话导入请求体。 */
    public record ImportRequest(
            String agentId,
            String userId,
            String title,
            List<ImportMsg> messages
    ) {
        public record ImportMsg(String role, String content) {
        }
    }

    /** 分页列出会话。 */
    @GetMapping
    public ApiResponse<PageResult<SessionSummary>> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(sessionService.list(tenantId, agentId, userId, page, size));
    }

    /** 会话详情（含全部消息）。 */
    @GetMapping("/{sessionId}")
    public ApiResponse<SessionDetail> get(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String sessionId) {
        return ApiResponse.ok(sessionService.get(tenantId, sessionId));
    }

    /** 会话消息列表（时间顺序）。 */
    @GetMapping("/{sessionId}/messages")
    public ApiResponse<List<MessageDto>> messages(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String sessionId) {
        return ApiResponse.ok(sessionService.messages(tenantId, sessionId));
    }

    /** 更新会话（标题 / 状态）。 */
    @PatchMapping("/{sessionId}")
    public ApiResponse<SessionSummary> update(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String sessionId,
            @RequestBody UpdateSessionRequest req) {
        return ApiResponse.ok(sessionService.update(tenantId, sessionId, req.title(), req.status()));
    }

    /** 归档会话。 */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> archive(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String sessionId) {
        sessionService.archive(tenantId, sessionId);
        return ApiResponse.ok(null, "archived");
    }

    /** 清除会话历史（保留会话，清空消息）。 */
    @DeleteMapping("/{sessionId}/messages")
    public ApiResponse<Void> clear(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String sessionId) {
        sessionService.clear(tenantId, sessionId);
        return ApiResponse.ok(null, "cleared");
    }
}