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
 * 数据字典类型（一类选项，如「日志级别」）。
 *
 * <p>与既有实体的约定一致：{@code id} 是自增物理主键，{@code dictTypeId} 是业务 ID
 * （{@code IdGenerator.generate("dict")} 生成），对外一律只暴露业务 ID。</p>
 *
 * <p>为什么类型与字典项分两张表：单表要靠 {@code SELECT DISTINCT type_code} 反推类型，
 * 无法表达"刚建好、还没加项的字典"，管理界面（左类型 / 右字典项）也就做不出来。</p>
 */
@Entity
@Table(name = "sys_dict_type")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysDictType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "dict_type_id", nullable = false, length = 64)
    private String dictTypeId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 类型编码，同租户内唯一（uk_tenant_type_code）；前端按它取字典。 */
    @Column(name = "type_code", nullable = false, length = 64)
    private String typeCode;

    @Column(name = "type_name", nullable = false, length = 100)
    private String typeName;

    @Column(name = "remark", length = 500)
    private String remark;

    /**
     * 状态：{@code active} / {@code disabled}（取值与 {@code sys_user.status} 一致）。
     * <p>用 String 而不新建枚举：只有两个取值，且这里不需要类型安全的比较逻辑。</p>
     */
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = "active";

    /**
     * 内置类型（由 {@code DictSeeder} 按后端枚举/常量初始化）。
     * <p>内置的**禁止删除、允许改标签** —— 删掉 {@code log_level} 会让日志页的下拉直接变空，
     * 属于"用户把自己界面搞坏"的自伤，挡在服务层。</p>
     */
    @Column(name = "builtin", nullable = false)
    @Builder.Default
    private Boolean builtin = false;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
