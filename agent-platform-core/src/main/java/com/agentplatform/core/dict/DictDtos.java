package com.agentplatform.core.dict;

import java.util.List;

/**
 * 数据字典的出入参。
 *
 * <p>用 record（强类型）而不是自由 Map：与 {@code RbacAdminDtos} 一致，
 * 字段名走 camelCase，前端 TS 侧能直接映射成 interface。</p>
 */
public final class DictDtos {

    private DictDtos() {
    }

    /** 字典类型视图。 */
    public record DictTypeView(String dictTypeId,
                               String typeCode,
                               String typeName,
                               String remark,
                               String status,
                               /** 内置类型：不可删除（可改标签、可停用），其下字典项同样不可删。 */
                               boolean builtin,
                               /** 该类型下的字典项数量（列表里直接显示，省一次请求）。 */
                               long itemCount) {
    }

    /**
     * 字典项视图。
     *
     * <p>**没有 builtin 字段**：是否需要"不可删除"由**所属类型**决定
     * （内置类型的项就是内置项）。少一个字段就少一处会不一致的状态。</p>
     */
    public record DictItemView(String dictItemId,
                               String typeCode,
                               String itemValue,
                               String itemLabel,
                               Integer sortOrder,
                               String status,
                               String remark) {
        /** 前端下拉要的最小字段（批量取时用它，少传无用数据）。 */
        public record Option(String value, String label) {
        }
    }

    /** 新建/修改字典类型。修改时 {@code typeCode} 会被忽略（编码不可改）。 */
    public record SaveDictTypeRequest(String typeCode,
                                      String typeName,
                                      String remark,
                                      String status) {
    }

    /** 新建/修改字典项。修改时 {@code typeCode} 与 {@code itemValue} 都会被忽略（两者都是稳定标识）。 */
    public record SaveDictItemRequest(String typeCode,
                                      String itemValue,
                                      String itemLabel,
                                      Integer sortOrder,
                                      String status,
                                      String remark) {
    }

    /** 批量取：{@code typeCode → 选项列表}。 */
    public record DictBatchView(String typeCode, List<DictItemView.Option> options) {
    }
}
