package com.agentplatform.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 数据字典项（某个类型下的一个选项，如「日志级别 / INFO」）。
 *
 * <p>存的是 {@code typeCode} 而**不是** {@code dictTypeId}：与 {@code sys_user_role} 存业务 ID
 * 的既有做法一致，按类型取项时免回表 {@code sys_dict_type}。</p>
 */
@Entity
@Table(name = "sys_dict_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysDictItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "dict_item_id", nullable = false, length = 64)
    private String dictItemId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 所属字典类型编码；与 {@code sys_dict_type.type_code} 逻辑关联（数据库层面不建外键）。 */
    @Column(name = "type_code", nullable = false, length = 64)
    private String typeCode;

    /** 存进去的值（如 {@code INFO}）—— 会被写进业务字段，所以是稳定标识，**不要跟着标签改**。 */
    @Column(name = "item_value", nullable = false, length = 100)
    private String itemValue;

    /** 给人看的标签（如「信息」）—— 可以随时改，改了不影响已存的数据。 */
    @Column(name = "item_label", nullable = false, length = 100)
    private String itemLabel;

    /** 升序排列，越小越靠前。 */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /** 状态：{@code active} / {@code disabled}；停用的项不出现在下拉里，但历史数据仍能解析出标签。 */
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = "active";

    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
