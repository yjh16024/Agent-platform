package com.agentplatform.core.memory;

import com.agentplatform.model.entity.UserFactCandidate;
import com.agentplatform.model.enums.UserFactCategory;
import com.agentplatform.model.repository.UserFactCandidateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserFactCandidateService} 的核心不变量测试（2026-09-26）。
 *
 * <h3>为什么只测这几条</h3>
 * 这个功能的价值不在"能抽取"，而在**抽取结果不会绕过用户**。所以测试集中在三条边界上：
 * <ol>
 *   <li><b>已有正式画像的键不再生成候选</b> —— 否则用户会被要求确认一件他已经确认过的事；</li>
 *   <li><b>被忽略过的键不再重新出现</b> —— 这是"记得拒绝"，也是该功能最容易变成骚扰的地方；</li>
 *   <li><b>忽略是打时间戳而不是删除</b> —— 删掉就记不住了，下轮又会抽出来。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class UserFactCandidateServiceTest {

    @Mock
    private UserFactCandidateRepository repository;
    @Mock
    private UserFactService userFactService;

    private UserFactCandidateService service;

    @BeforeEach
    void setUp() {
        service = new UserFactCandidateService(repository, userFactService);
    }

    private UserFactCandidate candidate(String key, String value, LocalDateTime rejectedAt) {
        return UserFactCandidate.builder()
                .candidateId("fctc_" + key)
                .tenantId("t1")
                .userId("u1")
                .factKey(key)
                .factValue(value)
                .category(UserFactCategory.other)
                .rejectedAt(rejectedAt)
                .build();
    }

    @Test
    @DisplayName("已有正式画像的键：不再生成候选（用户不必确认一件已确认过的事）")
    void skipsKeysThatAlreadyHaveConfirmedFact() {
        when(userFactService.findByKey("t1", "u1", "职业")).thenReturn(mockFact());

        int n = service.offer("t1", "u1",
                List.of(new UserFactCandidateService.CandidateDraft("职业", "Java 工程师", UserFactCategory.other)),
                "sess_1", "deepseek-chat");

        assertEquals(0, n, "已有确认过的同名画像 ⇒ 不该再生成候选");
        verify(repository, never()).save(any(UserFactCandidate.class));
    }

    @Test
    @DisplayName("★ 被忽略过的键：不再重新生成候选（记住这次拒绝）")
    void skipsKeysUserAlreadyRejected() {
        // 该键存在一条候选，且用户已忽略（rejectedAt 非空）
        when(repository.findByTenantIdAndUserIdAndFactKey("t1", "u1", "所在地"))
                .thenReturn(Optional.of(candidate("所在地", "北京", LocalDateTime.now())));

        int n = service.offer("t1", "u1",
                List.of(new UserFactCandidateService.CandidateDraft("所在地", "上海", UserFactCategory.other)),
                "sess_1", "deepseek-chat");

        assertEquals(0, n, "用户拒绝过的键不得重新打扰");
        verify(repository, never()).save(any(UserFactCandidate.class));
    }

    @Test
    @DisplayName("待确认的键：值有变化则更新（upsert 而非堆积）")
    void updatesPendingCandidateWhenValueChanged() {
        UserFactCandidate existing = candidate("职业", "Java 工程师", null);
        when(repository.findByTenantIdAndUserIdAndFactKey("t1", "u1", "职业"))
                .thenReturn(Optional.of(existing));

        int n = service.offer("t1", "u1",
                List.of(new UserFactCandidateService.CandidateDraft("职业", "后端架构师", UserFactCategory.other)),
                "sess_9", "deepseek-chat");

        assertEquals(1, n);
        assertEquals("后端架构师", existing.getFactValue(), "应覆盖为新值，而不是新增第二条");
        assertEquals("sess_9", existing.getSourceSessionId(), "来源会话应更新，便于追溯");
        verify(repository).save(existing);
    }

    @Test
    @DisplayName("待确认的键：值没变则不写库（省一次无谓的 UPDATE）")
    void skipsWhenValueUnchanged() {
        UserFactCandidate existing = candidate("职业", "Java 工程师", null);
        when(repository.findByTenantIdAndUserIdAndFactKey("t1", "u1", "职业"))
                .thenReturn(Optional.of(existing));

        int n = service.offer("t1", "u1",
                List.of(new UserFactCandidateService.CandidateDraft("职业", "Java 工程师", UserFactCategory.other)),
                "sess_1", "deepseek-chat");

        assertEquals(0, n);
        verify(repository, never()).save(any(UserFactCandidate.class));
    }

    @Test
    @DisplayName("★ 忽略：打时间戳而非删除（删掉就记不住，下轮又会抽出来）")
    void rejectMarksTimestampInsteadOfDeleting() {
        UserFactCandidate existing = candidate("职业", "Java 工程师", null);
        when(repository.findByCandidateIdAndTenantIdAndUserId("fctc_职业", "t1", "u1"))
                .thenReturn(Optional.of(existing));

        service.reject("t1", "u1", "fctc_职业");

        assertEquals(true, existing.getRejectedAt() != null, "必须记下拒绝时间");
        verify(repository).save(existing);
        verify(repository, never()).delete(any(UserFactCandidate.class));
    }

    @Test
    @DisplayName("单批上限：一次抽取最多接受 5 条（避免待办区被一次淹没）")
    void capsBatchSize() {
        when(userFactService.findByKey(anyString(), anyString(), anyString())).thenReturn(null);
        when(repository.findByTenantIdAndUserIdAndFactKey(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        List<UserFactCandidateService.CandidateDraft> many = List.of(
                new UserFactCandidateService.CandidateDraft("k1", "v1", UserFactCategory.other),
                new UserFactCandidateService.CandidateDraft("k2", "v2", UserFactCategory.other),
                new UserFactCandidateService.CandidateDraft("k3", "v3", UserFactCategory.other),
                new UserFactCandidateService.CandidateDraft("k4", "v4", UserFactCategory.other),
                new UserFactCandidateService.CandidateDraft("k5", "v5", UserFactCategory.other),
                new UserFactCandidateService.CandidateDraft("k6", "v6", UserFactCategory.other),
                new UserFactCandidateService.CandidateDraft("k7", "v7", UserFactCategory.other));

        int n = service.offer("t1", "u1", many, "sess_1", "deepseek-chat");

        assertEquals(5, n, "超出上限的应被丢弃");
    }

    @Test
    @DisplayName("空 key / 空 value 的草稿被丢弃（模型偶尔会吐出不完整项）")
    void dropsBlankDrafts() {
        when(userFactService.findByKey(anyString(), anyString(), anyString())).thenReturn(null);
        when(repository.findByTenantIdAndUserIdAndFactKey(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        int n = service.offer("t1", "u1", List.of(
                new UserFactCandidateService.CandidateDraft("  ", "值", UserFactCategory.other),
                new UserFactCandidateService.CandidateDraft("键", "  ", UserFactCategory.other),
                new UserFactCandidateService.CandidateDraft("好键", "好值", UserFactCategory.other)),
                "sess_1", "deepseek-chat");

        assertEquals(1, n, "只有完整的那条应被接受");
    }

    private static com.agentplatform.model.entity.UserFact mockFact() {
        return com.agentplatform.model.entity.UserFact.builder()
                .factId("fact_x").tenantId("t1").userId("u1")
                .factKey("职业").factValue("Java 工程师")
                .category(UserFactCategory.other).build();
    }
}
