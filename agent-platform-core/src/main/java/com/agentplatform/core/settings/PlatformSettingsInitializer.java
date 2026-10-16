package com.agentplatform.core.settings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动期把「平台设置覆盖文件」注入 Environment —— 让**装配期读取**的设置项
 * （{@code @ConditionalOnProperty} 的 Bean 条件、构造器注入的 final 字段）在重启后生效。
 *
 * <h3>为什么必须是 {@code ApplicationContextInitializer}</h3>
 * {@code @ConditionalOnProperty} 在容器**刷新**时求值，此前读取的是 Environment。所以注入必须发生在
 * 刷新之前、配置文件加载之后 —— {@code ApplicationContextInitializer#initialize} 恰好落在这个窗口
 * （{@code prepareContext} 阶段）。{@code @PostConstruct} / {@code ApplicationReadyEvent} 都太晚：
 * Bean 装配早就做完了，改 Environment 对它们没有追溯力。
 *
 * <h3>注入的优先级位置（刻意选择，不是随手 addFirst）</h3>
 * <pre>
 *   命令行参数 > 系统属性(-D) > 环境变量 > 【平台设置文件】 > application.yml / application-embedded.yml
 * </pre>
 * 实现为 {@code addAfter(SYSTEM_ENVIRONMENT, …)}。两个意图：
 * <ul>
 *   <li><b>压过 yml</b>：否则页面改的值永远被配置文件默认值盖住，"重启生效"就是空话；</li>
 *   <li><b>压不过环境变量 / 命令行</b>：运维的显式设置始终是最高优先级的救命通道 ——
 *       页面把 {@code storage.type} 误设成 minio、服务起不来时，一句
 *       {@code STORAGE_TYPE=local} 就能救回，不必先去改文件。</li>
 * </ul>
 *
 * <h3>容错</h3>
 * 文件不存在 / 解析失败 → 什么都不注入（记日志），启动照常。
 * 注入本身不产生任何副作用，最坏情况是"页面设置没生效"，而不是服务起不来。
 */
@Slf4j
public class PlatformSettingsInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** 注入的 PropertySource 名 —— {@link PlatformSettingService} 读取"基础层"时要显式跳过它。 */
    public static final String SOURCE_NAME = "platformSettings";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        inject(applicationContext.getEnvironment());
    }

    /**
     * 注入逻辑本体（与容器解耦，便于单测直接对 {@code MockEnvironment} 验证优先级）。
     */
    static void inject(ConfigurableEnvironment env) {
        String path = env.getProperty("agent-platform.settings.file", "./data/platform-settings.properties");
        Map<String, String> overrides = PlatformSettingsFile.load(Path.of(path));
        if (overrides.isEmpty()) {
            return;
        }

        MutablePropertySources sources = env.getPropertySources();
        MapPropertySource ps = new MapPropertySource(SOURCE_NAME, new LinkedHashMap<String, Object>(overrides));
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, ps);
            log.info("[settings] 已注入 {} 项平台设置覆盖（优先级：低于环境变量、高于配置文件）",
                    overrides.size());
        } else {
            // 正常 Boot 环境下 systemEnvironment 必然存在；走到这里说明环境被深度定制过，
            // 此时只能退化为最低优先级（宁可"页面设置没生效"，也不能把未知环境挤坏）
            sources.addLast(ps);
            log.warn("[settings] 未找到 systemEnvironment 配置源，平台设置覆盖已降级为最低优先级注入");
        }
    }
}
