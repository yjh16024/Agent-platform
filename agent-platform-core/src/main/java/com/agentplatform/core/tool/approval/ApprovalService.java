package com.agentplatform.core.tool.approval;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.tool.fs.FileWriteService;
import com.agentplatform.model.entity.ToolApproval;
import com.agentplatform.model.enums.ToolApprovalStatus;
import com.agentplatform.model.repository.ToolApprovalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具审批服务：有副作用的工具调用必须先申请、经人放行才执行。
 *
 * <h3>为什么工具自己"不执行"（这是整套设计的关键）</h3>
 * 采用**申请-批准两步**：写工具被调用时**只提交一条申请**并立即返回"等待批准"，
 * 绝不落笔。用户在前端点批准后，由本服务**按申请里存的参数快照**去执行。
 *
 * <p>这样做的两个好处：</p>
 * <ol>
 *   <li><b>工具侧不需要知道批准状态</b> —— 不必往 {@code ToolContext} 里塞令牌、
 *       也不必写"已批准则放行"的分支。权限判断与执行动作彻底分离，
 *       不存在"工具以为批了、审批表以为没批"这种两处不一致；</li>
 *   <li><b>没有 TOCTOU 窗口</b> —— 执行的是**提交时存下的参数**，
 *       而不是让模型再调一次（第二次它完全可能换个参数，
 *       那就成了"用户批的是改 A、实际改的是 B"）。</li>
 * </ol>
 *
 * <h3>批准人限制：只能批自己提交的</h3>
 * 刻意做得很严（fail-closed）：多一个人能批，就多一条"帮别人放行危险操作"的路径。
 * 多用户协作场景若确实需要管理员代批，应当在此处显式加一条角色判断，
 * 而不是默认放开 —— 默认放开意味着任何登录用户都能批准任何人的文件删除。
 * 仓储方法签名已强制带 {@code tenantId + userId}，忘传就查不到。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ToolApprovalRepository repository;
    private final FileWriteService fileWriteService;
    private final SnapshotService snapshotService;
    private final ApprovalWakeChannel wakeChannel;

    /** 用 Jackson 3 的 mapper 序列化参数快照（项目统一用 tools.jackson）。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 待审批的保留期（天）：超过就自动作废，防止陈年申请被误批准。 */
    @Value("${agent-platform.agent.approval.keep-days:7}")
    private int keepDays;

    /**
     * 阻塞等待用户确认的时长（秒）。超时后降级为"两步式"（工具返回"已提交待审批"）。
     *
     * <p>默认 5 分钟：够用户看清提示并做决定，又不至于把对话线程挂太久。
     * 桌面版单机场景下这条链路完全在本地，5 分钟不会带来任何资源压力
     * （等待发生在虚拟线程上，几乎不占内存）。</p>
     */
    @Value("${agent-platform.agent.approval.blocking-timeout-seconds:300}")
    private int blockingTimeoutSeconds;

    /**
     * 等待中的申请：{@code approvalId → 等待者}。
     *
     * <h3>为什么用内存 Map 而不是"轮询数据库"</h3>
     * 阻塞方与唤醒方在**同一个进程**里（工具调用线程 vs HTTP 线程），用 {@link CompletableFuture}
     * 唤醒是零延迟的；轮询数据库则要选一个间隔（太短浪费、太长有延迟），
     * 而且"决定已做出但轮询还没到"会让用户觉得点了没反应。
     *
     * <p><b>多实例怎么办</b>：这张表是进程内的，负载均衡可能把"批准"请求分到另一个实例。
     * 所以批准/拒绝后除了唤醒本地等待者，还会经 {@link ApprovalWakeChannel} 广播一条消息，
     * 各实例收到后检查自己的等待表 —— 有就唤醒。没有 Redis 时通道退化为空操作
     * （那种部署必然是单实例，不存在这个问题）。</p>
     */
    private final Map<String, CompletableFuture<ToolApproval>> waiters = new ConcurrentHashMap<>();

    /**
     * 注册跨实例唤醒回调。
     *
     * <p>用 {@code @PostConstruct} 而不是构造注入回调：回调要调 {@link #wake}，
     * 若走构造器就形成 {@code ApprovalService → Channel → ApprovalService} 的循环依赖。</p>
     */
    @jakarta.annotation.PostConstruct
    void registerWakeHandler() {
        wakeChannel.onWake(approvalId -> {
            try {
                // 广播只带 approvalId（它全局唯一）；这里按它取最新记录再唤醒本地等待者。
                // 取不到说明本实例上没有这条申请的等待者 —— 正常情况，不做任何事。
                repository.findByApprovalId(approvalId).ifPresent(row -> wake(approvalId, row));
            } catch (Exception e) {
                log.warn("[approval] 处理唤醒广播失败（{}）：{}", approvalId, e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ 提交申请

    /**
     * 提交一条审批申请，返回业务 ID。
     *
     * <p>由写工具在 {@code execute()} 里调用。**不抛异常给模型看的能力由调用方负责** ——
     * 本方法失败（如落库异常）会正常抛出，因为它属于"申请根本没建起来"，
     * 此时返回"已提交"是错的（用户永远看不到这条申请）。</p>
     */
    @Transactional
    public String submit(String tenantId, String agentId, String sessionId, String runId,
                         String userId, String toolName, JsonNode args, String summary) {
        String argsJson;
        try {
            argsJson = args == null ? "{}" : objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            throw BizException.internal("参数序列化失败：" + e.getMessage(), e);
        }
        ToolApproval row = ToolApproval.builder()
                .approvalId(IdGenerator.generate("appr"))
                .tenantId(tenantId == null || tenantId.isBlank() ? "default" : tenantId)
                .agentId(agentId)
                .sessionId(sessionId)
                .runId(runId)
                .userId(userId)
                .toolName(toolName)
                .toolArgs(argsJson)
                .summary(summary == null ? null : (summary.length() > 500 ? summary.substring(0, 500) : summary))
                .status(ToolApprovalStatus.pending)
                .build();
        repository.save(row);
        log.info("[approval] 提交待审批 {} tool={} agent={}", row.getApprovalId(), toolName, agentId);
        return row.getApprovalId();
    }

    /**
     * 阻塞等待用户对某条申请做出决定（"就地确认"模式的核心）。
     *
     * <p>由写工具在 {@code submit()} 之后调用，返回后它就能拿到**真实结果**
     * 交给模型 —— 模型侧完全无感，不需要"下一轮重试"、也不依赖它转述提示。</p>
     *
     * <p><b>为什么必须在注册等待器之后再复查一次状态</b>：用户完全可能在
     * "提交申请"与"注册等待器"之间这一瞬间点了批准（本地单机场景下这个窗口虽然极短，
     * 但并非不可能）。若只查一次，那次批准就会落到空处 —— 工具一直等到超时，
     * 而用户明明看到自己点了成功。所以顺序是：**先查 → 注册 → 再查**，
     * 第二次查到已决定就直接完成 future。</p>
     *
     * @return 决定结果；超时/中断时 {@code decided=false}，调用方据此降级为两步式
     */
    public WaitOutcome awaitDecision(String approvalId, String tenantId) {
        if (approvalId == null || approvalId.isBlank()) {
            return WaitOutcome.timeout();
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;

        // ① 先查（覆盖"提交后立刻就批"的常见情形，避免白等）
        ToolApproval early = repository.findByApprovalIdAndTenantId(approvalId, tenant).orElse(null);
        if (early != null && early.getStatus() != ToolApprovalStatus.pending) {
            return WaitOutcome.of(early);
        }

        // ② 注册等待器
        CompletableFuture<ToolApproval> future = new CompletableFuture<>();
        waiters.put(approvalId, future);
        try {
            // ③ 注册后再查一次，堵住"①与②之间被批准"的窗口
            ToolApproval again = repository.findByApprovalIdAndTenantId(approvalId, tenant).orElse(null);
            if (again != null && again.getStatus() != ToolApprovalStatus.pending) {
                return WaitOutcome.of(again);
            }
            int timeout = blockingTimeoutSeconds <= 0 ? 300 : blockingTimeoutSeconds;
            ToolApproval decided = future.get(timeout, TimeUnit.SECONDS);
            return WaitOutcome.of(decided);
        } catch (TimeoutException e) {
            log.info("[approval] 等待用户确认超时（{}s），降级为待审批 {}",
                    blockingTimeoutSeconds, approvalId);
            return WaitOutcome.timeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return WaitOutcome.timeout();
        } catch (Exception e) {
            log.warn("[approval] 等待确认异常（按超时处理）{}：{}", approvalId, e.getMessage());
            return WaitOutcome.timeout();
        } finally {
            waiters.remove(approvalId);
        }
    }

    /** 唤醒等待中的工具调用（批准/拒绝后调用；没有等待者时是空操作）。 */
    private void wake(String approvalId, ToolApproval row) {
        CompletableFuture<ToolApproval> future = waiters.get(approvalId);
        if (future != null) {
            future.complete(row);
            log.debug("[approval] 已唤醒等待中的调用 {}", approvalId);
        }
    }

    /** 等待结果：{@code decided=false} 表示超时/未决，调用方应降级为两步式。 */
    public record WaitOutcome(boolean decided, ToolApprovalStatus status,
                              String result, String errorMsg) {

        static WaitOutcome of(ToolApproval row) {
            return new WaitOutcome(true, row.getStatus(), row.getResult(), row.getErrorMsg());
        }

        static WaitOutcome timeout() {
            return new WaitOutcome(false, null, null, null);
        }

        public boolean approved() {
            return decided && status == ToolApprovalStatus.approved;
        }
    }

    // ------------------------------------------------------------------ 查询

    @Transactional(readOnly = true)
    public Page<ToolApproval> list(String tenantId, String status, Pageable pageable) {
        if (status == null || status.isBlank()) {
            return repository.findByTenantId(tenantId, pageable);
        }
        ToolApprovalStatus st;
        try {
            st = ToolApprovalStatus.valueOf(status.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("非法的状态：" + status
                    + "（可用：pending / approved / rejected / expired）");
        }
        return repository.findByTenantIdAndStatus(tenantId, st, pageable);
    }

    /** 待审批条数（前端角标）。 */
    @Transactional(readOnly = true)
    public long pendingCount(String tenantId) {
        return repository.countByTenantIdAndStatus(tenantId, ToolApprovalStatus.pending);
    }

    @Transactional(readOnly = true)
    public long pendingCountOfSession(String tenantId, String sessionId) {
        return repository.countByTenantIdAndSessionIdAndStatus(tenantId, sessionId, ToolApprovalStatus.pending);
    }

    // ------------------------------------------------------------------ 决定

    /**
     * 批准并**立即执行**。
     *
     * <p>执行失败不会把申请退回 pending：一次批准对应一次尝试，失败原因写进 {@code errorMsg}
     * 供用户与模型看到。退回 pending 会让"反复点批准反复失败"变成可能，
     * 而用户其实需要看到的是失败原因，而不是一个可以无限重试的按钮。</p>
     */
    @Transactional
    public ToolApproval approve(String tenantId, String userId, String approvalId) {
        ToolApproval row = requirePending(tenantId, userId, approvalId);
        row.setStatus(ToolApprovalStatus.approved);
        row.setDecidedBy(userId);
        row.setDecidedAt(LocalDateTime.now());
        try {
            // ① 改前快照：**必须在执行之前**，且失败就终止 ——
            //    没有快照的写操作是不可撤销的，而"可撤销"正是用户批准它的前提。
            Path snap = snapshotService.capture(approvalId, affectedPaths(row));
            if (snap != null) {
                row.setSnapshotDir(snap.toString());
            }
            // ② 执行
            String result = execute(row);
            row.setResult(result == null ? "(执行成功，无输出)"
                    : (result.length() > 2000 ? result.substring(0, 2000) + "…" : result));
            row.setErrorMsg(null);
            log.info("[approval] 已批准并执行 {} tool={}", approvalId, row.getToolName());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            row.setErrorMsg(msg.length() > 1000 ? msg.substring(0, 1000) : msg);
            log.warn("[approval] 已批准但执行失败 {} tool={}：{}", approvalId, row.getToolName(), msg);
        }
        ToolApproval saved = repository.save(row);
        // 唤醒"就地确认"模式下等待中的工具调用（没有等待者时是空操作）。
        // 必须在 save 之后：等待方拿到的是含执行结果的完整记录。
        wake(approvalId, saved);
        // 再多一步广播：批准请求可能被负载均衡分到了**另一个实例**，
        // 那边没有等待者，真正在等的本实例需要被告知。单实例时这是空操作。
        // ⚠️ 只在这里（以及 reject）广播 —— wake() 内部**不**广播，否则会形成回环
        //（A 唤醒后广播 → B 收到再唤醒再广播 → …）。
        wakeChannel.publish(approvalId);
        return saved;
    }

    /**
     * 回滚一次已批准的操作，把文件恢复到快照那一刻。
     *
     * <p><b>幂等保护</b>：已有 {@code rolledBackAt} 的记录不允许再次回滚 ——
     * 否则"恢复到某次快照"会被误解成"再执行一次当时的操作"。
     * 同一限制也保证"改 A → 回滚 → 改 B → 回滚"这种链路不会互相踩。</p>
     */
    @Transactional
    public ToolApproval rollback(String tenantId, String userId, String approvalId) {
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        ToolApproval row = repository.findByApprovalIdAndTenantId(approvalId, tenant)
                .orElseThrow(() -> BizException.notFound("审批记录", approvalId));
        if (row.getUserId() != null && userId != null && !row.getUserId().equals(userId)) {
            throw BizException.forbidden("只能回滚自己提交的操作");
        }
        if (row.getStatus() != ToolApprovalStatus.approved) {
            throw BizException.badRequest("只有「已批准」的操作才可能回滚（当前状态："
                    + row.getStatus().label() + "）");
        }
        if (row.getRolledBackAt() != null) {
            throw BizException.conflict("该操作已于 " + row.getRolledBackAt() + " 回滚过，不能重复回滚");
        }
        if (row.getErrorMsg() != null && !row.getErrorMsg().isBlank()) {
            // 执行本来就没成功（如路径越界），改动根本没落盘，无需回滚
            throw BizException.badRequest("该操作当时执行失败了，没有产生改动，无需回滚");
        }

        int restored = snapshotService.rollback(approvalId);
        row.setRolledBackAt(LocalDateTime.now());
        row.setResult((row.getResult() == null ? "" : row.getResult() + "\n")
                + "⟲ 已回滚：恢复了 " + restored + " 个文件（" + LocalDateTime.now() + "）");
        log.info("[approval] 已回滚 {}：恢复 {} 个文件", approvalId, restored);
        return repository.save(row);
    }

    /**
     * 从申请参数里解析出本次操作会影响的文件。
     *
     * <p>当前两个写工具都只动一个文件（{@code path}），所以这里返回单元素列表；
     * 将来出现"一次改多个文件"的工具时，需要在这里扩展（并保持顺序稳定）。</p>
     */
    private List<String> affectedPaths(ToolApproval row) {
        try {
            JsonNode args = row.getToolArgs() == null || row.getToolArgs().isBlank()
                    ? null : objectMapper.readTree(row.getToolArgs());
            String path = args == null ? "" : args.path("path").asText("");
            return path.isBlank() ? List.of() : List.of(path);
        } catch (Exception e) {
            log.debug("[approval] 解析参数失败，按无文件处理：{}", e.getMessage());
            return List.of();
        }
    }

    /** 拒绝（不执行）。 */
    @Transactional
    public ToolApproval reject(String tenantId, String userId, String approvalId, String reason) {
        ToolApproval row = requirePending(tenantId, userId, approvalId);
        row.setStatus(ToolApprovalStatus.rejected);
        row.setDecidedBy(userId);
        row.setDecidedAt(LocalDateTime.now());
        if (reason != null && !reason.isBlank()) {
            row.setErrorMsg(reason.length() > 1000 ? reason.substring(0, 1000) : reason);
        }
        ToolApproval saved = repository.save(row);
        // 拒绝同样要唤醒：等待中的工具调用需要立刻知道"不用等了"
        wake(approvalId, saved);
        wakeChannel.publish(approvalId);
        return saved;
    }

    /**
     * 把超过保留期的待审批标记为过期，返回处理条数。
     *
     * <p>这是安全阀而不是垃圾回收：一条申请放了几十天，其对话上下文早已过去，
     * 此时若还有人点批准，执行的是一次与当前状态无关的写操作。</p>
     */
    @Transactional
    public int expireStale() {
        int days = keepDays <= 0 ? 7 : keepDays;
        int n = repository.expirePendingBefore(LocalDateTime.now().minusDays(days));
        if (n > 0) {
            log.info("[approval] 已将 {} 条超过 {} 天未处理的申请标记为过期", n, days);
        }
        return n;
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 按申请里存的**参数快照**执行。
     *
     * <p>当前只有两个写工具，用显式分派；新增写工具时在这里加一个分支
     * （并同时在 {@code ApprovalService} 的 javadoc 与 `backlog.md` 里登记）。
     * 刻意不做"通用反射调用 ToolRegistry"—— 那会让任何注册的工具都能被审批链路执行，
     * 等于绕过了工具自己的语义（比如 {@code sandbox.run} 有自己的白名单逻辑）。</p>
     */
    private String execute(ToolApproval row) throws Exception {
        JsonNode args = row.getToolArgs() == null || row.getToolArgs().isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(row.getToolArgs());

        return switch (row.getToolName()) {
            case "fs_write_file" -> {
                FileWriteService.WriteOutcome out = fileWriteService.writeFile(
                        args.path("path").asText(""),
                        args.path("content").asText(""),
                        args.path("createOnly").asBoolean(false));
                yield "已" + (out.overwritten() ? "覆盖" : "新建") + " " + out.path()
                        + "（" + out.chars() + " 字符）";
            }
            case "fs_edit_file" -> {
                FileWriteService.EditOutcome out = fileWriteService.editFile(
                        args.path("path").asText(""),
                        args.path("oldString").asText(""),
                        args.path("newString").asText(""),
                        args.path("replaceAll").asBoolean(false));
                yield "已编辑 " + out.path() + "（替换 " + out.replaced() + " 处，"
                        + out.linesBefore() + " 行 → " + out.linesAfter() + " 行）";
            }
            default -> throw BizException.badRequest("审批执行不支持的工具：" + row.getToolName()
                    + "（若刚新增了写工具，请在 ApprovalService.execute 中登记）");
        };
    }

    /** 取一条待审批记录，并校验租户与申请人（fail-closed）。 */
    private ToolApproval requirePending(String tenantId, String userId, String approvalId) {
        if (approvalId == null || approvalId.isBlank()) {
            throw BizException.badRequest("缺少审批 ID");
        }
        if (userId == null || userId.isBlank()) {
            throw BizException.badRequest("缺少用户身份，无法审批");
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        ToolApproval row = repository.findByApprovalIdAndTenantId(approvalId, tenant)
                .orElseThrow(() -> BizException.notFound("审批记录", approvalId));
        // 只能批自己提交的：多一个人能批，就多一条"帮别人放行危险操作"的路径
        if (row.getUserId() != null && !row.getUserId().equals(userId)) {
            log.warn("[approval] 用户 {} 试图审批他人（{}）的申请 {}", userId, row.getUserId(), approvalId);
            throw BizException.forbidden("只能审批自己提交的操作");
        }
        if (row.getStatus() != ToolApprovalStatus.pending) {
            throw BizException.conflict("该审批已处理（当前状态：" + row.getStatus().label() + "）");
        }
        return row;
    }

    /** 供接口层返回的轻量视图。 */
    public static Map<String, Object> toView(ToolApproval a) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("approvalId", a.getApprovalId());
        m.put("toolName", a.getToolName());
        m.put("summary", a.getSummary());
        m.put("toolArgs", a.getToolArgs());
        m.put("status", a.getStatus() == null ? null : a.getStatus().name());
        m.put("statusLabel", a.getStatus() == null ? null : a.getStatus().label());
        m.put("agentId", a.getAgentId());
        m.put("sessionId", a.getSessionId());
        m.put("result", a.getResult());
        m.put("errorMsg", a.getErrorMsg());
        m.put("decidedBy", a.getDecidedBy());
        m.put("decidedAt", a.getDecidedAt() == null ? null : a.getDecidedAt().toString());
        m.put("createdAt", a.getCreatedAt() == null ? null : a.getCreatedAt().toString());
        // 回滚相关（前端据此决定是否显示「回滚」按钮）
        m.put("hasSnapshot", a.getSnapshotDir() != null && !a.getSnapshotDir().isBlank());
        m.put("rolledBackAt", a.getRolledBackAt() == null ? null : a.getRolledBackAt().toString());
        m.put("rollbackable", a.getStatus() == com.agentplatform.model.enums.ToolApprovalStatus.approved
                && a.getRolledBackAt() == null
                && (a.getErrorMsg() == null || a.getErrorMsg().isBlank()));
        return m;
    }
}
