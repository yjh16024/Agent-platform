package com.agentplatform.core.observability.export;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 可观测性导出配置（阶段 B：Loki 日志 / Tempo Trace / Micrometer 业务指标）。
 * <p>
 * 前缀 {@code agent-platform.observability.*}，全部带环境变量覆盖、全部可关闭。
 * 默认不配置任何导出地址（本地零外部依赖静默运行）；只有显式配置地址后
 * 对应 exporter 才真正外推。
 * </p>
 * <pre>
 *   agent-platform.observability.enabled=false                    # 总开关（默认 true）
 *   agent-platform.observability.loki.url=http://localhost:3100   # 空 = 不推送 Loki
 *   agent-platform.observability.loki.tenant-id=                  # 可选租户头 X-Scope-OrgID
 *   agent-platform.observability.loki.batch-size=200              # 攒批条数
 *   agent-platform.observability.loki.flush-interval-ms=1000      # 攒批间隔
 *   agent-platform.observability.tempo.url=http://localhost:9411  # 空 = 不合成 Trace（Zipkin v2）
 *   agent-platform.observability.metrics.enabled=true             # Micrometer 业务指标
 * </pre>
 */
@Getter
@Component
public class ObsExportProperties {

    private final boolean enabled;
    private final boolean metricsEnabled;
    private final String lokiUrl;
    private final String lokiTenantId;
    private final int lokiBatchSize;
    private final int lokiFlushIntervalMs;
    private final int queueCapacity;
    private final String tempoUrl;
    private final int tempoFlushIntervalMs;

    public ObsExportProperties(
            @Value("${agent-platform.observability.enabled:true}") boolean enabled,
            @Value("${agent-platform.observability.metrics.enabled:true}") boolean metricsEnabled,
            @Value("${agent-platform.observability.loki.url:}") String lokiUrl,
            @Value("${agent-platform.observability.loki.tenant-id:}") String lokiTenantId,
            @Value("${agent-platform.observability.loki.batch-size:200}") int lokiBatchSize,
            @Value("${agent-platform.observability.loki.flush-interval-ms:1000}") int lokiFlushIntervalMs,
            @Value("${agent-platform.observability.queue-capacity:4096}") int queueCapacity,
            @Value("${agent-platform.observability.tempo.url:}") String tempoUrl,
            @Value("${agent-platform.observability.tempo.flush-interval-ms:500}") int tempoFlushIntervalMs) {
        this.enabled = enabled;
        this.metricsEnabled = metricsEnabled;
        this.lokiUrl = blankToNull(lokiUrl);
        this.lokiTenantId = blankToNull(lokiTenantId);
        this.lokiBatchSize = Math.max(1, lokiBatchSize);
        this.lokiFlushIntervalMs = Math.max(50, lokiFlushIntervalMs);
        this.queueCapacity = Math.max(256, queueCapacity);
        this.tempoUrl = blankToNull(tempoUrl);
        this.tempoFlushIntervalMs = Math.max(50, tempoFlushIntervalMs);
    }

    public boolean lokiEnabled() {
        return enabled && lokiUrl != null;
    }

    public boolean tempoEnabled() {
        return enabled && tempoUrl != null;
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
