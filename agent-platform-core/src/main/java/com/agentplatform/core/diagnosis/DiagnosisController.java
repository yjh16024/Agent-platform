package com.agentplatform.core.diagnosis;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.security.rbac.RequiresPermission;
import com.agentplatform.core.diagnosis.engine.DiagnosisEngine;
import com.agentplatform.core.diagnosis.rule.BuiltinDiagnosisRules;
import com.agentplatform.core.log.LogCategory;
import com.agentplatform.core.log.LogEvent;
import com.agentplatform.core.log.LogLevel;
import com.agentplatform.model.record.DiagnosticReport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能诊断接口（手动触发诊断 / 诊断规则查询）。
 */
@RestController
@RequestMapping("/api/v1/diagnosis")
@RequiredArgsConstructor
@RequiresPermission("log:read")
public class DiagnosisController {

    private final DiagnosisEngine engine;
    private final BuiltinDiagnosisRules rules;

    /**
     * 手动触发诊断（body 含 trace_id/log_id/context，或直接给 message+category）。
     */
    @PostMapping("/analyze")
    @RequiresPermission("agent:invoke")
    public ApiResponse<DiagnosticReport> analyze(@RequestBody Map<String, Object> body) {
        String traceId = (String) body.get("trace_id");
        String message = (String) body.get("message");
        String category = (String) body.getOrDefault("category", "plugin");
        String fingerprint = (String) body.get("fingerprint");
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) body.get("context");

        LogEvent event = LogEvent.of(
                (String) body.getOrDefault("log_id", "log_manual"), LogLevel.ERROR,
                LogCategory.valueOf(category), message, "default", traceId, null, context);
        DiagnosisContext ctx = new DiagnosisContext(traceId, event, fingerprint,
                LogCategory.valueOf(category), message, null, context);
        return ApiResponse.ok(engine.diagnose(ctx));
    }

    /**
     * 内置诊断规则列表。
     */
    @GetMapping("/rules")
    public ApiResponse<List<Map<String, Object>>> rules() {
        return ApiResponse.ok(rules.all().values().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fingerprint", r.fingerprint());
            m.put("category", r.category());
            m.put("root_cause", r.rootCauseSummary());
            return m;
        }).toList());
    }
}