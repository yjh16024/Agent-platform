package com.agentplatform.core.plugin.web;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 界面插件（独立的前端 JS 插件）的安装与管理（2026-10-19，D 档第 4 步）。
 *
 * <h3>它和"插件"（jar）的区别</h3>
 * <ul>
 *   <li><b>jar 插件</b>：有后端能力（工具 / 钩子），按智能体 attach，需要 ClassLoader；</li>
 *   <li><b>界面插件</b>：只有一段前端脚本 + 一个声明（槽位 / 入口 / 隔离），
 *       **全局生效、不绑智能体、装上就能用**（像皮肤，但做的是功能不是外观）。</li>
 * </ul>
 * 所以它有自己的目录（{@code ./data/plugin-web}）与状态文件，不进 {@code plugin_def} 表 ——
 * 混进去会让"按智能体挂载"这个语义变模糊。
 *
 * <h3>安装包格式（zip，根级平铺）</h3>
 * <pre>
 *   manifest.json   {"id":"usage_widget","name":"用量挂件","slot":"global.overlay",
 *                    "entry":"main.js","style":"style.css","isolate":true,"order":100}
 *   main.js         window.__apPlugin__.register('usage_widget', { apply(ctx) {...} })
 *   style.css       （可选）
 * </pre>
 *
 * <h3>★ 三条硬约束</h3>
 * <ol>
 *   <li><b>解压必须防 zip slip</b>：任何含 {@code ..} 段 / 绝对路径 / 空段的条目直接拒绝
 *       （不是"归一化后接受"——那会让两个条目写同一个文件，行为不可预期）；</li>
 *   <li><b>体积与数量上限</b>：单文件 ≤ 2MB、总文件数 ≤ 64 —— zip 炸弹的解压发生在
 *       "读条目"的过程中，只在最后检查大小已经太晚了；</li>
 *   <li><b>先解到临时目录、校验通过再改名</b>：半装的插件目录比"没装上"更麻烦
 *       （它的 manifest 可能合法、文件却缺一半）。</li>
 * </ol>
 */
@Slf4j
@Service
public class PluginWebService {

    /** 插件 id（同时是目录名）：只允许安全字符，避免路径穿越从 id 进来。 */
    private static final String SAFE_ID = "[A-Za-z0-9._-]+";

    /** 单文件上限。插件前端资源不该更大；也是 zip 炸弹的第一道闸。 */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    /** 单个插件最多文件数。 */
    private static final int MAX_FILES = 64;

    private static final String STATE_FILE = "state.json";
    private static final String MANIFEST_FILE = "manifest.json";

    private final Path baseDir;

    /** 与 {@code PluginService} 同一个总开关：关掉后界面插件也不再出现在槽位声明里。 */
    private final boolean uiScriptEnabled;

    public PluginWebService(@Value("${agent-platform.plugin-web.dir:./data/plugin-web}") String dir,
                            @Value("${agent-platform.plugin.ui-script.enabled:true}") boolean uiScriptEnabled) {
        this.baseDir = Path.of(dir).toAbsolutePath().normalize();
        this.uiScriptEnabled = uiScriptEnabled;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            log.warn("[plugin-web] 无法创建目录 {}：{}", baseDir, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 对外

    /** 已安装列表（含 enabled 与挂载范围）。 */
    public List<Map<String, Object>> list() {
        Map<String, Boolean> state = readState();
        Map<String, List<String>> scopes = readAgents();
        List<Map<String, Object>> out = new ArrayList<>();
        try (var dirs = Files.list(baseDir)) {
            dirs.filter(Files::isDirectory)
                    .forEach(dir -> {
                        String id = dir.getFileName().toString();
                        if (!id.matches(SAFE_ID)) {
                            return;   // 非法目录名（手工丢进来的）忽略
                        }
                        Map<String, Object> manifest = readManifest(dir);
                        if (manifest == null) {
                            return;
                        }
                        out.add(toView(id, manifest, !Boolean.FALSE.equals(state.get(id)),
                                dir, scopes.getOrDefault(id, List.of())));
                    });
        } catch (IOException e) {
            log.warn("[plugin-web] 读取插件目录失败：{}", e.getMessage());
            return List.of();
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
        return out;
    }

    /** 兼容旧调用：不做作用域过滤（等同于全局视图）。 */
    public List<Map<String, Object>> contributions() {
        return contributions(null);
    }

    /**
     * 供槽位渲染的贡献（只含 enabled 且在当前智能体范围内生效的）。
     *
     * @param agentId 当前智能体；**为 null 时不做作用域过滤**（兼容旧调用方，等同于"全部"）。
     *                正常前端会带上它 —— 不带的后果是"给 A 智能体装的插件也出现在 B 上"。
     */
    public List<Map<String, Object>> contributions(String agentId) {
        if (!uiScriptEnabled) {
            return List.of();   // 应急开关关闭：界面插件一律不再下发
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : list()) {
            if (!Boolean.TRUE.equals(item.get("enabled"))) {
                continue;
            }
            /*
             * 作用域过滤（2026-10-20）：`agents` 为空 = 全局（所有智能体都生效），
             * 非空则只给列出的智能体。这让"单个智能体专属的小挂件"成为可能，
             * 而不必为了一个挂件污染所有对话页。
             */
            if (!inScope(item.get("agents"), agentId)) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("pluginId", item.get("id"));
            one.put("pluginName", item.get("name"));
            one.put("id", "web");
            one.put("slot", item.get("slot"));
            one.put("type", "script");
            one.put("label", item.get("label"));
            one.put("order", item.get("order"));
            one.put("script", item.get("entry"));
            one.put("style", item.get("style"));
            one.put("isolate", item.get("isolate"));
            /*
             * Node 侧（2026-10-19，P4）：`main` + `path` 交给桌面壳去加载插件自己的 main.js。
             * 后端不执行它（那是主进程的事），只把"有没有"和"目录在哪"如实告诉前端 ——
             * 主进程据此 loadPlugin(pluginId, dir)，不自己猜后端的数据目录。
             */
            one.put("main", item.get("main"));
            one.put("path", item.get("path"));
            // 配置声明也要随贡献下发：槽位组件要用它给插件登记默认值（见 registerPluginConfigDefaults）
            one.put("config", item.get("config"));
            // ★ 资源前缀：界面插件的文件在 /plugin-web/{id}/file/，与 jar 插件的 /plugins/{id}/web/ 不同。
            //   前端 loader 据此拼 URL —— 少了这个字段，插件会 404 且只表现为"界面没出现"。
            one.put("assetBase", "plugin-web");
            out.add(one);
        }
        return out;
    }

    /** 安装（zip）—— 校验通过才落盘。 */
    public Map<String, Object> install(MultipartFile zip) {
        if (zip == null || zip.isEmpty()) {
            throw BizException.badRequest("请上传插件 zip 包");
        }
        Path tmp = baseDir.resolve(".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(tmp);
            unzipSafely(zip, tmp);

            Map<String, Object> manifest = readManifest(tmp);
            if (manifest == null) {
                throw BizException.badRequest("压缩包根目录缺少合法的 manifest.json（至少要有 id / slot / entry）");
            }
            String id = String.valueOf(manifest.get("id"));
            String entry = String.valueOf(manifest.get("entry"));
            if (!Files.isRegularFile(tmp.resolve(entry))) {
                throw BizException.badRequest("manifest 里的 entry 在压缩包中不存在：" + entry);
            }
            Object style = manifest.get("style");
            if (style != null && !String.valueOf(style).isBlank()
                    && !Files.isRegularFile(tmp.resolve(String.valueOf(style)))) {
                throw BizException.badRequest("manifest 里的 style 在压缩包中不存在：" + style);
            }

            Path target = baseDir.resolve(id).normalize();
            if (!target.startsWith(baseDir)) {
                throw BizException.badRequest("插件 id 非法：" + id);
            }
            deleteRecursively(target);          // 覆盖安装
            Files.move(tmp, target);
            setEnabled(id, true);
            log.info("[plugin-web] 已安装界面插件 {}（{}）", id, tmp.getFileName());
            // 覆盖安装时保留原有的挂载范围（用户设过就别重置）
            return toView(id, manifest, true, target, readAgents().getOrDefault(id, List.of()));
        } catch (IOException e) {
            throw BizException.internal("安装界面插件失败：" + e.getMessage(), e);
        } finally {
            deleteRecursively(tmp);             // 成功时 tmp 已被 move 走，这里只会清掉失败残留
        }
    }

    public void setEnabled(String id, boolean enabled) {
        if (id == null || !id.matches(SAFE_ID)) {
            throw BizException.badRequest("插件 id 非法：" + id);
        }
        if (!Files.isDirectory(baseDir.resolve(id))) {
            throw BizException.notFound("plugin-web", id);
        }
        Map<String, Boolean> state = readState();
        state.put(id, enabled);
        writeState(state);
    }

    /**
     * 设置挂载范围（2026-10-20）。
     *
     * @param agents 允许生效的智能体 id 列表；**空列表 = 全局**（与"没设置过"等价）。
     *               这里刻意不提供"禁用全局"之外的关闭手段 —— 关闭用 {@link #setEnabled}。
     */
    public void setAgents(String id, List<String> agents) {
        if (id == null || !id.matches(SAFE_ID)) {
            throw BizException.badRequest("插件 id 非法：" + id);
        }
        if (!Files.isDirectory(baseDir.resolve(id))) {
            throw BizException.notFound("plugin-web", id);
        }
        List<String> clean = new ArrayList<>();
        for (String a : agents == null ? List.<String>of() : agents) {
            if (a != null && !a.isBlank()) {
                clean.add(a.trim());
            }
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("agents", Map.of(id, clean));
        patchState(patch);
        log.info("[plugin-web] 插件 {} 的挂载范围已设为 {}（空=全局）", id, clean);
    }

    public void delete(String id) {
        if (id == null || !id.matches(SAFE_ID)) {
            throw BizException.badRequest("插件 id 非法：" + id);
        }
        Path dir = baseDir.resolve(id).normalize();
        if (!dir.startsWith(baseDir) || !Files.isDirectory(dir)) {
            throw BizException.notFound("plugin-web", id);
        }
        deleteRecursively(dir);
        Map<String, Boolean> state = readState();
        state.remove(id);
        writeState(state);
    }

    /** 读插件文件（`<id>/<path>`）；路径与扩展名白名单复用 jar 插件那套。 */
    public Optional<PluginWebAssetService.WebAsset> file(String id, String path) {
        if (id == null || !id.matches(SAFE_ID)) {
            return Optional.empty();
        }
        String safe = PluginWebAssetService.normalize(path);
        if (safe == null) {
            return Optional.empty();
        }
        String contentType = PluginWebAssetService.contentTypeOf(PluginWebAssetService.extensionOf(safe));
        if (contentType == null) {
            return Optional.empty();
        }
        Path dir = baseDir.resolve(id).normalize();
        if (!dir.startsWith(baseDir) || !Files.isDirectory(dir)) {
            return Optional.empty();
        }
        Path f = dir.resolve(safe).normalize();
        if (!f.startsWith(dir) || !Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PluginWebAssetService.WebAsset(Files.readAllBytes(f), contentType));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Object> toView(String id, Map<String, Object> manifest, boolean enabled, Path dir,
                                       List<String> agents) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        /*
         * 插件目录的绝对路径：桌面壳加载 `main.js`（Node 侧）时要用。
         * 本地单用户平台，暴露路径没有风险；它也避免让主进程去猜后端的工作目录。
         */
        m.put("path", dir == null ? "" : dir.toAbsolutePath().toString());
        // Node 侧入口文件名（可选）：manifest 里写 `main: "main.js"` 才有
        m.put("main", str(manifest.get("main"), ""));
        m.put("name", str(manifest.get("name"), id));
        m.put("version", str(manifest.get("version"), ""));
        m.put("description", str(manifest.get("description"), ""));
        m.put("slot", str(manifest.get("slot"), ""));
        m.put("label", str(manifest.get("label"), str(manifest.get("name"), id)));
        m.put("entry", str(manifest.get("entry"), ""));
        m.put("style", str(manifest.get("style"), ""));
        m.put("isolate", !Boolean.FALSE.equals(manifest.get("isolate")));
        m.put("order", manifest.get("order") instanceof Number n ? n.intValue() : 0);
        /*
         * 插件自定义配置项的**声明**（不是值）—— 2026-10-19 补。
         *
         * 宿主据此渲染设置表单（`PluginConfigModal`），值存在浏览器侧（localStorage，
         * 与 `ctx.storage` 同一套）。后端**不参与读写配置值**：界面插件本来就是本机安装的，
         * 它的配置跟着本机走最自然，也省掉一套"插件配置表"。
         */
        m.put("config", manifest.get("config"));
        m.put("enabled", enabled);
        /*
         * 挂载范围（2026-10-20）：空数组 = 全局。
         *
         * <p>但**只有声明了 `scope: "agent"` 的插件**才需要这个能力 ——
         * 通用插件（用量提示、桌宠这类）本来就该全平台生效，给它们显示"挂载到哪几个智能体"
         * 只是噪音：用户会以为每个插件都必须配置一遍。</p>
         *
         * <p>所以 `scope` 缺省为 `global`，前端据此决定显示不显示挂载入口。
         * 这是"平台给通道、插件声明意图"的延续。</p>
         */
        m.put("scope", str(manifest.get("scope"), "global"));
        m.put("agents", agents == null ? List.of() : agents);
        return m;
    }

    /** 当前智能体是否在作用域内：`agents` 为空 = 全局；`agentId` 为空 = 不做过滤（兼容）。 */
    private static boolean inScope(Object agents, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return true;
        }
        if (!(agents instanceof List<?> list) || list.isEmpty()) {
            return true;   // 未设置 = 全局
        }
        for (Object a : list) {
            if (a != null && agentId.equals(String.valueOf(a))) {
                return true;
            }
        }
        return false;
    }

    /** 读目录下的 manifest；不合法（缺 id/slot/entry 或 id 非法）返回 null。 */
    private Map<String, Object> readManifest(Path dir) {
        Path f = dir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            Map<?, ?> raw = JsonUtils.fromJson(Files.readString(f, StandardCharsets.UTF_8), Map.class);
            if (raw == null) {
                return null;
            }
            Object id = raw.get("id");
            Object slot = raw.get("slot");
            Object entry = raw.get("entry");
            if (id == null || slot == null || entry == null) {
                return null;
            }
            String idStr = String.valueOf(id);
            String entryStr = String.valueOf(entry);
            if (!idStr.matches(SAFE_ID) || entryStr.isBlank() || entryStr.contains("..")) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        } catch (Exception e) {
            log.warn("[plugin-web] manifest 解析失败（{}）：{}", dir.getFileName(), e.getMessage());
            return null;
        }
    }

    private void unzipSafely(MultipartFile zip, Path target) throws IOException {
        int count = 0;
        try (ZipInputStream zin = new ZipInputStream(zip.getInputStream())) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name == null || name.isBlank()) {
                    zin.closeEntry();
                    continue;
                }
                String rel = safeEntryName(name);
                if (rel == null) {
                    throw BizException.badRequest("压缩包含非法路径：" + name);
                }
                if (++count > MAX_FILES) {
                    throw BizException.badRequest("压缩包文件过多（上限 " + MAX_FILES + " 个）");
                }
                Path out = target.resolve(rel).normalize();
                if (!out.startsWith(target)) {
                    throw BizException.badRequest("压缩包含非法路径：" + name);
                }
                if (out.getParent() != null) {
                    Files.createDirectories(out.getParent());
                }
                try (OutputStream os = Files.newOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    long total = 0;
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_FILE_BYTES) {
                            throw BizException.badRequest("压缩包内单个文件超过 " + (MAX_FILE_BYTES / 1024 / 1024) + "MB：" + rel);
                        }
                        os.write(buf, 0, n);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    /**
     * zip 条目名 → 安全相对路径；含 {@code ..} / 空段 / 绝对路径一律 null。
     *
     * <p>刻意与 {@link PluginWebAssetService#normalize} 分开：那个用于"读取已存在的文件"，
     * 允许归一化（{@code a/../b} → {@code b}）；这里用于"写入新文件"，归一化会让两个条目
     * 落到同一个路径 —— 那是覆盖，不是越界，但同样不可接受。</p>
     */
    static String safeEntryName(String raw) {
        String rel = raw.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (rel.isEmpty() || rel.indexOf('\0') >= 0) {
            return null;
        }
        for (String seg : rel.split("/")) {
            if (seg.isEmpty() || "..".equals(seg)) {
                return null;
            }
        }
        return rel;
    }

    private Map<String, Boolean> readState() {
        Path f = baseDir.resolve(STATE_FILE);
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            Map<?, ?> raw = JsonUtils.fromJson(Files.readString(f, StandardCharsets.UTF_8), Map.class);
            if (raw != null && raw.get("enabled") instanceof Map<?, ?> enabled) {
                enabled.forEach((k, v) -> out.put(String.valueOf(k), Boolean.TRUE.equals(v)));
            }
        } catch (Exception e) {
            log.warn("[plugin-web] 状态文件解析失败（按全部启用处理）：{}", e.getMessage());
        }
        return out;
    }

    private void writeState(Map<String, Boolean> enabled) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("enabled", enabled);
            Files.writeString(baseDir.resolve(STATE_FILE), JsonUtils.toJson(payload), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 状态写不进去不该让安装/启停失败：下次启动会退回"全部启用"
            log.warn("[plugin-web] 写入状态文件失败：{}", e.getMessage());
        }
    }

    /** 插件 → 允许生效的智能体列表（空 = 全局）。 */
    private Map<String, List<String>> readAgents() {
        Path f = baseDir.resolve(STATE_FILE);
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            Map<?, ?> raw = JsonUtils.fromJson(Files.readString(f, StandardCharsets.UTF_8), Map.class);
            if (raw != null && raw.get("agents") instanceof Map<?, ?> agents) {
                agents.forEach((k, v) -> {
                    List<String> list = new ArrayList<>();
                    if (v instanceof List<?> l) {
                        for (Object o : l) {
                            if (o != null && !String.valueOf(o).isBlank()) {
                                list.add(String.valueOf(o));
                            }
                        }
                    }
                    out.put(String.valueOf(k), list);
                });
            }
        } catch (Exception e) {
            // 文件被手工改坏：当作"全部全局"，不影响插件继续工作
            log.warn("[plugin-web] 挂载范围解析失败（按全局处理）：{}", e.getMessage());
        }
        return out;
    }

    /**
     * 局部更新状态文件。
     *
     * <p><b>必须先读再合并</b>：`enabled` 与 `agents` 在同一个文件里，直接整份覆盖会把另一项抹掉
     * （曾导致"设了挂载范围，插件启停状态被重置"这类难查的问题）。</p>
     */
    private void patchState(Map<String, Object> patch) {
        Path f = baseDir.resolve(STATE_FILE);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (Files.isRegularFile(f)) {
            try {
                Map<?, ?> raw = JsonUtils.fromJson(Files.readString(f, StandardCharsets.UTF_8), Map.class);
                if (raw != null) {
                    raw.forEach((k, v) -> payload.put(String.valueOf(k), v));
                }
            } catch (Exception e) {
                log.warn("[plugin-web] 读取状态文件失败（将重建）：{}", e.getMessage());
            }
        }
        // 深合并 agents：只替换 patch 里出现的那些 id，其余插件的范围保持不变
        if (patch.get("agents") instanceof Map<?, ?> incoming) {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (payload.get("agents") instanceof Map<?, ?> existing) {
                existing.forEach((k, v) -> merged.put(String.valueOf(k), v));
            }
            incoming.forEach((k, v) -> merged.put(String.valueOf(k), v));
            patch = new LinkedHashMap<>(patch);
            patch.put("agents", merged);
        }
        payload.putAll(patch);
        try {
            Files.writeString(f, JsonUtils.toJson(payload), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[plugin-web] 写入状态文件失败：{}", e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件删不掉（被占用）不阻断：残留目录下次安装会被覆盖
                }
            });
        } catch (IOException e) {
            log.warn("[plugin-web] 清理目录失败 {}：{}", dir, e.getMessage());
        }
    }

    private static String str(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }
}
