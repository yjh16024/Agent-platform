package com.agentplatform.core.settings;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.security.rbac.RequiresPermission;
import com.agentplatform.core.settings.PlatformSettingDtos.SettingsResetRequest;
import com.agentplatform.core.settings.PlatformSettingDtos.SettingsUpdateRequest;
import com.agentplatform.core.settings.PlatformSettingDtos.SettingsView;
import com.agentplatform.core.settings.PlatformSettingDtos.UpdateResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台设置接口 —— 「平台设置」页（功能开关层）的读写入口。
 *
 * <ul>
 *   <li>{@code GET /api/v1/platform-settings} —— 全部设置项（分组 + 当前值 + 是否需重启）；</li>
 *   <li>{@code PUT /api/v1/platform-settings} —— 写入若干项（类型校验；返回本次需重启的键）；</li>
 *   <li>{@code POST /api/v1/platform-settings/reset} —— 恢复若干项为配置文件默认值。</li>
 * </ul>
 *
 * <p>写入会落 `data/platform-settings.properties` 覆盖文件（<b>热切换项立即生效</b>，
 * 需重启项由 {@link PlatformSettingsInitializer} 在下次启动时注入）。
 * 单码 {@code platform:manage} 管读写：与 {@code model:manage} 同一粒度 ——
 * 页面里的模型绑定含厂商密钥（掩码视图），能看掩码的人本来就是该配置它的人；读写拆两码
 * 只会造出"能看不能改"的中间态，没有实际管控对象。</p>
 */
@RestController
@RequestMapping("/api/v1/platform-settings")
@RequiredArgsConstructor
@RequiresPermission("platform:manage")
public class PlatformSettingController {

    private final PlatformSettingService service;

    /** 全部设置项。 */
    @GetMapping
    public ApiResponse<SettingsView> get() {
        return ApiResponse.ok(service.view());
    }

    /** 写入若干项。 */
    @PutMapping
    public ApiResponse<UpdateResult> update(@RequestBody SettingsUpdateRequest req) {
        return ApiResponse.ok(service.apply(req == null ? null : req.values()), "平台设置已保存");
    }

    /** 恢复默认（删掉覆盖层里的键，回落配置文件 / 环境变量的值）。 */
    @PostMapping("/reset")
    public ApiResponse<UpdateResult> reset(@RequestBody SettingsResetRequest req) {
        return ApiResponse.ok(service.reset(req == null ? null : req.keys()), "平台设置已恢复默认");
    }
}
