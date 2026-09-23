package com.agentplatform.core.tool.fs;

import com.agentplatform.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工作区（workspace）根约束 —— `fs_*` 工具的**唯一安全边界**。
 *
 * <p>这是"让智能体改文件"这条路的地基：智能体只能看见并操作工作区内的文件，
 * 越界一律拒绝。所有路径校验都必须走这里，**不要在工具里各写一套** ——
 * 五处各写一套的下场是其中一处漏了 {@code toRealPath}，而后果是能读到 {@code C:\Users\...}。</p>
 *
 * <h3>四道防线（缺一道就能被绕过）</h3>
 * <ol>
 *   <li><b>{@code normalize()} + {@code startsWith(root)}</b> —— 挡掉 {@code ../..} 这类
 *       纯字符串穿越。这是项目里已有的成熟模式（skins/skills/plugins/storage 都这么写）；</li>
 *   <li><b>{@code toRealPath()} 之后再校验一次</b> —— 挡掉**符号链接**。
 *       只做第 1 道是不够的：工作区内一个指向 {@code /etc/passwd} 的软链，
 *       字符串上完全"合法"，但读出来的是工作区外的文件。
 *       这是本项目此前那套模式**没有覆盖**的场景，必须补上；</li>
 *   <li><b>拒绝名单 + 大小上限</b> —— 挡掉"合法但无意义且昂贵"的访问：
 *       {@code .git} 内部文件、{@code node_modules}（几十万文件会把上下文撑爆）、
 *       超大文件（塞进模型上下文只会浪费 token）；</li>
 *   <li><b>凭证隔离</b>（见 {@link ProtectedPaths}）—— 挡掉"合法但**敏感**"的访问。
 *       前 3 道解决的是"够不着工作区外"，这一道解决的是"工作区里的
 *       {@code .env} / {@code .ssh/} / {@code .bashrc} 同样不该碰"。</li>
 * </ol>
 *
 * <h3>为什么用配置根而不是"会话级根"</h3>
 * 会话级工作区（每个会话一个根）更灵活，但要求 {@code Session} 加字段 + 前端能选目录 +
 * 迁移 + 权限，是一次完整改造。当前先做**配置级根**（一台部署一个工作区），
 * 已经能覆盖"让智能体帮我读改这个项目的代码"这个主场景；
 * 会话级作为后续演进，不影响本类的对外接口（{@link #resolve} 的签名不用变）。
 */
@Slf4j
@Service
public class WorkspaceService {

    /** 总开关：关掉后所有 {@code fs_*} 工具直接拒绝，便于在生产环境临时禁用。 */
    @Value("${agent-platform.agent.workspace.enabled:true}")
    private boolean enabled;

    /** 工作区根目录（相对路径按进程工作目录解析）。 */
    @Value("${agent-platform.agent.workspace.root:./data/workspace}")
    private String rootConfig;

    /** 单文件读取上限（字节）。默认 256KB —— 再大塞进上下文只会浪费 token。 */
    @Value("${agent-platform.agent.workspace.max-read-bytes:262144}")
    private int maxReadBytes;

    /** 单次目录列举 / glob 的条目上限。 */
    @Value("${agent-platform.agent.workspace.max-entries:500}")
    private int maxEntries;

    /** 单次 grep 的命中上限。 */
    @Value("${agent-platform.agent.workspace.max-matches:200}")
    private int maxMatches;

    /** grep 单行输出的截断长度。 */
    @Value("${agent-platform.agent.workspace.max-line-chars:300}")
    private int maxLineChars;

    /**
     * 遍历时跳过的目录名。
     *
     * <p>{@code .git} 是版本库内部结构（模型读它没有任何意义，还会读到 packed refs 之类二进制）；
     * {@code node_modules} 动辄几十万文件，一旦被 glob/grep 扫到会直接打爆时间与上下文；
     * {@code target} / {@code dist} / {@code build} 是构建产物，同理。</p>
     */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "target", "dist", "build", ".idea", ".gradle");

    /** 认定的文本文件后缀（不在表内的一律当二进制跳过，避免把乱码喂给模型）。 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "kt", "scala", "groovy",
            "js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte",
            "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cc", "cs", "php", "swift", "m", "mm",
            "html", "htm", "css", "scss", "sass", "less",
            "json", "yaml", "yml", "toml", "ini", "properties", "conf", "cfg", "env",
            "xml", "xsd", "xsl", "svg",
            "md", "markdown", "txt", "rst", "adoc",
            "sql", "sh", "bash", "zsh", "bat", "cmd", "ps1",
            "gradle", "pom", "lock", "gitignore", "dockerfile", "makefile", "editorconfig");

    /** 无后缀但通常是文本的文件名（小写比较）。 */
    private static final Set<String> TEXT_FILENAMES = Set.of(
            "makefile", "dockerfile", ".gitignore", ".gitattributes", ".editorconfig",
            "license", "readme", "changelog", "notice");

    /**
     * 凭证隔离（受保护路径）—— 第四道防线，与前三道**语义不同**。
     *
     * <p>前三道是"范围"（够不着工作区外），这一道是"敏感度"：
     * {@code ./.env} 是一个完全合法的工作区内路径，但读它等于把密钥交给模型。</p>
     *
     * <p><b>为什么是 {@code @Autowired(required=false)} 加一个直接 new 的默认实例</b>：
     * 本类被单元测试以 {@code new WorkspaceService()} + 反射设字段的方式构造，
     * 改构造注入会连带改掉所有测试的写法。给默认实例的好处是 ——
     * **没被 Spring 管时这层保护照样生效**（{@link ProtectedPaths} 的默认开关就是开的），
     * 容器里存在同类型 Bean 时又会被注入覆盖。
     * 安全组件不该依赖"装配成功"才起作用。</p>
     */
    @Autowired(required = false)
    private ProtectedPaths protectedPaths = new ProtectedPaths();

    private volatile Path root;

    // ------------------------------------------------------------------ 对外

    /** 工作区是否可用（总开关打开）。 */
    public boolean available() {
        return enabled;
    }

    /**
     * 工作区根目录（惰性创建）。
     *
     * <p>惰性而不是 {@code @PostConstruct}：桌面版以 {@code lazy-initialization=true} 启动，
     * 本类若不被依赖就不会被实例化 —— 放在构造/初始化里反而可能不执行。
     * 首次取用时创建目录，语义更直观。</p>
     */
    public Path root() {
        Path r = root;
        if (r != null) {
            return r;
        }
        synchronized (this) {
            if (root == null) {
                Path p = Path.of(rootConfig == null || rootConfig.isBlank()
                        ? "./data/workspace" : rootConfig).toAbsolutePath().normalize();
                try {
                    Files.createDirectories(p);
                } catch (IOException e) {
                    log.warn("[fs] 工作区目录创建失败（{}）：{}", p, e.getMessage());
                }
                root = p;
                log.info("[fs] 工作区根目录：{}", p);
            }
            return root;
        }
    }

    /**
     * 把用户/模型给的相对路径解析成工作区内的**真实**路径，越界即抛异常。
     *
     * @param relative 相对路径（也容忍以 {@code /} 或 {@code .} 开头）
     * @param mustExist 要求路径已存在（读/列目录场景为 true；将来写文件时为 false）
     */
    public Path resolve(String relative, boolean mustExist) {
        requireEnabled();
        Path base = root();
        String rel = relative == null ? "" : relative.trim().replace('\\', '/');
        // 去掉开头的 ./ 与 /，让 "/src/a.java"、"src/a.java"、"./src/a.java" 等价
        while (rel.startsWith("./")) {
            rel = rel.substring(2);
        }
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        // 绝对路径直接拒绝：Path.resolve 遇到绝对路径会**丢弃** base，必须显式挡掉
        if (rel.contains(":") || rel.startsWith("~")) {
            throw BizException.badRequest("工作区内的路径必须是相对路径，不能是绝对路径或含盘符：" + relative);
        }

        Path candidate = base.resolve(rel).normalize();
        // 防线 1：字符串层面的越界
        if (!candidate.startsWith(base)) {
            throw BizException.forbidden("路径超出工作区范围：" + relative);
        }
        // 防线 2：符号链接（只对已存在的路径有意义 —— toRealPath 要求路径存在）
        if (mustExist) {
            if (!Files.exists(candidate)) {
                throw BizException.notFound("路径", relative == null || relative.isBlank() ? "(工作区根)" : relative);
            }
            try {
                Path real = candidate.toRealPath();
                if (!real.startsWith(base.toRealPath())) {
                    throw BizException.forbidden("路径经符号链接指向了工作区外：" + relative);
                }
                candidate = real;
            } catch (IOException e) {
                throw BizException.badRequest("无法解析路径：" + relative + "（" + e.getMessage() + "）");
            }
        }
        if (isSkipped(candidate)) {
            throw BizException.forbidden("该目录已在跳过名单内（版本库/依赖/构建产物的内部文件不对外开放）：" + relative);
        }
        // 防线 4：凭证隔离。注意它检查的是**已归一的候选路径**（mustExist 时即 toRealPath 结果），
        // 所以"软链名看起来无害、实际指向 id_rsa"这种写法也会被认出来。
        String guarded = protectedPaths.reason(candidate, base);
        if (guarded != null) {
            log.warn("[fs] 拒绝访问受保护路径（凭证隔离）：{} —— {}", relative, guarded);
            throw BizException.forbidden(protectedPaths.denyMessage(relative, guarded));
        }
        return candidate;
    }

    /** 转成工作区内的相对路径（用于展示，统一用 {@code /}）。 */
    public String relative(Path p) {
        try {
            return root().relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return p.toString().replace('\\', '/');
        }
    }

    public boolean isSkipped(Path p) {
        for (Path part : root().relativize(p)) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 遍历目录时是否该跳过该子目录。
     *
     * <p>除版本库/依赖/构建产物外，也跳过凭据与智能体配置目录 ——
     * 让 glob/grep **根本不进** {@code .ssh}、{@code .aws} 这类目录：
     * 既省遍历开销，也避免"只泄露文件名单"这种边缘暴露。</p>
     */
    public boolean shouldSkipDir(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        return SKIP_DIRS.contains(name) || protectedPaths.isProtectedDirName(name);
    }

    /**
     * 是否按文本处理。
     *
     * <p>两种判据：扩展名在白名单 / 文件名在名单（Makefile、LICENSE 这类无后缀）；
     * 都不匹配时**再按内容嗅探**：读到 NUL 字节就当二进制。
     * 只按扩展名会漏掉大量无后缀的配置文件，只按内容嗅探则每次都读盘 —— 两者结合最省。</p>
     */
    public boolean isTextFile(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        if (!ext.isEmpty() && TEXT_EXTENSIONS.contains(ext)) {
            return true;
        }
        if (TEXT_FILENAMES.contains(name.toLowerCase())) {
            return true;
        }
        if (dot < 0) {
            // 无后缀且不在名单里 → 嗅探
            return !looksBinary(p);
        }
        return false;
    }

    /** 嗅探前 4KB 是否含 NUL 字节（通用二进制判据）。 */
    private boolean looksBinary(Path p) {
        try (var in = Files.newInputStream(p)) {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
        } catch (IOException e) {
            return true;   // 读不了就当二进制，别让它进上下文
        }
        return false;
    }

    /** 二进制/图片等按扩展名直接排除（模型看不了，且会污染 grep 结果）。 */
    private static final Pattern BINARY_EXT = Pattern.compile(
            "(?i)\\.(png|jpe?g|gif|bmp|ico|webp|tiff?|svgz|zip|gz|tgz|bz2|xz|7z|rar|jar|war|class|"
                    + "exe|dll|so|dylib|bin|o|obj|a|lib|pdf|docx?|xlsx?|pptx?|mp[34]|wav|avi|mov|"
                    + "mkv|flv|ttf|otf|woff2?|eot|db|sqlite|mv\\.db|lock)$");

    public boolean isBinaryByName(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        return BINARY_EXT.matcher(name).find();
    }

    /** 是否是可读的文本文件（综合判据，给 glob/grep 用）。 */
    public boolean readableText(Path p) {
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(p)) {
            return false;
        }
        // 受保护路径按"不可读"处理。
        // 单文件访问（read/edit）到不了这里 —— 它们在 resolve() 就被挡下并给出明确原因；
        // 这一句是给**遍历场景**兜底的：grep 是逐个文件判定的，不会对每个文件再走一遍
        // resolve()，不在这里排除的话 .env 的内容会被直接搜出来。
        if (protectedPaths.reason(p, root()) != null) {
            return false;
        }
        if (isBinaryByName(p)) {
            return false;
        }
        return isTextFile(p);
    }

    public int maxReadBytes() {
        return Math.max(1024, maxReadBytes);
    }

    public int maxEntries() {
        return Math.max(1, maxEntries);
    }

    public int maxMatches() {
        return Math.max(1, maxMatches);
    }

    public int maxLineChars() {
        return Math.max(40, maxLineChars);
    }

    /** 供列表展示的跳过名单（前端/文档说明用）。 */
    public List<String> skipDirs() {
        return List.copyOf(SKIP_DIRS);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw BizException.forbidden("文件工具已被关闭（agent-platform.agent.workspace.enabled=false）");
        }
    }
}
