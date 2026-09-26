package com.agentplatform.core.memory;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.model.entity.UserFact;
import com.agentplatform.model.entity.UserFactCandidate;
import com.agentplatform.model.enums.UserFactCategory;
import com.agentplatform.model.enums.UserFactSource;
import com.agentplatform.model.repository.UserFactCandidateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 长期记忆：**自动抽取候选**的收集与「用户确认」环节。
 *
 * <h3>为什么必须要有"确认"这一步（设计上的硬约定）</h3>
 * 这个功能的本意是省去用户手填画像的麻烦，但它同时带来一个风险：
 * **系统会在用户背后记下关于他的事**。若抽取结果直接生效，用户永远不会知道
 * "模型为什么突然用这种语气跟我说话"，也无法纠正一条记错的信息 ——
 * 这份数据会悄无声息地一直影响之后所有对话。
 *
 * <p>所以顺序不能倒过来：**先让用户对这份数据有控制感（看得见、改得动、删得掉），
 * 才谈得上自动往里写**。这也是 V19 当时只做"用户主动填写"、
 * 把 {@link UserFactSource#auto} 的字段先预留出来的原因。</p>
 *
 * <h3>候选的两条出路</h3>
 * <ul>
 *   <li><b>采纳</b>：搬进 {@code sys_user_fact}（来源标 auto），随后会被注入提示词；</li>
 *   <li><b>忽略</b>：**打 {@code rejectedAt} 时间戳但不删除** —— 抽取侧会跳过已拒绝的键，
 *       否则下次对话又把同样的事抽出来重新问一遍，那是这个功能最容易变成骚扰的地方。</li>
 * </ul>
 *
 * <p>所有方法强制收显式 {@code userId}（同 {@link UserFactService} 的约定：
 * 不自己从上下文取身份，让"在异步抽取链路里复用本服务"不会静默读错人）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserFactCandidateService {

    private final UserFactCandidateRepository repository;
    private final UserFactService userFactService;

    /** 单条候选值的长度上限（DB 列是 1000，这里留出余量并给用户明确报错）。 */
    private static final int MAX_VALUE_CHARS = 1000;

    /** 单次抽取最多接受多少条候选（防止模型一次性吐出几十条把待办区淹掉）。 */
    private static final int MAX_OFFER_PER_BATCH = 5;

    // ------------------------------------------------------------------ 查询

    /** 待确认列表（最新在前）。 */
    @Transactional(readOnly = true)
    public List<UserFactCandidate> pending(String tenantId, String userId) {
        if (isBlank(userId)) {
            return List.of();
        }
        return repository.findByTenantIdAndUserIdAndRejectedAtIsNullOrderByCreatedAtDesc(
                normalizeTenant(tenantId), userId);
    }

    /** 待确认条数（界面角标用）。 */
    @Transactional(readOnly = true)
    public long countPending(String tenantId, String userId) {
        if (isBlank(userId)) {
            return 0L;
        }
        return repository.countByTenantIdAndUserIdAndRejectedAtIsNull(
                normalizeTenant(tenantId), userId);
    }

    // ------------------------------------------------------------------ 抽取写入

    /**
     * 提交一批由模型抽取出来的候选（供自动抽取链路调用）。
     *
     * <p>三条约束都在这里落地：</p>
     * <ol>
     *   <li><b>upsert</b>：同一用户同一 key 只保留一条（DB 唯一约束），重复抽取覆盖最新值，
     *       而不是堆一串待办让用户审到烦；</li>
     *   <li><b>跳过已拒绝</b>：用户点过「忽略」的键不再重新打扰；</li>
     *   <li><b>已有正式画像的键也跳过</b>：那不是"待确认"，而是"已知且已确认"。</li>
     * </ol>
     *
     * @return 实际写入（新建或更新）的条数
     */
    @Transactional
    public int offer(String tenantId, String userId, List<CandidateDraft> drafts,
                     String sourceSessionId, String extractedBy) {
        if (isBlank(userId) || drafts == null || drafts.isEmpty()) {
            return 0;
        }
        String tenant = normalizeTenant(tenantId);
        int accepted = 0;

        for (CandidateDraft draft : drafts) {
            if (accepted >= MAX_OFFER_PER_BATCH) {
                log.debug("[memory] 抽取候选超过单批上限 {}，其余丢弃", MAX_OFFER_PER_BATCH);
                break;
            }
            String key = trim(draft.key(), 100);
            String value = trim(draft.value(), MAX_VALUE_CHARS);
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            // 已经有正式画像了 ⇒ 不需要再确认一遍
            if (userFactService.findByKey(tenant, userId, key) != null) {
                continue;
            }

            UserFactCandidate existing =
                    repository.findByTenantIdAndUserIdAndFactKey(tenant, userId, key).orElse(null);

            if (existing != null) {
                // ① 用户明确忽略过 ⇒ 不再打扰（这是"记得拒绝"的落点）
                if (!existing.isPending()) {
                    continue;
                }
                // ② 值没变就别无谓写库（省一次 UPDATE 与一次 updated_at 变更）
                if (value.equals(existing.getFactValue())
                        && draft.category() == existing.getCategory()) {
                    continue;
                }
                existing.setFactValue(value);
                existing.setCategory(draft.category());
                existing.setSourceSessionId(sourceSessionId);
                existing.setExtractedBy(extractedBy);
                repository.save(existing);
                accepted++;
                continue;
            }

            repository.save(UserFactCandidate.builder()
                    .candidateId(IdGenerator.generate("fctc"))
                    .tenantId(tenant)
                    .userId(userId)
                    .factKey(key)
                    .factValue(value)
                    .category(draft.category() == null ? UserFactCategory.other : draft.category())
                    .sourceSessionId(sourceSessionId)
                    .extractedBy(extractedBy)
                    .build());
            accepted++;
        }
        return accepted;
    }

    /** 抽取出来的一条候选（模型输出的最小单元）。 */
    public record CandidateDraft(String key, String value, UserFactCategory category) {
    }

    // ------------------------------------------------------------------ 用户决策

    /**
     * 采纳一条候选：搬进正式画像（来源标 auto），并删除该候选。
     *
     * @return 写入后的正式画像
     */
    @Transactional
    public UserFact adopt(String tenantId, String userId, String candidateId) {
        String tenant = normalizeTenant(tenantId);
        UserFactCandidate candidate = requireOwned(tenant, userId, candidateId);

        UserFact fact = userFactService.save(tenant, userId,
                candidate.getFactKey(), candidate.getFactValue(),
                candidate.getCategory() == null ? null : candidate.getCategory().name(),
                UserFactSource.auto);

        repository.delete(candidate);
        log.info("[memory] 用户 {} 采纳画像候选 {}（{}）", userId, candidate.getFactKey(), candidateId);
        return fact;
    }

    /**
     * 忽略一条候选：**打时间戳而非删除**。
     *
     * <p>保留记录是为了"记住这次拒绝"—— 抽取时看到 {@code rejectedAt} 非空就跳过该键，
     * 否则下一轮对话又会把同一件事抽出来重新问一遍。</p>
     */
    @Transactional
    public void reject(String tenantId, String userId, String candidateId) {
        UserFactCandidate candidate = requireOwned(normalizeTenant(tenantId), userId, candidateId);
        if (candidate.isPending()) {
            candidate.setRejectedAt(LocalDateTime.now());
            repository.save(candidate);
        }
    }

    /** 采纳全部待确认候选，返回采纳条数。 */
    @Transactional
    public int adoptAll(String tenantId, String userId) {
        List<UserFactCandidate> list = pending(tenantId, userId);
        int n = 0;
        for (UserFactCandidate c : list) {
            try {
                adopt(tenantId, userId, c.getCandidateId());
                n++;
            } catch (Exception e) {
                // 单条失败不影响其余（例如值恰好与某条正式画像冲突）
                log.warn("[memory] 采纳候选 {} 失败，已跳过：{}", c.getCandidateId(), e.getMessage());
            }
        }
        return n;
    }

    /** 忽略全部待确认候选，返回条数。 */
    @Transactional
    public int rejectAll(String tenantId, String userId) {
        List<UserFactCandidate> list = pending(tenantId, userId);
        LocalDateTime now = LocalDateTime.now();
        for (UserFactCandidate c : list) {
            c.setRejectedAt(now);
        }
        repository.saveAll(list);
        return list.size();
    }

    /**
     * 一键清除该用户的全部候选（**含已忽略的记录**），返回删除条数。
     *
     * <p>用户的隐私出口：既然系统会观察他，就要允许"这些观察一点不留"。
     * 连已忽略记录一起清掉是刻意的 —— 用户说"别记我的事"时，
     * 留着"他曾经拒绝过 X"本身也是一种记录。</p>
     */
    @Transactional
    public long clearAll(String tenantId, String userId) {
        requireUserId(userId);
        long deleted = repository.deleteByTenantIdAndUserId(normalizeTenant(tenantId), userId);
        if (deleted > 0) {
            log.info("[memory] 用户 {} 清除了画像候选 {} 条", userId, deleted);
        }
        return deleted;
    }

    // ------------------------------------------------------------------ 小工具

    private UserFactCandidate requireOwned(String tenantId, String userId, String candidateId) {
        requireUserId(userId);
        if (isBlank(candidateId)) {
            throw BizException.badRequest("缺少候选 ID");
        }
        return repository.findByCandidateIdAndTenantIdAndUserId(candidateId, tenantId, userId)
                .orElseThrow(() -> BizException.notFound("画像候选", candidateId));
    }

    private static void requireUserId(String userId) {
        if (isBlank(userId)) {
            throw BizException.badRequest("缺少用户身份，无法操作画像候选");
        }
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String v = s.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String normalizeTenant(String tenantId) {
        return isBlank(tenantId) ? "default" : tenantId.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
