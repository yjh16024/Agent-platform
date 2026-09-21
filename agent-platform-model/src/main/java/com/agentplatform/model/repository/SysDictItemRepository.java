package com.agentplatform.model.repository;

import com.agentplatform.model.entity.SysDictItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 数据字典项仓储。
 */
public interface SysDictItemRepository extends JpaRepository<SysDictItem, Long> {

    Optional<SysDictItem> findByDictItemId(String dictItemId);

    boolean existsByTenantIdAndTypeCodeAndItemValue(String tenantId, String typeCode, String itemValue);

    /**
     * 按类型取项（管理界面用，含停用项）。
     * <p>排序写进方法名而不是靠数据库默认顺序 —— 顺序不稳定时界面每次刷新都会跳。</p>
     */
    List<SysDictItem> findByTenantIdAndTypeCodeOrderBySortOrderAscItemValueAsc(String tenantId, String typeCode);

    /** 批量取（前端一次拉多个字典时用，避免 N 次查询）。 */
    List<SysDictItem> findByTenantIdAndTypeCodeInOrderBySortOrderAscItemValueAsc(String tenantId,
                                                                                Collection<String> typeCodes);

    long countByTenantIdAndTypeCode(String tenantId, String typeCode);

    long countByTenantId(String tenantId);

    /**
     * 删除某个类型的全部字典项（删类型时级联清理）。
     * <p>用 JPQL 批量删而不是 `findBy...` 后逐个 delete：后者在项很多时会发出 N 条 DELETE。</p>
     */
    @Modifying
    @Transactional
    @Query("delete from SysDictItem i where i.tenantId = :tenantId and i.typeCode = :typeCode")
    void deleteByTenantIdAndTypeCode(@Param("tenantId") String tenantId, @Param("typeCode") String typeCode);
}
