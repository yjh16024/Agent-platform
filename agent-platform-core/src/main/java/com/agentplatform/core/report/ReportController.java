package com.agentplatform.core.report;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.report.ReportDtos.ReportView;
import com.agentplatform.core.security.rbac.RbacContext;
import com.agentplatform.core.security.rbac.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计报表接口。
 *
 * <p>只有**一个** GET：整张报表一次返回。页面打开时要同时渲染 4 个区块，
 * 拆成 4 个接口就是 4 次往返，而且它们的时间窗必须一致 ——
 * 分开发很容易出现"概览是 7 天、趋势是 30 天"这种对不上的情况。</p>
 *
 * <p>它是 GET，所以天然不在 {@code AuditAspect} 的记录范围内（切面只记写操作），
 * 不会出现"看报表反而产生审计"的自我增殖。</p>
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * 整张报表。
     *
     * @param days 时间窗天数（默认 7，上限 90；服务层会夹取，非法值不会导致 500）
     */
    @GetMapping("/overview")
    @RequiresPermission("report:read")
    public ApiResponse<ReportView> overview(@RequestParam(required = false) Integer days) {
        int d = days == null ? 7 : days;
        return ApiResponse.ok(reportService.build(RbacContext.tenantId(), d));
    }
}
