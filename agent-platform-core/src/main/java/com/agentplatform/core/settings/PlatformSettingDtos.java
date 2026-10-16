package com.agentplatform.core.settings;

import java.util.List;
import java.util.Map;

/**
 * 「平台设置」接口的 DTO（强类型 record，遵循项目契约：出参 camelCase）。
 */
public final class PlatformSettingDtos {

    private PlatformSettingDtos() {
    }

    /**
     * 单个设置项。
     *
     * @param key              Spring 属性名
     * @param label            界面标签
     * @param description      说明
     * @param type             BOOL / ENUM / NUMBER / LIST（决定界面控件）
     * @param value            当前生效值（字符串：bool 为 "true"/"false"；LIST 为逗号分隔）
     * @param options          ENUM 候选项（其他类型为空表）
     * @param restartRequired  写入后是否需要重启才生效
     * @param overridden       是否被页面改过（true 时界面显示"已自定义 / 恢复默认"）
     * @param locked           当前环境是否强制锁定（如桌面模式的 Kafka 开关）
     * @param lockReason       锁定时给用户看的原因
     */
    public record SettingItem(String key, String label, String description, String type,
                              String value, List<String> options, boolean restartRequired,
                              boolean overridden, boolean locked, String lockReason) {
    }

    /** 分组（名称 = 注册表里的 group，顺序 = 注册表声明顺序）。 */
    public record SettingGroup(String name, List<SettingItem> items) {
    }

    /**
     * 完整视图。
     *
     * @param runtimeMode "embedded"（桌面 / 内置库）/ "server"（外部数据库）—— 界面按它提示环境差异
     */
    public record SettingsView(String runtimeMode, List<SettingGroup> groups) {
    }

    /** 写入请求：键 → 值（值一律以字符串传递，后端按注册表类型校验）。 */
    public record SettingsUpdateRequest(Map<String, String> values) {
    }

    /** 恢复默认请求：要删掉覆盖的键列表。 */
    public record SettingsResetRequest(List<String> keys) {
    }

    /**
     * 写入 / 恢复后的返回。
     *
     * @param restartRequiredKeys 本次因值变化而需要重启的键（界面据此弹窗；空表 = 全部即时生效）
     */
    public record UpdateResult(SettingsView view, List<String> restartRequiredKeys) {
    }
}
