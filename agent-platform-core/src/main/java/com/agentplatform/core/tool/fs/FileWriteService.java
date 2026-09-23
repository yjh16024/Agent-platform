package com.agentplatform.core.tool.fs;

import com.agentplatform.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 真正执行文件写入的服务。
 *
 * <h3>为什么与"写工具"分成两层</h3>
 * 因为审批的存在：{@code fs_write_file} / {@code fs_edit_file} 这两个**工具本身永远不写文件**，
 * 它们只做参数校验并提交审批申请。真正落笔的是本类，由 {@code ApprovalService} 在用户批准后调用。
 *
 * <p>这样分层的好处是工具侧不需要知道"我有没有被批准"—— 不需要往 {@code ToolContext} 里塞令牌、
 * 也不需要一套"已批准则放行"的分支判断。**权限判断与执行动作彻底分离**，
 * 少了一个能在两处不一致的状态。</p>
 *
 * <h3>工程细节（每条都对应一类真实的坑）</h3>
 * <ul>
 *   <li><b>原子写</b>：先写同目录下的临时文件，再 {@code move} 覆盖。
 *       直接往目标文件写，一旦进程中断就留下半个文件（源码半截 = 项目编不过）；</li>
 *   <li><b>保留换行风格</b>：Windows 上源码常是 CRLF，而模型输出的文本几乎总是 LF。
 *       若不做归一，一次编辑就会把整个文件的行尾改掉 —— diff 里全是噪声，
 *       git blame 也会被冲掉。这里按"先原样匹配、失败再按文件风格匹配"的顺序处理；</li>
 *   <li><b>编辑用精确串替换而非行号</b>：行号会因上游任何一次改动而失效，
 *       而"这段唯一文本"的语义稳定得多。业界（Aider/Cursor/Claude Code）都是这个流派；</li>
 *   <li><b>匹配失败要给出可用的反馈</b>：只说"没找到"模型只能瞎猜；
 *       返回"当前相关片段"它就能立刻修正 —— 这是编辑工具能不能收敛的关键。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileWriteService {

    private final WorkspaceService workspace;

    /** 单文件写入上限（防止一次覆盖出个巨型文件）。 */
    @Value("${agent-platform.agent.workspace.max-write-bytes:1048576}")
    private int maxWriteBytes;

    /** 改前备份目录后缀（第 3 期会升级为完整的快照回滚，这里先做单文件备份）。 */
    private static final String BACKUP_SUFFIX = ".ap-bak";

    // ------------------------------------------------------------------ 新增 / 覆盖

    /**
     * 写入文件（新建或整体覆盖）。
     *
     * @param content 完整内容
     * @param createOnly 仅在文件不存在时写入（防止误覆盖已有文件）
     */
    public WriteOutcome writeFile(String relativePath, String content, boolean createOnly) {
        if (content == null) {
            throw BizException.badRequest("content 不能为空");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > maxWriteBytes) {
            throw BizException.badRequest("内容过大（超过 " + maxWriteBytes + " 字节）");
        }
        // mustExist=false：允许新建；但父目录必须已存在且在工作区内
        Path target = workspace.resolve(relativePath, false);
        boolean exists = Files.exists(target);
        if (createOnly && exists) {
            throw BizException.badRequest("文件已存在，createOnly=true 时不覆盖：" + relativePath);
        }
        if (exists && !Files.isRegularFile(target)) {
            throw BizException.badRequest("目标不是普通文件：" + relativePath);
        }
        if (exists) {
            backup(target);
        }
        // 内容统一以 LF 落盘后由调用方决定是否转换；这里保留传入内容原样（模型给什么写什么）
        atomicWrite(target, content);
        return new WriteOutcome(workspace.relative(target), exists, content.length());
    }

    // ------------------------------------------------------------------ 精确串替换

    /**
     * 精确串替换（编辑文件的主力）。
     *
     * @param oldString  要替换的原文本，**必须在文件中唯一出现**（除非 replaceAll=true）
     * @param newString  新文本
     * @param replaceAll 是否替换全部出现
     */
    public EditOutcome editFile(String relativePath, String oldString, String newString, boolean replaceAll) {
        if (oldString == null || oldString.isEmpty()) {
            throw BizException.badRequest("oldString 不能为空（要新增内容请用 fs_write_file）");
        }
        if (newString == null) {
            throw BizException.badRequest("newString 不能为空（要删除内容请传空字符串的情况下请明确说明）");
        }
        if (oldString.equals(newString)) {
            throw BizException.badRequest("oldString 与 newString 相同，无需编辑");
        }
        Path target = workspace.resolve(relativePath, true);
        if (!Files.isRegularFile(target)) {
            throw BizException.badRequest("目标不是普通文件：" + relativePath);
        }
        if (!workspace.readableText(target)) {
            throw BizException.badRequest("目标不是可读的文本文件：" + relativePath);
        }

        String original;
        try {
            original = Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw BizException.internal("读取文件失败：" + e.getMessage(), e);
        }

        // 行尾归一：先按传入原样匹配；失败则以文件自身的换行风格再试一次。
        // 这样 LF 文本改 CRLF 文件也能命中，而不必让模型去猜行尾。
        String needle = oldString;
        String haystack = original;
        boolean crlf = original.contains("\r\n");
        int occurrences = countOccurrences(haystack, needle);
        if (occurrences == 0 && crlf && !needle.contains("\r\n")) {
            needle = oldString.replace("\n", "\r\n");
            occurrences = countOccurrences(haystack, needle);
        }
        if (occurrences == 0 && !crlf && needle.contains("\r\n")) {
            // 反向：模型给的是 CRLF，文件是 LF
            needle = oldString.replace("\r\n", "\n");
            occurrences = countOccurrences(haystack, needle);
        }

        if (occurrences == 0) {
            throw BizException.badRequest("在 " + relativePath + " 中未找到要替换的内容。"
                    + "请先用 fs_read_file 读取当前内容确认（文件可能已变化）。\n"
                    + "文件当前内容片段：\n" + head(original, 40));
        }
        if (occurrences > 1 && !replaceAll) {
            throw BizException.badRequest("要替换的内容在 " + relativePath + " 中出现了 " + occurrences
                    + " 次，无法确定改哪一处。请提供更长的上下文使其唯一，或显式设置 replaceAll=true。");
        }

        // 替换时把 newString 也归一到文件的换行风格，避免混入异种换行
        String replacement = newString;
        if (crlf && !replacement.contains("\r\n")) {
            replacement = replacement.replace("\n", "\r\n");
        } else if (!crlf && replacement.contains("\r\n")) {
            replacement = replacement.replace("\r\n", "\n");
        }

        String updated = replaceAll
                ? haystack.replace(needle, replacement)
                : replaceFirst(haystack, needle, replacement);

        backup(target);
        atomicWrite(target, updated);
        return new EditOutcome(workspace.relative(target), occurrences, replaceAll,
                countLines(original), countLines(updated));
    }

    // ------------------------------------------------------------------ 内部

    /** 改前备份（保留最近一次）。第 3 期会用完整快照机制替代。 */
    private void backup(Path target) {
        try {
            Path bak = target.resolveSibling(target.getFileName() + BACKUP_SUFFIX);
            Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // 备份失败不该阻止主流程（主流程本身已有原子写保证不会出现半个文件）
            log.debug("[fs] 备份失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 原子写：同目录临时文件 + move 覆盖。
     *
     * <p>临时文件必须与目标**同目录**（同一文件系统），否则 move 会退化成"复制+删除"，
     * 就不是原子的了。Windows 上目标被占用时 {@code ATOMIC_MOVE} 可能失败，
     * 所以捕获后降级为普通 REPLACE_EXISTING。</p>
     */
    private void atomicWrite(Path target, String content) {
        Path dir = target.getParent();
        try {
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Path tmp = Files.createTempFile(dir, ".ap-edit-", ".tmp");
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    // 某些文件系统/占用场景不支持原子移动，退化为普通覆盖
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw BizException.internal("写入失败：" + e.getMessage(), e);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String replaceFirst(String haystack, String needle, String replacement) {
        int idx = haystack.indexOf(needle);
        return idx < 0 ? haystack
                : haystack.substring(0, idx) + replacement + haystack.substring(idx + needle.length());
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** 取前 N 行（匹配失败时给模型看当前内容，便于它修正 oldString）。 */
    private static String head(String text, int maxLines) {
        List<String> lines = text.lines().limit(maxLines).toList();
        String body = String.join("\n", lines);
        long total = text.lines().count();
        return total > maxLines ? body + "\n…（共 " + total + " 行）" : body;
    }

    public int maxWriteBytes() {
        return maxWriteBytes;
    }

    /** 写入结果。 */
    public record WriteOutcome(String path, boolean overwritten, int chars) {
    }

    /** 编辑结果。 */
    public record EditOutcome(String path, int replaced, boolean replaceAll,
                              int linesBefore, int linesAfter) {
    }
}
