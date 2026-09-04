package com.agentplatform.core.agent.service;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.entity.AgentVersion;
import com.agentplatform.model.repository.AgentVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能体版本服务（不可变快照 + 发布 + 回滚 + Diff）。
 * <p>
 * 每次发布生成语义化版本（v{major}.{minor}.{patch}）+ 配置快照（含 prompt 哈希），
 * 运行中会话锚定发布版本避免配置漂移。回滚到任意历史版本并支持差异对比。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentVersionService {

    /** 语义化版本：v1.2.0 */
    private static final Pattern SEMVER = Pattern.compile("^v(\\d+)\\.(\\d+)\\.(\\d+)$");

    private final AgentVersionRepository versionRepository;
    private final AgentService agentService;

    /**
     * 基于当前草稿创建/获取版本快照并存盘。
     *
     * @return 快照版本号，如 v1.0.0-draft（未发布时携带 -draft 后缀）
     */
    @Transactional
    public AgentVersion createVersionSnapshot(String agentId, String tenantId) {
        AgentDefinition def = agentService.getOrThrow(tenantId, agentId);
        String version = nextDraftVersion(agentId);
        Map<String, Object> snapshot = buildSnapshot(def);
        String hash = sha256(def.getSystemPrompt());

        AgentVersion v = AgentVersion.builder()
                .agentId(agentId)
                .version(version)
                .snapshot(snapshot)
                .promptHash(hash)
                .releasedBy(tenantId)
                .build();
        return versionRepository.save(v);
    }

    /**
     * 发布当前版本：将 draft 快照转为发布的语义化版本并更新 current_version。
     */
    @Transactional
    public AgentVersion publish(String agentId, String tenantId) {
        AgentDefinition def = agentService.getOrThrow(tenantId, agentId);
        String version = nextReleaseVersion(agentId);
        Map<String, Object> snapshot = buildSnapshot(def);
        String hash = sha256(def.getSystemPrompt());

        AgentVersion v = AgentVersion.builder()
                .agentId(agentId)
                .version(version)
                .snapshot(snapshot)
                .promptHash(hash)
                .releasedBy(tenantId)
                .build();
        v = versionRepository.save(v);

        agentService.updateCurrentVersion(agentId, tenantId, version);
        log.info("Published agent {} version {}", agentId, version);
        return v;
    }

    /**
     * 回滚到指定历史版本。
     */
    @Transactional
    public AgentVersion rollback(String agentId, String version, String tenantId) {
        AgentVersion target = versionRepository.findByAgentIdAndVersion(agentId, version)
                .orElseThrow(() -> BizException.notFound("agent version", version));

        // 从快照还原当前值
        AgentDefinition def = agentService.getOrThrow(tenantId, agentId);
        applySnapshot(def, target.getSnapshot());
        agentService.getOrThrow(tenantId, agentId);  // 确保租户校验

        agentService.updateCurrentVersion(agentId, tenantId, version);
        log.info("Rolled back agent {} to version {}", agentId, version);
        return target;
    }

    /**
     * 版本列表（倒序）。
     */
    @Transactional(readOnly = true)
    public List<AgentVersion> listVersions(String agentId, String tenantId) {
        agentService.getOrThrow(tenantId, agentId);
        return versionRepository.findByAgentIdOrderByReleasedAtDesc(agentId);
    }

    /**
     * 两个版本的配置差异对比（含提示词 diff）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> diff(String agentId, String fromVersion, String toVersion, String tenantId) {
        AgentVersion from = versionRepository.findByAgentIdAndVersion(agentId, fromVersion)
                .orElseThrow(() -> BizException.notFound("agent version", fromVersion));
        AgentVersion to = versionRepository.findByAgentIdAndVersion(agentId, toVersion)
                .orElseThrow(() -> BizException.notFound("agent version", toVersion));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", fromVersion);
        result.put("to", toVersion);
        result.put("prompt_diff", diffPrompt(
                (String) from.getSnapshot().get("system_prompt"),
                (String) to.getSnapshot().get("system_prompt")));
        result.put("config_diff", diffConfig(from.getSnapshot(), to.getSnapshot()));
        return result;
    }

    /**
     * 组装全量配置快照。
     */
    private Map<String, Object> buildSnapshot(AgentDefinition def) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", def.getName());
        snapshot.put("persona", def.getPersona());
        snapshot.put("system_prompt", def.getSystemPrompt());
        snapshot.put("generation_config", def.getGenerationConfig());
        snapshot.put("capabilities", def.getCapabilities());
        snapshot.put("visibility", def.getVisibility().toDb());
        return snapshot;
    }

    /**
     * 从快照还原当前值。
     */
    private void applySnapshot(AgentDefinition def, Map<String, Object> snapshot) {
        def.setPersona(JsonUtils.fromJson(JsonUtils.toJson(snapshot.get("persona")),
                com.agentplatform.model.record.Persona.class));
        def.setSystemPrompt((String) snapshot.get("system_prompt"));
        def.setGenerationConfig(JsonUtils.fromJson(JsonUtils.toJson(snapshot.get("generation_config")),
                com.agentplatform.model.record.GenerationConfig.class));
        def.setCapabilities(JsonUtils.fromJson(JsonUtils.toJson(snapshot.get("capabilities")),
                com.agentplatform.model.record.Capabilities.class));
    }

    /**
     * 生成下一个草稿版本号（v{major}.{minor}.{patch}-draft）。
     */
    private String nextDraftVersion(String agentId) {
        return nextBaseVersion(agentId) + "-draft";
    }

    /**
     * 生成下一个发布版本号（v{major}.{minor}.{patch}）。
     */
    private String nextReleaseVersion(String agentId) {
        return nextBaseVersion(agentId);
    }

    private String nextBaseVersion(String agentId) {
        List<String> versions = versionRepository.findByAgentIdOrderByReleasedAtDesc(agentId).stream()
                .map(AgentVersion::getVersion)
                .map(v -> v.replace("-draft", ""))
                .toList();
        int major = 1, minor = 0, patch = 0;
        for (String v : versions) {
            Matcher m = SEMVER.matcher(v);
            if (m.matches()) {
                int[] parsed = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
                if (parsed[0] > major || (parsed[0] == major && (parsed[1] > minor || (parsed[1] == minor && parsed[2] >= patch)))) {
                    major = parsed[0];
                    minor = parsed[1];
                    patch = parsed[2] + 1;
                }
            }
        }
        return "v" + major + "." + minor + "." + patch;
    }

    /**
     * SHA-256 哈希（提示词指纹）。
     */
    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 行级 Diff（LCS 最长公共子序列）。
     * <p>
     * 相对「逐行 contains 判断」的旧实现：LCS 保证
     * （1）删/增操作按原文本<b>顺序</b>输出，可读性接近 unified diff；
     * （2）内容重复的行不会误判为「既删又增」。行数过大时退化为按块对比，避免 DP 内存爆炸。
     * </p>
     */
    private List<Map<String, Object>> diffPrompt(String before, String after) {
        List<Map<String, Object>> diffs = new java.util.ArrayList<>();
        if (before == null && after == null) {
            return diffs;
        }
        if (before == null || before.isBlank()) {
            for (String line : after.split("\n", -1)) {
                diffs.add(Map.of("type", "added", "content", line));
            }
            return diffs;
        }
        if (after == null || after.isBlank()) {
            for (String line : before.split("\n", -1)) {
                diffs.add(Map.of("type", "removed", "content", line));
            }
            return diffs;
        }
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        if (a.length * (long) b.length > 2_000_000L) {
            // 超大文本：退化为按块差异（只看整体增删行集合，不做顺序对齐）
            java.util.Set<String> setA = new java.util.HashSet<>(java.util.Arrays.asList(a));
            java.util.Set<String> setB = new java.util.HashSet<>(java.util.Arrays.asList(b));
            for (String line : b) {
                if (!setA.contains(line)) {
                    diffs.add(Map.of("type", "added", "content", line));
                }
            }
            for (String line : a) {
                if (!setB.contains(line)) {
                    diffs.add(Map.of("type", "removed", "content", line));
                }
            }
            return diffs;
        }

        // 标准 LCS DP
        int n = a.length, m = b.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a[i].equals(b[j])
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                diffs.add(Map.of("type", "removed", "content", a[i]));
                i++;
            } else {
                diffs.add(Map.of("type", "added", "content", b[j]));
                j++;
            }
        }
        while (i < n) {
            diffs.add(Map.of("type", "removed", "content", a[i++]));
        }
        while (j < m) {
            diffs.add(Map.of("type", "added", "content", b[j++]));
        }
        return diffs;
    }

    /**
     * 配置字段差异对比。
     */
    private Map<String, Object> diffConfig(Map<String, Object> from, Map<String, Object> to) {
        Map<String, Object> diff = new LinkedHashMap<>();
        to.forEach((k, v) -> {
            Object fromV = from.get(k);
            if (fromV == null || !fromV.equals(v)) {
                diff.put(k, Map.of("before", fromV == null ? null : fromV, "after", v));
            }
        });
        return diff;
    }
}