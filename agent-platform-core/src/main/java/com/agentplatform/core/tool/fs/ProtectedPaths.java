package com.agentplatform.core.tool.fs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 受保护路径 —— **凭证隔离**。{@code fs_*} 工具组的第二道边界。
 *
 * <h3>为什么需要它（工作区约束挡不住这一层）</h3>
 * {@link WorkspaceService} 解决的是"**别碰工作区外的东西**"；而这一层解决
 * "**工作区内的有些文件，连碰都不该碰**"。前者是范围问题，后者是敏感度问题 ——
 * 一个完全合法的路径 {@code ./.env} 就躺在工作区里，但读它等于把密钥交给模型。
 *
 * <h3>两类名单，一条防线</h3>
 * <ol>
 *   <li><b>凭证（读/写都拒）</b>：{@code .env}、{@code *.key}、{@code *.pem}、
 *       {@code id_rsa}、{@code .npmrc}、{@code .ssh/}、{@code .aws/} 等。
 *       读它 = 密钥进入模型上下文、随日志/会话/追踪四处扩散；
 *       写它 = 把凭据替换成攻击者控制的值（比把密码删掉更隐蔽）；</li>
 *   <li><b>提权路径（读/写都拒）</b>：{@code .bashrc}、{@code .gitconfig}、
 *       {@code .git/hooks/}、以及能在目录里"伪装成 git 仓库"的顶层 {@code HEAD}/{@code objects}/{@code refs}。
 *       这些文件被写一次，就换来"**下次运行时执行任意代码**"的能力 ——
 *       比直接删文件危险得多，而且改动很小、很难在 diff 里被注意到。</li>
 * </ol>
 *
 * <p>两类合在一份名单里，是因为它们的处置方式完全一致（**一律拒绝、不予豁免**），
 * 拆成两套机制只会多出一个能被绕过的缝。</p>
 *
 * <h3>★ 为什么不在审批里放行</h3>
 * 平台的其他写操作都可以"人看一眼再放行"，这两类**刻意不行**：
 * 读取是**已经发生**的动作 —— 一旦内容返回给模型，它就已经进入了会话历史、
 * 短期缓存、日志与可能的向量索引，之后无论拒绝多少次都收不回来。
 * 所以这里只提供"允许访问"或"拒绝"，没有"批准"这个选项。
 * 真要处理这些文件，由用户自己动手。</p>
 *
 * <h3>名单只能加不能减</h3>
 * {@code extra-deny} 是**追加**项，默认项不可通过配置移除 ——
 * 否则一个被提示注入（prompt injection）驱动的模型只要改一行配置
 * 就能解除自己的约束。这与 Claude Code 对"受保护路径"的处理一致
 * （其 {@code allowWrite} 也无法豁免这些路径）。</p>
 */
@Slf4j
@Service
public class ProtectedPaths {

    /**
     * 总开关（默认开）。
     *
     * <p>关掉等于放弃整层凭证隔离，只建议在"工作区里根本没有敏感文件"的
     * 临时排查场景下使用，并且要清楚此时 {@code .env} 是能被模型读到的。</p>
     */
    @Value("${agent-platform.agent.workspace.protected-paths.enabled:true}")
    private boolean enabled = true;

    /**
     * 追加的受保护模式（逗号分隔，支持 glob，如 {@code application-prod.yml,secrets/*}）。
     *
     * <p>只能**追加**，不能移除下面的默认项。</p>
     */
    @Value("${agent-platform.agent.workspace.protected-paths.extra-deny:}")
    private List<String> extraDeny = List.of();

    /** 惰性构建的 glob 匹配器（本类可能被直接 new 出来，所以不用 @PostConstruct）。 */
    private volatile List<PathMatcher> extraMatchers;

    // ------------------------------------------------------------------ 名单

    /**
     * 凭证类**文件名**（精确匹配，大小写不敏感）。
     *
     * <p>都是"文件名本身就说明它是秘密"的那些，不靠内容判断 ——
     * 靠内容猜（比如"看起来像 base64"）会产生大量假阳性，
     * 而安全机制一旦开始误伤，人就会把它关掉，最后等于没有。</p>
     */
    private static final Set<String> CREDENTIAL_FILES = Set.of(
            // 各类包管理器/工具存放 token 的文件
            ".npmrc", ".yarnrc", ".pypirc", ".netrc", "_netrc", ".htpasswd",
            ".mcp.json",                      // MCP server 的启动命令与 env（常含 token）
            // OpenSSH 私钥（公钥 .pub 不在此列，它不是秘密）
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
            // 云厂商/工具链凭据
            "credentials.json", ".credentials.json",
            "secring.gpg", "trustdb.gpg"
    );

    /**
     * 凭证类**扩展名**（密钥与密钥库格式，它们没有"看起来无害"的可能）。
     *
     * <p>{@code .pem} 也包含公钥/证书 —— 一并拒掉是刻意的：模型没有读证书的需求，
     * 而"区分公私钥"需要解析内容，多做一步判断就多一个出错的机会。</p>
     */
    private static final Set<String> CREDENTIAL_EXTS = Set.of(
            "key", "pem", "p12", "pfx", "jks", "keystore", "jceks", "ppk", "asc", "kdbx");

    /**
     * 凭证类**目录**（出现在路径任意层级即拒绝）。
     *
     * <p>整个目录拒掉，是因为这些目录里的文件名五花八门
     * （{@code .aws} 下可能是 {@code credentials} 也可能是 {@code config}），
     * 逐个列名字一定会漏。</p>
     */
    private static final Set<String> CREDENTIAL_DIRS = Set.of(
            ".ssh", ".aws", ".gnupg", ".gpg", ".kube", ".docker", ".azure", ".secrets");

    /**
     * 提权类**文件**：写一次就换来"下次运行执行任意代码"。
     *
     * <p>Shell 启动文件（{@code .bashrc} 等）在每次开新 shell 时执行；
     * {@code .gitconfig} 里能挂 {@code core.hooksPath} 与 {@code alias}；
     * {@code .ripgreprc}/{@code .curlrc} 这类是**别的工具**的配置，
     * 效果相同 —— 换个下次会被执行的入口而已。</p>
     */
    private static final Set<String> PRIVILEGE_FILES = Set.of(
            ".bashrc", ".bash_profile", ".bash_login", ".bash_logout",
            ".zshrc", ".zprofile", ".zshenv", ".zlogin", ".zlogout",
            ".profile", ".kshrc", ".cshrc", ".tcshrc",
            ".gitconfig", ".gitmodules", ".ripgreprc", ".wgetrc", ".curlrc");

    /**
     * Agent **自身**的配置目录：改它等于改写对自己生效的规则/指令。
     *
     * <p>{@code .codebuddy} 是本项目的约定目录（工作记忆等），同理纳入。</p>
     */
    private static final Set<String> AGENT_CONFIG_DIRS = Set.of(
            ".claude", ".codex", ".cursor", ".codebuddy", ".agents");

    /**
     * "把目录伪装成 git 仓库"的顶层条目。
     *
     * <p>为什么这是个真实威胁：git 会向**上层目录**查找仓库，所以只要在某个目录里
     * 放上 {@code HEAD}+{@code objects}+{@code refs}，在那里执行 git 命令时
     * 它就会被当作仓库根，进而可能触发其中被植入的钩子 —— 一条不经过
     * {@code .git/hooks} 的执行路径。</p>
     *
     * <p><b>只在工作区顶层生效</b>，且故意**不含** {@code config} / {@code hooks}：
     * 这两个名字在普通项目里太常见（各种工具都有自己的 {@code config}），
     * 加进来会频繁误伤。而伪装的必要条件是三者同时存在，挡住任意一个就够。</p>
     */
    private static final Set<String> BARE_REPO_ENTRIES = Set.of(
            "head", "objects", "refs", "packed-refs");

    /**
     * {@code .env} 及其全部变体（{@code .env} / {@code .env.local} / {@code .env.production} …）。
     *
     * <p>刻意**包含** {@code .env.example} / {@code .env.template} 这类模板：
     * 它们通常只有占位符，但一来无法可靠区分"模板"与"填了真值的模板"，
     * 二来模型不读它们也能正常工作。多留一个豁免就是多留一条绕过路径。</p>
     */
    private static final Pattern ENV_LIKE = Pattern.compile("^\\.env(\\..+)?$");

    // ------------------------------------------------------------------ 判定

    /**
     * 判定工作区内的某个路径是否受保护。
     *
     * @param candidate 待判定的路径（通常是绝对路径，必须位于工作区内）
     * @param root      工作区根（用于计算相对路径）
     * @return **拒绝原因**（可直接拼给用户/模型看）；{@code null} 表示可以访问
     */
    public String reason(Path candidate, Path root) {
        if (!enabled || candidate == null) {
            return null;
        }
        Path rel = relativize(candidate, root);
        if (rel == null || rel.getNameCount() == 0) {
            return null;   // 就是工作区根本身
        }
        int last = rel.getNameCount() - 1;
        for (int i = 0; i <= last; i++) {
            String raw = rel.getName(i).toString();
            String seg = normalize(raw);
            if (seg.isEmpty()) {
                continue;
            }
            // 1) 凭证 / agent 配置目录 —— 任意层级都拒
            if (CREDENTIAL_DIRS.contains(seg)) {
                return "它位于凭据目录 " + raw + " 下（该目录整体受保护）";
            }
            if (AGENT_CONFIG_DIRS.contains(seg)) {
                return "它位于智能体自身的配置目录 " + raw + " 下，改动会直接改写对本次运行生效的规则";
            }
            // 2) 提权类文件 —— 任意层级都拒
            if (PRIVILEGE_FILES.contains(seg)) {
                return "它是 shell / 工具的启动或配置文件，被改写后会在下次运行时执行任意代码";
            }
            // 3) 顶层"伪装成 git 仓库"的条目
            if (i == 0 && BARE_REPO_ENTRIES.contains(seg)) {
                return "顶层名为 " + raw + " 的条目可让该目录被 git 当作仓库，进而触发其中被植入的钩子脚本";
            }
            // 4) 文件名 / 后缀判定（只看最后一段；父目录已经在上面按目录名拒过了）
            if (i == last) {
                if (CREDENTIAL_FILES.contains(seg)) {
                    return "它是存放凭据 / 令牌的约定文件";
                }
                if (ENV_LIKE.matcher(seg).matches()) {
                    return "它以 .env 开头，是存放密钥与令牌的约定文件";
                }
                String ext = extensionOf(seg);
                if (ext != null && CREDENTIAL_EXTS.contains(ext)) {
                    return "它的扩展名 ." + ext + " 属于密钥或密钥库格式";
                }
                String extra = matchExtra(raw, rel);
                if (extra != null) {
                    return extra;
                }
            }
        }
        return null;
    }

    /** 目录名是否受保护（供遍历时 {@code SKIP_SUBTREE} 用，避免进都不进）。 */
    public boolean isProtectedDirName(String name) {
        if (!enabled || name == null) {
            return false;
        }
        String seg = normalize(name);
        return CREDENTIAL_DIRS.contains(seg) || AGENT_CONFIG_DIRS.contains(seg);
    }

    /**
     * 拒绝访问时给用户/模型的说明。
     *
     * <p>刻意把"**为什么不能批准**"写清楚：如果只说"禁止访问"，
     * 模型会反复换路径重试（换大小写、换相对写法），白白耗掉几轮工具调用；
     * 说清"这是刻意的、不可豁免"，它才会停下来转向别的手段。</p>
     */
    public String denyMessage(String shownPath, String reason) {
        return "拒绝访问「" + shownPath + "」：" + reason + "。\n"
                + "这属于平台的**凭证隔离**名单（受保护路径），它有意不参与工具审批、**无法豁免** —— "
                + "因为这些内容一旦被读取就已经进入模型上下文、会话记录与日志，之后再拒绝也收不回来。\n"
                + "**请不要重复尝试访问它**（换写法、换大小写同样会被拒）。"
                + "如果确实需要处理这个文件，请由用户自己操作。";
    }

    /** 供日志/文档/界面展示的名单摘要。 */
    public List<String> summary() {
        List<String> out = new ArrayList<>();
        out.add("凭据文件：" + String.join("、", CREDENTIAL_FILES));
        out.add("凭据扩展名：." + String.join("、.", CREDENTIAL_EXTS));
        out.add("凭据目录：" + String.join("、", CREDENTIAL_DIRS));
        out.add("提权风险文件：" + String.join("、", PRIVILEGE_FILES));
        out.add("智能体配置目录：" + String.join("、", AGENT_CONFIG_DIRS));
        out.add(".env 全部变体（含 .env.example 模板）");
        out.add("工作区顶层的 " + String.join(" / ", BARE_REPO_ENTRIES) + "（防 git 仓库伪装）");
        if (extraDeny != null && !extraDeny.isEmpty()) {
            out.add("配置追加：" + String.join("、", extraDeny));
        }
        return out;
    }

    public boolean enabled() {
        return enabled;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 归一文件名：去尾随**点与空格** + 转小写。
     *
     * <p><b>★ 实测发现（2026-09-23）</b>：原本担心 {@code ".env "} / {@code ".env."} 能绕过名单
     * （Windows 的 Win32 API 会忽略文件名末尾的空格与点，这三个写法打开的是同一个文件）。
     * 但写测试时发现 —— <b>Java 的 {@code Path.of()} 在 Windows 上直接拒绝这类名字</b>
     * （{@code InvalidPathException: Trailing char < > at index 4}），
     * 所以这条绕过路径**根本到不了本类**，{@code Files} 层面的操作也构造不出它。</p>
     *
     * <p>因此下面去尾随字符的处理是**兜底而非必需**，保留的理由有两个：
     * Unix 上尾随空格是合法字符（是另一个文件，会被某些工具混用）；
     * 以及将来若换成别的路径构造方式（自己拼字符串），JDK 那层保护就不存在了。
     * 留着它成本近零，去掉它则要在很久以后重新推理一遍这个结论。</p>
     *
     * <p>大小写归一则是**必需**的：Windows 文件系统不区分大小写，{@code .ENV} 就是 {@code .env}，
     * 而字符串比较区分大小写 —— 不做这一步，换个大小写就能绕过整份名单。</p>
     */
    private static String normalize(String name) {
        String s = name == null ? "" : name.trim();
        while (!s.isEmpty() && (s.endsWith(".") || s.endsWith(" "))) {
            s = s.substring(0, s.length() - 1);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static String extensionOf(String seg) {
        int dot = seg.lastIndexOf('.');
        if (dot < 0 || dot == seg.length() - 1) {
            return null;
        }
        return seg.substring(dot + 1);
    }

    /**
     * 计算相对路径。
     *
     * <p>两侧的"根"可能不一致：{@code resolve()} 在 {@code mustExist=true} 时会把路径
     * 规范成 {@code toRealPath()} 的结果，而配置里的工作区根若本身是个符号链接，
     * 两者就落在不同的文件系统根上，{@code relativize} 会抛异常。
     * 这种（罕见的）情况下**退化为拿整条绝对路径去判定** —— 宁可多查几段
     * （最坏是把某个恰好叫 {@code objects} 的父目录也拦下），也不能漏检。</p>
     */
    private static Path relativize(Path candidate, Path root) {
        if (root == null) {
            return candidate;
        }
        try {
            return root.relativize(candidate);
        } catch (IllegalArgumentException e) {
            return candidate;
        }
    }

    private String matchExtra(String rawName, Path rel) {
        List<PathMatcher> matchers = extraMatchers;
        if (matchers == null) {
            synchronized (this) {
                if (extraMatchers == null) {
                    extraMatchers = buildExtraMatchers();
                }
                matchers = extraMatchers;
            }
        }
        if (matchers.isEmpty()) {
            return null;
        }
        for (PathMatcher m : matchers) {
            try {
                if (m.matches(Path.of(rawName)) || m.matches(rel)) {
                    return "它匹配了配置中追加的受保护模式"
                            + "（agent-platform.agent.workspace.protected-paths.extra-deny）";
                }
            } catch (Exception ignored) {
                // 单个模式匹配失败（文件名含非法字符等）不影响其余模式
            }
        }
        return null;
    }

    private List<PathMatcher> buildExtraMatchers() {
        if (extraDeny == null || extraDeny.isEmpty()) {
            return List.of();
        }
        List<PathMatcher> out = new ArrayList<>();
        for (String raw : extraDeny) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                out.add(FileSystems.getDefault().getPathMatcher("glob:" + raw.trim()));
            } catch (Exception e) {
                // 配错了就跳过并留痕：不要因为一个笔误让整层隔离失效
                log.warn("[fs] 忽略非法的 extra-deny 模式「{}」：{}", raw, e.getMessage());
            }
        }
        return out;
    }
}
