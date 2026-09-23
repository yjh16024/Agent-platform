package com.agentplatform.core.tool.approval;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.tool.fs.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 改前快照与回滚：让"智能体改文件"从不可逆变成可撤销。
 *
 * <h3>为什么以「审批」为单位</h3>
 * 用户的心智是"撤销刚才那次操作"，而不是"撤销某个文件的某一次改动"。
 * 一次批准可能只改一个文件、也可能新建一个文件 —— 单文件 {@code .bak} 表达不了
 * "这次操作整体是什么"，也无法表达"这个文件本来是**不存在**的，回滚应该是删掉它"。
 * 所以快照目录按 {@code {snapshotRoot}/{approvalId}/} 组织，目录内镜像相对路径，
 * 另有一个 {@link #MANIFEST} 文件记下每个文件在改动前是"已存在"还是"新建"。
 *
 * <h3>几个刻意的决定</h3>
 * <ul>
 *   <li><b>快照存文件系统而不是数据库</b>：源码文件动辄几十 KB，
 *       存库会把审批表撑成一张大表，而它的职责是"记录决策"而不是"存字节"；</li>
 *   <li><b>快照目录默认在 {@code ./data/snapshots}，不在工作区内</b>：
 *       否则 {@code fs_glob}/{@code fs_grep} 会把自己的备份也当成项目文件扫出来；
 *       本类启动时会检测这种配置并明确告警（而非静默跑出奇怪结果）；</li>
 *   <li><b>回滚只做"恢复"，不做"反向执行"</b>：不需要重新调工具，
 *       直接把文件按快照还原。这样即使在审批之后又手动改过文件，
 *       回滚的结果也是确定的（回到快照那一刻）；</li>
 *   <li><b>回滚有幂等保护</b>：已回滚的记录不允许再次回滚 ——
 *       否则"恢复到某次快照"会被误解成"再执行一次当时的操作"。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotService {

    private final WorkspaceService workspace;

    /** 快照根目录（**默认在工作区之外**，见类注释）。 */
    @Value("${agent-platform.agent.workspace.snapshot-dir:./data/snapshots}")
    private String snapshotRootConfig;

    /** 快照保留天数（超期由 {@link #purgeExpired} 清理）。 */
    @Value("${agent-platform.agent.workspace.snapshot-keep-days:7}")
    private int snapshotKeepDays;

    /** 清单文件名：每行 `相对路径<TAB>existed|created`。 */
    static final String MANIFEST = ".ap-manifest";

    private volatile Path snapshotRoot;

    public Path snapshotRoot() {
        Path r = snapshotRoot;
        if (r != null) {
            return r;
        }
        synchronized (this) {
            if (snapshotRoot == null) {
                Path p = Path.of(snapshotRootConfig == null || snapshotRootConfig.isBlank()
                        ? "./data/snapshots" : snapshotRootConfig).toAbsolutePath().normalize();
                try {
                    Files.createDirectories(p);
                } catch (IOException e) {
                    log.warn("[snapshot] 快照目录创建失败（{}）：{}", p, e.getMessage());
                }
                // 快照目录落在工作区里会导致 fs_grep/fs_glob 扫到自己的备份。
                // 不阻断（用户可能有别的安排），但必须让人看见这条警告。
                try {
                    Path wsRoot = workspace.root();
                    if (p.startsWith(wsRoot)) {
                        log.warn("[snapshot] 快照目录 {} 位于工作区 {} 之内 —— "
                                        + "fs_glob / fs_grep 会把备份当成项目文件扫出来。"
                                        + "建议把 agent-platform.agent.workspace.snapshot-dir 移到工作区之外。",
                                p, wsRoot);
                    }
                } catch (Exception ignored) {
                    // 工作区不可用不影响快照功能本身
                }
                snapshotRoot = p;
                log.info("[snapshot] 快照根目录：{}", p);
            }
            return snapshotRoot;
        }
    }

    // ------------------------------------------------------------------ 捕获

    /**
     * 在执行写操作**之前**捕获快照。
     *
     * @param approvalId 审批 ID（作为快照目录名）
     * @param relativePaths 本次操作会影响的文件（相对工作区根）
     * @return 快照目录；若没有任何需要备份的文件则返回 null（如目标文件本来就存在以外的异常情况）
     */
    public Path capture(String approvalId, List<String> relativePaths) {
        if (approvalId == null || approvalId.isBlank() || relativePaths == null || relativePaths.isEmpty()) {
            return null;
        }
        Path dir = snapshotRoot().resolve(approvalId);
        List<String> manifest = new ArrayList<>();
        int copied = 0;
        try {
            Files.createDirectories(dir);
            for (String rel : relativePaths) {
                if (rel == null || rel.isBlank()) {
                    continue;
                }
                Path target;
                try {
                    // mustExist=false：新建文件的场景下目标尚不存在，这不是错误
                    target = workspace.resolve(rel, false);
                } catch (Exception e) {
                    // 路径非法就不备份它（执行阶段同样会被拒），但要记下来
                    log.debug("[snapshot] 跳过无法解析的路径 {}：{}", rel, e.getMessage());
                    continue;
                }
                String normalized = workspace.relative(target);
                if (Files.isRegularFile(target)) {
                    Path dest = dir.resolve(normalized);
                    Path parent = dest.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(target, dest, StandardCopyOption.REPLACE_EXISTING);
                    manifest.add(normalized + "\texisted");
                    copied++;
                } else {
                    // 改动前不存在 ⇒ 回滚动作是"删掉它"，不需要字节内容
                    manifest.add(normalized + "\tcreated");
                }
            }
            Files.write(dir.resolve(MANIFEST), manifest, StandardCharsets.UTF_8);
            log.info("[snapshot] 已捕获 {}（备份 {} 个文件，清单 {} 项）", approvalId, copied, manifest.size());
            return dir;
        } catch (Exception e) {
            // 快照失败要不要阻止执行？—— 要。没有快照的写操作是不可撤销的，
            // 而"可撤销"正是用户批准它的前提。
            throw BizException.internal("捕获改前快照失败，已阻止本次写入：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 回滚

    /**
     * 按快照回滚一次审批的全部改动。
     *
     * @return 回滚的文件数（含被删除的新建文件）
     */
    public int rollback(String approvalId) {
        if (approvalId == null || approvalId.isBlank()) {
            throw BizException.badRequest("缺少审批 ID");
        }
        Path dir = snapshotRoot().resolve(approvalId);
        if (!Files.isDirectory(dir)) {
            throw BizException.badRequest("该操作没有可用的快照（可能未产生快照，或快照已被清理）");
        }
        Path manifestPath = dir.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestPath)) {
            throw BizException.badRequest("快照清单缺失，无法安全回滚：" + approvalId);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw BizException.internal("读取快照清单失败：" + e.getMessage(), e);
        }

        int restored = 0;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length < 2) {
                continue;
            }
            String rel = parts[0];
            String kind = parts[1];
            try {
                // 回滚同样要走工作区校验：快照清单是磁盘上的文件，
                // 万一被改动过，不能让回滚变成"往任意路径写"的通道。
                Path target = workspace.resolve(rel, false);
                Path backup = dir.resolve(rel);
                if ("created".equals(kind)) {
                    // 改动前不存在 ⇒ 删掉它才是回滚
                    if (Files.isRegularFile(target)) {
                        Files.delete(target);
                        restored++;
                    }
                } else {
                    if (!Files.isRegularFile(backup)) {
                        log.warn("[snapshot] 快照内容缺失，跳过 {}（{}）", rel, approvalId);
                        continue;
                    }
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                    restored++;
                }
            } catch (Exception e) {
                // 单个文件回滚失败不中断其余：用户更需要"能救回来多少是多少"
                log.warn("[snapshot] 回滚 {} 失败：{}", rel, e.getMessage());
            }
        }
        log.info("[snapshot] 回滚 {} 完成：恢复 {} 个文件", approvalId, restored);
        return restored;
    }

    // ------------------------------------------------------------------ 清理

    /**
     * 删除超过保留期的快照目录，返回清理的目录数。
     *
     * <p>快照是**临时的撤销凭据**，不是审计资料（审计在 {@code sys_audit_log} 与
     * 审批记录里）。留太久只会白占磁盘，而且陈旧快照给不了用户任何安全感 ——
     * 没人会去回滚三天前的一次改动。</p>
     */
    public int purgeExpired() {
        Path rootDir = snapshotRoot();
        if (!Files.isDirectory(rootDir)) {
            return 0;
        }
        int days = snapshotKeepDays <= 0 ? 7 : snapshotKeepDays;
        long cutoff = System.currentTimeMillis() - days * 24L * 3600L * 1000L;
        int removed = 0;
        try (Stream<Path> children = Files.list(rootDir)) {
            for (Path child : children.toList()) {
                try {
                    if (Files.isDirectory(child)
                            && Files.getLastModifiedTime(child).toMillis() < cutoff) {
                        deleteRecursively(child);
                        removed++;
                    }
                } catch (Exception e) {
                    log.debug("[snapshot] 清理 {} 失败：{}", child, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[snapshot] 清理快照失败：{}", e.getMessage());
        }
        if (removed > 0) {
            log.info("[snapshot] 已清理 {} 个超过 {} 天的快照目录", removed, days);
        }
        return removed;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力而为
                }
            });
        }
    }

    public int snapshotKeepDays() {
        return snapshotKeepDays <= 0 ? 7 : snapshotKeepDays;
    }
}
