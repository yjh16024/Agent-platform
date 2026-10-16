package com.agentplatform.core.settings;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 平台设置「覆盖文件」的读写 —— 纯文件操作，供启动注入与运行期服务共用。
 *
 * <h3>为什么是文件，不是数据库表</h3>
 * 这些设置有两个必须同时满足的要求，只有文件能同时做到：
 * <ol>
 *   <li><b>启动极早期可读</b>：一部分设置项（{@code @ConditionalOnProperty}）必须在
 *       Spring 容器**装配前**就位 —— {@link PlatformSettingsInitializer} 在
 *       {@code ApplicationContextInitializer} 阶段读它，此时数据库连接还没建立，
 *       读表根本不可能（表本身还依赖 Flyway 迁移）；</li>
 *   <li><b>不依赖数据库可用性</b>：缺 MySQL / H2 文件损坏时，平台设置不应跟着失效。</li>
 * </ol>
 * 与 {@code WorkspaceService} 的 {@code workspace.json} 同一判据：
 * 这类"本机 / 实例级"配置用文件，租户级业务配置才进库。
 *
 * <h3>文件里只存"被页面改过的项"</h3>
 * 没有写进文件的键 = 使用 application.yml / 环境变量的值。
 * 于是"恢复默认"就是**删掉那一行**（或整个文件），不需要在文件里复制一份完整配置 ——
 * 复制完整配置会让"改 yml 默认值"与"文件里的旧默认"悄悄分叉。
 *
 * <h3>读取容错</h3>
 * 文件不存在、是目录、解析失败 —— 一律视为"没有覆盖"，记一条 WARN 后按配置文件默认值运行。
 * 设置文件读不出来不该让服务起不来（它与核心业务无关）。
 */
@Slf4j
final class PlatformSettingsFile {

    /** 文件头（给人看的；Properties 解析器把 {@code #} 开头的行当注释跳过）。 */
    private static final String HEADER = """
            # 白雾·智能体交互平台 —— 平台设置覆盖文件
            # 本文件由仪表盘「平台设置」页维护，只保存"被页面改过"的项。
            # 删除某一行（或整份文件）即把该项恢复为配置文件 / 环境变量里的值；改完需重启才生效的项会在页面提示。
            # 启动时本文件以「高于 application.yml、低于环境变量与命令行参数」的优先级参与配置装配。
            """;

    private PlatformSettingsFile() {
    }

    /**
     * 读取覆盖项。任何失败都返回空表（= 没有覆盖），绝不上抛 ——
     * 调用方之一是容器启动早期，抛异常会让整个服务起不来。
     */
    static Map<String, String> load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Map.of();
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException：Properties 对畸形的 Unicode 转义序列（反斜杠 + u 开头）会抛这个
            log.warn("[settings] 平台设置文件解析失败，已忽略（本次以配置文件默认值运行）：{} —— {}",
                    file, e.getMessage());
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            out.put(name, props.getProperty(name));
        }
        return out;
    }

    /**
     * 原子写入：先写同目录临时文件再 rename。
     * <p>直接覆盖原文件的话，"写到一半断电 / 进程被杀"会留下半份配置 ——
     * 而这份文件决定下次启动的行为，半份配置比旧配置危险得多。</p>
     */
    static void save(Path file, Map<String, String> values) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        if (abs.getParent() != null) {
            Files.createDirectories(abs.getParent());
        }
        StringBuilder sb = new StringBuilder(HEADER);
        values.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));

        Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 少数文件系统（部分网络盘）不支持原子 rename —— 退化为普通 move，仍比直接覆盖安全
            Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
