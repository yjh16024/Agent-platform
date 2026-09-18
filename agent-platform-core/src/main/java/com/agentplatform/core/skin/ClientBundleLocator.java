package com.agentplatform.core.skin;

import com.agentplatform.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 定位皮肤的**客户端 bundle**（宿主加载它来激活皮肤）。
 *
 * <p>DSH 皮肤的加载契约写在 {@code package.json} 里，实测有三种布局：
 * <pre>
 *   maid-atelier  exports { ".": "./lib/index.js",    "./client": "./lib/client.js"    }
 *   macintosh     exports { ".": "./index.js",         "./client": "./client.js"        }
 *   open-sea-skin exports { ".": "./plugin/index.js",  "./client": "./plugin/client.js" }
 * </pre>
 * 所以**不能写死 {@code lib/client.js}**，必须读 {@code exports['./client']}。
 *
 * <p>解析顺序（都是"读声明"，不猜）：
 * <ol>
 *   <li>{@code exports['./client']} —— 字符串形式；</li>
 *   <li>同上但为<b>条件导出对象</b>（{@code {import|require|default}}），取第一个字符串值；</li>
 *   <li>{@code client} 顶层字段（老式写法）；</li>
 *   <li>兜底：在 {@code lib/}、{@code plugin/}、根目录里找 {@code client.js}。</li>
 * </ol>
 */
@Slf4j
final class ClientBundleLocator {

    private ClientBundleLocator() {
    }

    /** 返回相对皮肤目录的路径；找不到返回 null。 */
    static String locate(Path dir) {
        String fromManifest = fromPackageJson(dir.resolve("package.json"));
        if (fromManifest != null && exists(dir, fromManifest)) {
            return fromManifest;
        }
        // 声明缺失或指向不存在的文件时，按约定目录兜底
        for (String candidate : List.of(
                "lib/client.js", "client.js", "plugin/client.js",
                "dist/client.js", "src/client.js")) {
            if (exists(dir, candidate)) {
                log.debug("[skin-bundle] {} 无有效 exports['./client']，兜底命中 {}", dir.getFileName(), candidate);
                return candidate;
            }
        }
        return null;
    }

    private static String fromPackageJson(Path pkg) {
        if (!Files.isRegularFile(pkg)) {
            return null;
        }
        try {
            JsonNode root = JsonUtils.toJsonNode(Files.readString(pkg, java.nio.charset.StandardCharsets.UTF_8));
            JsonNode exports = root.get("exports");
            String v = firstString(exports == null ? null : exports.get("./client"));
            if (v != null) {
                return stripDotSlash(v);
            }
            return stripDotSlash(firstString(root.get("client")));
        } catch (Exception e) {
            log.debug("[skin-bundle] 解析 package.json 失败 {}：{}", pkg, e.getMessage());
            return null;
        }
    }

    /** 支持字符串与条件导出对象（取首个字符串值）。 */
    private static String firstString(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isString()) {
            return n.asString();
        }
        if (n.isObject()) {
            for (String key : List.of("import", "module", "default", "require", "browser")) {
                String v = firstString(n.get(key));
                if (v != null) {
                    return v;
                }
            }
            // 任意键兜底
            for (JsonNode child : n) {
                String v = firstString(child);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private static String stripDotSlash(String p) {
        if (p == null) {
            return null;
        }
        String s = p.trim();
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        return s.startsWith("/") ? s.substring(1) : s;
    }

    private static boolean exists(Path dir, String rel) {
        if (rel == null || rel.isBlank()) {
            return false;
        }
        Path f = dir.resolve(rel).normalize();
        return f.startsWith(dir) && Files.isRegularFile(f);
    }

    /** 皮肤目录里所有可能是 bundle 的候选（供诊断用）。 */
    static List<String> candidates(Path dir) {
        try (Stream<Path> s = Files.walk(dir, 5)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".js"))
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .filter(r -> !r.contains(".skin-art/"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
