package com.agentplatform.core.skill;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Skill 导入器（市场安装 / Git 仓库 / 本地上传 / 远端 URL）。
 * <p>解析 YAML/JSON Manifest，导入后自动注册工具与提示词模板。</p>
 */
@Slf4j
@Component
public class SkillImporter {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 从 YAML/JSON 文本解析 Manifest。
     */
    public SkillManifest parse(String manifestText) {
        if (manifestText == null || manifestText.isBlank()) {
            throw new BizException("BAD_REQUEST", "Manifest is empty");
        }
        try {
            SkillManifest manifest;
            String trimmed = manifestText.trim();
            if (trimmed.startsWith("{")) {
                // JSON
                manifest = JsonUtils.mapper().readValue(trimmed, SkillManifest.class);
            } else {
                // YAML
                manifest = yamlMapper.readValue(trimmed, SkillManifest.class);
            }
            manifest.validate();
            return manifest;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("VALIDATION_ERROR", "Failed to parse skill manifest: " + e.getMessage());
        }
    }

    /**
     * 从远端 URL 导入（抓取 manifest.yaml）。
     */
    public SkillManifest importFromUrl(String url) {
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new BizException("BAD_REQUEST", "Failed to fetch skill manifest: HTTP " + response.code());
                }
                String body = response.body() == null ? "" : response.body().string();
                return parse(body);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("INTERNAL_ERROR", "Failed to import skill from URL: " + e.getMessage());
        }
    }
}