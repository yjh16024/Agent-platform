package com.agentplatform.core.tool.fs;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.security.rbac.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作区设置接口 —— 让"智能体能改哪个目录"在界面上可见、可改。
 *
 * <h3>为什么必须补这个入口</h3>
 * 在此之前工作区根**只能靠环境变量或启动参数设置**（{@code AGENT_WORKSPACE_ROOT}），
 * 而桌面版用户根本没有"改启动参数"的入口。默认值 {@code ./data/workspace} 是个空目录，
 * 于是"让智能体帮我改这个文件"这件事**在界面上无路可走** —— 用户只能改文件去骗平台，
 * 或者以为功能坏了（2026-09-26 的真实反馈）。
 *
 * <h3>★ 关于权限：为什么不新开 {@code workspace:manage}</h3>
 * 工作区根**就是 {@code fs_*} 工具的安全边界** —— 改它等于改"工具能碰到的范围"，
 * 与 {@code tool:write}（管理工具本身）是同一件事的两面。复用现成权限码的好处是
 * 不用动权限清单与迁移（那会牵出前后端一整套台账），而语义上并不勉强。
 *
 * <h3>关于校验：拒绝发生在服务层，不在这里</h3>
 * {@link WorkspaceService#checkRootSafety} 才是判定"哪些目录不能当工作区"的唯一实现
 * —— 放在 Controller 里会让"绕过路径"多一条（比如将来加个 CLI 或定时任务去设根，
 * 那条路就不会经过 Controller）。**安全判定只应有一处实现。**
 */
@RestController
@RequestMapping("/api/v1/workspace")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspace;
    private final ProtectedPaths protectedPaths;

    /** 当前工作区状态（界面用来显示"现在授权了哪个目录"）。 */
    @GetMapping
    @RequiresPermission("tool:read")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(view());
    }

    /**
     * 设置工作区根。
     *
     * <p>路径不合法、不存在、或属于必须拒绝的类别（盘根 / 系统目录 / 主目录本身 / 凭据目录）时
     * 返回 4xx —— 校验失败**不会**改变当前根（见 {@code WorkspaceService.setRoot} 的原子性）。</p>
     */
    @PutMapping
    @RequiresPermission("tool:write")
    public ApiResponse<Map<String, Object>> set(@RequestBody SetWorkspaceRequest req) {
        workspace.setRoot(req == null ? null : req.path());
        return ApiResponse.ok(view());
    }

    /** 重置为配置/环境变量里的默认根（清除界面设置）。 */
    @DeleteMapping
    @RequiresPermission("tool:write")
    public ApiResponse<Map<String, Object>> reset() {
        workspace.resetRoot();
        return ApiResponse.ok(view());
    }

    /**
     * 状态视图。
     *
     * <p>把 {@code skipDirs} 与 {@code protectedSummary} 一并返回，是为了让界面能如实告诉用户
     * "即使授权了这个目录，这些内容也不会被碰" —— 用户对边界了解得越清楚，才越敢用这个功能。</p>
     */
    private Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", workspace.available());
        m.put("root", workspace.root().toString());
        m.put("overridden", workspace.isOverridden());
        m.put("maxReadBytes", workspace.maxReadBytes());
        m.put("skipDirs", workspace.skipDirs());
        m.put("protectedSummary", protectedPaths.summary());
        return m;
    }

    /** 设置工作区的请求体。 */
    public record SetWorkspaceRequest(String path) {
    }
}
