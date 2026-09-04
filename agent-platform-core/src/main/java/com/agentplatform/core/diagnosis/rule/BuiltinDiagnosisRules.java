package com.agentplatform.core.diagnosis.rule;

import com.agentplatform.model.enums.ErrorSeverity;
import com.agentplatform.model.record.DiagnosticReport;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置诊断规则库（覆盖设计文档 §3.5 的高频错误场景）。
 * <p>覆盖 80% 高频错误秒级返回；未命中走向量/LLM 兜底。</p>
 */
@Component
public class BuiltinDiagnosisRules {

    private final Map<String, ErrorRule> rules = new ConcurrentHashMap<>();

    public BuiltinDiagnosisRules() {
        register(new ErrorRule("skill_import#manifest_invalid", "skill_import", ErrorSeverity.MAJOR,
                "Manifest 格式错误或缺少必填字段",
                "Manifest YAML 解析异常 / 缺少 name、version、entry 字段 / 工具 JSON Schema 不合法",
                java.util.List.of(
                        ErrorRule.manual("检查 Manifest YAML 缩进与字段名", "确认缩进一致、字段拼写正确", 0.95),
                        ErrorRule.manual("补全必填字段", "补充 name、version、entry 等必填字段", 0.90),
                        ErrorRule.manual("验证 tool input_schema", "确认是合法 JSON Schema", 0.85)),
                0.95));

        register(new ErrorRule("plugin#classloader_error", "plugin", ErrorSeverity.CRITICAL,
                "插件缺少依赖，ClassLoader 找不到类",
                "ClassLoader 在加载插件类时未找到依赖类 / requires.plugins 依赖未安装 / 导出包白名单遗漏",
                java.util.List.of(
                        ErrorRule.manual("将缺失依赖打包进插件 lib/", "在 plugin pom.xml 添加依赖并重新 mvn package", 0.95),
                        ErrorRule.manual("检查 requires.plugins 依赖", "确认 plugin.yaml 声明的插件依赖已全部安装", 0.80)),
                0.95));

        register(new ErrorRule("plugin#timeout", "plugin", ErrorSeverity.MAJOR,
                "插件执行超时",
                "外部 API 响应慢 / 插件逻辑死循环 / 资源（CPU/内存）不足",
                java.util.List.of(
                        ErrorRule.manual("调大 runtime.timeout_ms", "在 Manifest 增大超时阈值", 0.90),
                        ErrorRule.manual("检查外部服务健康状态", "确认调用方服务可用", 0.85)),
                0.90));

        register(new ErrorRule("plugin#dependency_conflict", "plugin", ErrorSeverity.MAJOR,
                "插件依赖版本冲突",
                "多个插件要求同一依赖的不同不兼容版本（SemVer 范围无法满足）",
                java.util.List.of(
                        ErrorRule.manual("查看依赖图", "调用 /plugins/{id}/dependencies 查看", 0.90),
                        ErrorRule.manual("升级冲突插件到兼容版本", "或使用 runtime.isolation=container 隔离", 0.85)),
                0.90));

        register(new ErrorRule("model#connection_refused", "model", ErrorSeverity.CRITICAL,
                "模型网关不可达或网络策略阻断",
                "ConnectException / SocketTimeout / 401 Unauthorized / NetworkPolicy 阻断",
                java.util.List.of(
                        ErrorRule.manual("检查 LiteLLM Proxy 健康", "访问 /health 端点确认", 0.95),
                        ErrorRule.manual("验证 API Key 配额与有效期", "确认 key 未过期、配额充足", 0.90),
                        ErrorRule.manual("切换 Fallback 模型", "ModelRouter 自动降级到备选模型", 0.85)),
                0.95));

        register(new ErrorRule("api#401", "api", ErrorSeverity.MAJOR,
                "鉴权失效",
                "API Key 无效或过期 / 缺少 Authorization 头",
                java.util.List.of(
                        ErrorRule.manual("检查 API Key 是否有效/过期", "重新绑定凭证", 0.95)),
                0.95));

        register(new ErrorRule("api#429", "api", ErrorSeverity.MINOR,
                "请求限流",
                "调用频率超限",
                java.util.List.of(
                        ErrorRule.manual("降低调用频率或申请提额", "增加重试+退避策略", 0.95)),
                0.95));
    }

    public void register(ErrorRule rule) {
        rules.put(rule.fingerprint(), rule);
    }

    public ErrorRule find(String fingerprint) {
        return rules.get(fingerprint);
    }

    public Map<String, ErrorRule> all() {
        return Map.copyOf(rules);
    }
}