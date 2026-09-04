package com.agentplatform.core.diagnosis;

import com.agentplatform.core.diagnosis.engine.DiagnosisEngine;
import com.agentplatform.core.log.LogEvent;
import com.agentplatform.core.log.LogService;
import com.agentplatform.model.record.DiagnosticReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断服务（错误 → 根因 → 方案）。
 * <p>对日志事件自动触发诊断（规则→向量→LLM 三级），并做错误统计。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisService {

    private final DiagnosisEngine engine;
    private final LogService logService;

    /**
     * 诊断单条日志。
     */
    public DiagnosticReport analyze(LogEvent event) {
        return engine.diagnose(DiagnosisContext.from(event));
    }

    /**
     * 按指纹/类别统计错误。
     */
    public Map<String, Object> statistics(List<LogEvent> errorEvents) {
        Map<String, Long> byFingerprint = new LinkedHashMap<>();
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (LogEvent e : errorEvents) {
            String fp = e.fingerprint() == null ? "unknown" : e.fingerprint();
            byFingerprint.merge(fp, 1L, Long::sum);
            byCategory.merge(e.category().name(), 1L, Long::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_errors", errorEvents.size());
        result.put("by_fingerprint", byFingerprint);
        result.put("by_category", byCategory);
        return result;
    }
}