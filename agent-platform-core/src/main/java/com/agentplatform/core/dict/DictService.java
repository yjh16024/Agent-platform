package com.agentplatform.core.dict;

import com.agentplatform.core.dict.DictDtos.DictItemView;
import com.agentplatform.model.entity.SysDictItem;
import com.agentplatform.model.repository.SysDictItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据字典**读**侧。
 *
 * <h3>缓存策略（有意设计，别当优化删掉）</h3>
 * 字典读发生在**每个页面渲染**时（侧栏、下拉、筛选器都要），单页可能并发十几个请求。
 * 若每次都查库，一个页面的十几条查询全砸在 {@code sys_dict_item} 上。
 * 所以按 <b>租户 → 类型 → 启用项</b> 做进程内缓存：
 * <ul>
 *   <li><b>不设 TTL</b>：TTL 只会造成"过期后第一批请求变慢"，正确性交给**显式失效**
 *       （{@code DictAdminService} 写完立刻调用 {@link #evict}），与
 *       {@code RbacService.evictRole} 的思路一致；</li>
 *   <li><b>不用 Redis</b>：这是"进程内、可重建"的派生数据，丢了最多多查一次库；
 *       多实例部署时 Redis 还要额外处理一致性，桌面单机更是纯负担；</li>
 *   <li><b>缓存的是整类型的全量启用项</b>，不是单个 code —— 字典项数量极少（个位数），
 *       按类型整块取比逐项取缓存命中率高得多。</li>
 * </ul>
 *
 * <p><b>只缓存启用项</b>：停用项不该出现在下拉里。但管理界面要看到全部，
 * 所以那边走 {@code DictAdminService}（直查库，不吃这个缓存）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictService {

    private final SysDictItemRepository itemRepository;

    /** 租户 ID → (typeCode → 启用项)。外层键带租户，避免多租户互相污染。 */
    private final Map<String, Map<String, List<DictItemView.Option>>> cache = new ConcurrentHashMap<>();

    /**
     * 取某个字典的启用项（下拉用）。
     *
     * @return 按 sortOrder 升序；类型不存在时返回**空列表**而不是抛异常 ——
     *         前端某个下拉拿不到选项不该让整页崩掉。
     */
    @Transactional(readOnly = true)
    public List<DictItemView.Option> options(String tenantId, String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            return List.of();
        }
        return cacheFor(tenantId).computeIfAbsent(typeCode, k -> loadOptions(tenantId, k));
    }

    /**
     * 批量取多个字典（前端启动时一次性拉全量用）。
     *
     * <p>为什么要有它：如果前端每个下拉各自请求一次，一个页面会打出十几个 HTTP。
     * 启动时一次批量取回来存在前端内存里，后续下拉全是同步读取。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, List<DictItemView.Option>> optionsBatch(String tenantId, Collection<String> typeCodes) {
        Map<String, List<DictItemView.Option>> out = new LinkedHashMap<>();
        if (typeCodes == null || typeCodes.isEmpty()) {
            return out;
        }
        Map<String, List<DictItemView.Option>> tenantCache = cacheFor(tenantId);
        for (String code : typeCodes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            // 命中缓存就走缓存，避免"批量接口绕过缓存"这种反直觉行为
            out.put(code, tenantCache.computeIfAbsent(code, k -> loadOptions(tenantId, k)));
        }
        return out;
    }

    private List<DictItemView.Option> loadOptions(String tenantId, String typeCode) {
        List<SysDictItem> items = itemRepository
                .findByTenantIdAndTypeCodeOrderBySortOrderAscItemValueAsc(tenantId, typeCode);
        List<DictItemView.Option> out = new ArrayList<>(items.size());
        for (SysDictItem i : items) {
            if ("active".equals(i.getStatus())) {
                out.add(new DictItemView.Option(i.getItemValue(), i.getItemLabel()));
            }
        }
        return List.copyOf(out);
    }

    private Map<String, List<DictItemView.Option>> cacheFor(String tenantId) {
        return cache.computeIfAbsent(tenantId == null ? "default" : tenantId, k -> new ConcurrentHashMap<>());
    }

    /**
     * 使某个字典的缓存失效。**写侧改完必须调用** —— 否则表现为"改了字典但界面没变，重启才对"。
     */
    public void evict(String tenantId, String typeCode) {
        String tid = tenantId == null ? "default" : tenantId;
        Map<String, List<DictItemView.Option>> tenantCache = cache.get(tid);
        if (tenantCache != null) {
            tenantCache.remove(typeCode);
            log.debug("[dict] 缓存已失效：tenant={} type={}", tid, typeCode);
        }
    }

    /** 整个租户的字典缓存失效（批量操作或排查问题时可调用）。 */
    public void evictAll(String tenantId) {
        cache.remove(tenantId == null ? "default" : tenantId);
    }
}
