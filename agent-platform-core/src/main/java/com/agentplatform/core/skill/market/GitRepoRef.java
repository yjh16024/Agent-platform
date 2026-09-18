package com.agentplatform.core.skill.market;

import com.agentplatform.common.exception.BizException;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub 仓库引用：把用户粘贴的**各种形式的地址**解析成 {@code owner/repo + 分支 + 子路径}。
 *
 * <p>支持的输入（协议可省、大小写不敏感）：</p>
 * <ul>
 *   <li>{@code owner/repo}</li>
 *   <li>{@code github.com/owner/repo}</li>
 *   <li>{@code https://github.com/owner/repo/tree/main/skills}</li>
 *   <li>{@code https://github.com/owner/repo/blob/main/skills/docx/SKILL.md}（取父目录来扫）</li>
 *   <li>{@code https://raw.githubusercontent.com/owner/repo/main/skills/docx/SKILL.md}</li>
 *   <li>{@code https://cdn.jsdelivr.net/gh/owner/repo@main/skills}</li>
 *   <li>{@code https://data.jsdelivr.com/v1/packages/gh/owner/repo@main}</li>
 *   <li>{@code git@github.com:owner/repo.git}</li>
 *   <li><b>任何套了加速前缀的上述地址</b>（如 {@code https://gh-proxy.com/https://github.com/...}）</li>
 * </ul>
 *
 * <p>这是"让用户直接贴网址读技能库"能力的第一步：地址形态千奇百怪，先归一化再取文件。</p>
 */
public record GitRepoRef(String owner, String repo, String branch, String subPath) {

    /** 命中"真实仓库地址"的起始位置，用于剥掉加速前缀。 */
    private static final Pattern REPO_START = Pattern.compile(
            "(github\\.com/|raw\\.githubusercontent\\.com/|cdn\\.jsdelivr\\.net/gh/|data\\.jsdelivr\\.com/v1/packages/gh/)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE = Pattern.compile("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$");
    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9._-]+$");
    /** github.com 上跟在 owner/repo 之后、表示"后面是分支名"的路径段。 */
    private static final Set<String> BRANCH_MARKERS = Set.of("tree", "blob", "commit", "branch");
    /** 视为"具体文件"的后缀——用户贴的是文件链接时改为扫它的父目录。 */
    private static final List<String> FILE_EXT = List.of(
            ".md", ".txt", ".json", ".yaml", ".yml", ".py", ".sh", ".js", ".ts", ".toml");

    public String fullName() {
        return owner + "/" + repo;
    }

    /** 展示用来源串。 */
    public String describe() {
        String b = branch == null || branch.isBlank() ? "默认分支" : branch;
        String s = subPath == null || subPath.isBlank() ? "" : (" · " + subPath);
        return fullName() + " @ " + b + s;
    }

    /**
     * 扫描根目录：用户贴的是某个文件的链接（如 {@code .../blob/main/skills/docx/SKILL.md}）时，
     * 取其**父目录**作为扫描起点——用户想看的是那个技能，不是一个文件。
     */
    public String scanRoot() {
        if (subPath == null || subPath.isBlank()) {
            return "";
        }
        String lower = subPath.toLowerCase(Locale.ROOT);
        boolean looksLikeFile = FILE_EXT.stream().anyMatch(lower::endsWith);
        if (!looksLikeFile) {
            return subPath;
        }
        int slash = subPath.lastIndexOf('/');
        return slash < 0 ? "" : subPath.substring(0, slash);
    }

    public static GitRepoRef parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.badRequest("仓库地址不能为空");
        }
        String s = raw.trim().replaceAll("[\\r\\n\\t\\u3000]", "");
        // 全角字符（用户从聊天工具里复制时常见）
        s = s.replace("：", ":").replace("／", "/").replace("？", "?");

        // 1) 剥掉加速前缀/ext 包装，只保留从真实仓库地址开始的部分
        Matcher m = REPO_START.matcher(s);
        if (m.find()) {
            s = "https://" + s.substring(m.start());
        } else if (s.startsWith("git@")) {
            s = "https://" + s.substring(4).replaceFirst(":", "/");
        } else if (!s.contains("://") && !BARE.matcher(s).matches()) {
            s = "https://" + s;
        }

        // 2) 去掉 query 与 fragment
        int cut = s.length();
        int q = s.indexOf('?');
        if (q >= 0 && q < cut) {
            cut = q;
        }
        int h = s.indexOf('#');
        if (h >= 0 && h < cut) {
            cut = h;
        }
        if (cut < s.length()) {
            s = s.substring(0, cut);
        }

        // 3) 拆 host 与 path
        String host = "";
        String path;
        if (s.contains("://")) {
            try {
                URI u = URI.create(s);
                host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                path = u.getPath() == null ? "" : u.getPath();
            } catch (IllegalArgumentException e) {
                throw BizException.badRequest("无法识别的仓库地址：" + raw);
            }
        } else {
            path = s;
        }

        // jsDelivr 的固定前缀要去掉，否则 owner 会被解析成 "v1"
        path = path.replaceFirst("(?i)^/v1/packages/gh/", "/").replaceFirst("(?i)^/gh/", "/");
        path = path.replaceAll("^/+", "").replaceAll("/+$", "");
        if (path.isBlank()) {
            throw BizException.badRequest("地址里没解析出 owner/repo：" + raw);
        }

        // GitHub Pages 站点（<owner>.github.io/<repo>/...）：owner 在主机名上、repo 在首段路径上。
        // 皮肤市场常以静态站点形式发布（如 https://kingofsoysauce.github.io/dsh-skin-market/），
        // 而真正的数据在对应仓库里，所以要能把站点地址还原回 owner/repo。
        if (host.endsWith(".github.io")) {
            String owner = host.substring(0, host.length() - ".github.io".length());
            String[] segs = path.split("/");
            if (segs.length == 0 || segs[0].isBlank()) {
                throw BizException.badRequest("GitHub Pages 地址里没解析出仓库名：" + raw);
            }
            String repo = validate(stripGit(segs[0]), "repo", raw);
            String sub = segs.length > 1
                    ? String.join("/", Arrays.asList(segs).subList(1, segs.length))
                    : "";
            return new GitRepoRef(validate(owner, "owner", raw), repo, "", sub);
        }

        String[] seg = path.split("/");
        if (seg.length < 2) {
            throw BizException.badRequest("地址里没解析出 owner/repo：" + raw);
        }
        String owner = validate(seg[0], "owner", raw);
        String repo = stripGit(seg[1]);
        String branch = "";
        // jsDelivr 形式：repo@branch
        int at = repo.indexOf('@');
        if (at > 0) {
            branch = repo.substring(at + 1);
            repo = repo.substring(0, at);
        }
        repo = validate(repo, "repo", raw);

        boolean jsdelivrStyle = host.contains("jsdelivr");
        boolean rawStyle = host.contains("raw.githubusercontent") || jsdelivrStyle;
        String sub = "";
        if (seg.length > 2) {
            int i = 2;
            if (rawStyle) {
                // raw / jsDelivr：/owner/repo/<branch>/<path>（jsDelivr 已用 @ 给出分支则不消费）
                if (branch.isBlank()) {
                    branch = seg[i];
                    i++;
                }
            } else if (BRANCH_MARKERS.contains(seg[i].toLowerCase(Locale.ROOT))) {
                i++;
                if (i < seg.length && branch.isBlank()) {
                    branch = seg[i];
                    i++;
                }
            } else if (branch.isBlank()) {
                branch = seg[i];
                i++;
            }
            if (i < seg.length) {
                sub = String.join("/", Arrays.asList(seg).subList(i, seg.length));
            }
        }
        return new GitRepoRef(owner, repo, branch, sub);
    }

    private static String validate(String v, String field, String raw) {
        if (v == null || !SEGMENT.matcher(v).matches()) {
            throw BizException.badRequest("非法的 " + field + "（" + v + "），请检查地址：" + raw);
        }
        return v;
    }

    private static String stripGit(String repo) {
        return repo.endsWith(".git") ? repo.substring(0, repo.length() - 4) : repo;
    }
}
