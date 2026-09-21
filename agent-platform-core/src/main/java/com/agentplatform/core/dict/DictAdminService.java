package com.agentplatform.core.dict;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.dict.DictDtos.DictItemView;
import com.agentplatform.core.dict.DictDtos.DictTypeView;
import com.agentplatform.core.dict.DictDtos.SaveDictItemRequest;
import com.agentplatform.core.dict.DictDtos.SaveDictTypeRequest;
import com.agentplatform.model.entity.SysDictItem;
import com.agentplatform.model.entity.SysDictType;
import com.agentplatform.model.repository.SysDictItemRepository;
import com.agentplatform.model.repository.SysDictTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据字典**写**侧（管理界面用）。
 *
 * <h3>与 {@link DictService} 的分工</h3>
 * 与 RBAC 里 {@code RbacService} / {@code RbacAdminService} 的划分一致：读侧在热路径上（吃缓存），
 * 写侧是低频管理操作（直查库、写完显式失效缓存）。混在一起会让热路径那个类的职责变脏。
 *
 * <h3>三处"防自伤"校验</h3>
 * <ol>
 *   <li><b>内置类型不可删</b>：删掉 {@code log_level} 会让日志页的下拉直接变空 ——
 *       用户把界面搞坏了却不知道是自己干的。允许改标签、允许停用项；</li>
 *   <li><b>内置字典项不可删</b>（可停用、可改标签）：理由同上；</li>
 *   <li><b>编码不可改</b>：{@code typeCode} 改了会让该类型下的字典项全部失联
 *       （item 存的是 type_code）；{@code itemValue} 改了会让**已经写进业务数据**的值
 *       解析不出标签。两者都只能新建/停用，不能改 —— 与"角色编码不可改"同理。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictAdminService {

    private final SysDictTypeRepository typeRepository;
    private final SysDictItemRepository itemRepository;
    /** 写完立刻失效缓存，否则表现为"改了字典但界面没变，重启才对"。 */
    private final DictService dictService;

    private static final String ACTIVE = "active";

    // ------------------------------------------------------------------ 类型

    @Transactional(readOnly = true)
    public List<DictTypeView> listTypes(String tenantId) {
        List<SysDictType> types = typeRepository.findByTenantIdOrderByTypeCodeAsc(tenantId);
        List<DictTypeView> out = new ArrayList<>(types.size());
        for (SysDictType t : types) {
            long count = itemRepository.countByTenantIdAndTypeCode(tenantId, t.getTypeCode());
            out.add(view(t, count));
        }
        return out;
    }

    @Transactional
    public DictTypeView createType(String tenantId, SaveDictTypeRequest req) {
        String code = requireCode(req == null ? null : req.typeCode(), "字典类型编码");
        if (typeRepository.existsByTenantIdAndTypeCode(tenantId, code)) {
            throw BizException.conflict("字典类型编码已存在：" + code);
        }
        SysDictType saved = typeRepository.save(SysDictType.builder()
                .dictTypeId(IdGenerator.generate("dict"))
                .tenantId(tenantId)
                .typeCode(code)
                .typeName(blankToNull(req.typeName()) == null ? code : req.typeName().trim())
                .remark(blankToNull(req.remark()))
                .status(normalizeStatus(req.status()))
                .builtin(false)
                .build());
        log.info("[dict] 已创建字典类型 {}", code);
        return view(saved, 0);
    }

    /** 更新名称/备注/状态。**刻意不改 typeCode**，理由见类注释。 */
    @Transactional
    public DictTypeView updateType(String tenantId, String dictTypeId, SaveDictTypeRequest req) {
        SysDictType type = requireType(tenantId, dictTypeId);
        if (req != null) {
            if (blankToNull(req.typeName()) != null) {
                type.setTypeName(req.typeName().trim());
            }
            if (req.remark() != null) {
                type.setRemark(blankToNull(req.remark()));
            }
            if (req.status() != null && !req.status().isBlank()) {
                type.setStatus(normalizeStatus(req.status()));
            }
        }
        SysDictType saved = typeRepository.save(type);
        dictService.evict(tenantId, saved.getTypeCode());
        return view(saved, itemRepository.countByTenantIdAndTypeCode(tenantId, saved.getTypeCode()));
    }

    /** 删除类型，连带删掉它的全部字典项（否则会留下永远查不到的孤儿项）。 */
    @Transactional
    public void deleteType(String tenantId, String dictTypeId) {
        SysDictType type = requireType(tenantId, dictTypeId);
        if (Boolean.TRUE.equals(type.getBuiltin())) {
            throw BizException.badRequest("内置字典类型不可删除（可改标签或停用）：" + type.getTypeCode());
        }
        itemRepository.deleteByTenantIdAndTypeCode(tenantId, type.getTypeCode());
        typeRepository.delete(type);
        dictService.evict(tenantId, type.getTypeCode());
        log.info("[dict] 已删除字典类型 {}（连同其字典项）", type.getTypeCode());
    }

    // ------------------------------------------------------------------ 字典项

    /** 管理界面要看**全部**项（含停用），所以直查库、不吃 {@link DictService} 的缓存。 */
    @Transactional(readOnly = true)
    public List<DictItemView> listItems(String tenantId, String typeCode) {
        List<SysDictItem> items = itemRepository
                .findByTenantIdAndTypeCodeOrderBySortOrderAscItemValueAsc(tenantId, typeCode);
        List<DictItemView> out = new ArrayList<>(items.size());
        for (SysDictItem i : items) {
            out.add(view(i));
        }
        return out;
    }

    @Transactional
    public DictItemView createItem(String tenantId, SaveDictItemRequest req) {
        String typeCode = requireCode(req == null ? null : req.typeCode(), "所属字典类型编码");
        // 类型必须存在：否则会创建出永远显示不出来的孤儿项
        typeRepository.findByTenantIdAndTypeCode(tenantId, typeCode)
                .orElseThrow(() -> BizException.badRequest("字典类型不存在：" + typeCode));

        String value = requireCode(req.itemValue(), "字典项值");
        if (itemRepository.existsByTenantIdAndTypeCodeAndItemValue(tenantId, typeCode, value)) {
            throw BizException.conflict("该字典下已存在值：" + value);
        }
        SysDictItem saved = itemRepository.save(SysDictItem.builder()
                .dictItemId(IdGenerator.generate("dict_item"))
                .tenantId(tenantId)
                .typeCode(typeCode)
                .itemValue(value)
                .itemLabel(blankToNull(req.itemLabel()) == null ? value : req.itemLabel().trim())
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .status(normalizeStatus(req.status()))
                .remark(blankToNull(req.remark()))
                .build());
        dictService.evict(tenantId, typeCode);
        log.info("[dict] 已创建字典项 {}={}", typeCode, value);
        return view(saved);
    }

    /** 更新标签/排序/状态/备注。**刻意不改 itemValue**，理由见类注释。 */
    @Transactional
    public DictItemView updateItem(String tenantId, String dictItemId, SaveDictItemRequest req) {
        SysDictItem item = requireItem(tenantId, dictItemId);
        if (req != null) {
            if (blankToNull(req.itemLabel()) != null) {
                item.setItemLabel(req.itemLabel().trim());
            }
            if (req.sortOrder() != null) {
                item.setSortOrder(req.sortOrder());
            }
            if (req.status() != null && !req.status().isBlank()) {
                item.setStatus(normalizeStatus(req.status()));
            }
            if (req.remark() != null) {
                item.setRemark(blankToNull(req.remark()));
            }
        }
        SysDictItem saved = itemRepository.save(item);
        dictService.evict(tenantId, saved.getTypeCode());
        return view(saved);
    }

    @Transactional
    public void deleteItem(String tenantId, String dictItemId) {
        SysDictItem item = requireItem(tenantId, dictItemId);
        // 内置类型的选项一律不可删 —— 与"内置类型不可删"同一条理由（删了对应页面下拉会空）。
        // 这里按**所属类型**判断，而不是给 item 单独加 builtin 列：少一列就少一处要同步的状态，
        // 而"内置类型的项必然是内置项"这个语义本来就成立。
        if (isBuiltinType(tenantId, item.getTypeCode())) {
            throw BizException.badRequest("内置字典的选项不可删除（可停用或改标签）：" + item.getItemValue());
        }
        itemRepository.delete(item);
        dictService.evict(tenantId, item.getTypeCode());
        log.info("[dict] 已删除字典项 {}={}", item.getTypeCode(), item.getItemValue());
    }

    private boolean isBuiltinType(String tenantId, String typeCode) {
        return typeRepository.findByTenantIdAndTypeCode(tenantId, typeCode)
                .map(t -> Boolean.TRUE.equals(t.getBuiltin()))
                .orElse(false);
    }

    // ------------------------------------------------------------------ 内部工具

    private SysDictType requireType(String tenantId, String dictTypeId) {
        SysDictType type = typeRepository.findByDictTypeId(dictTypeId)
                .orElseThrow(() -> BizException.notFound("字典类型", dictTypeId));
        if (!tenantId.equals(type.getTenantId())) {
            // 不暴露"存在但属于别的租户"，统一当作找不到
            throw BizException.notFound("字典类型", dictTypeId);
        }
        return type;
    }

    private SysDictItem requireItem(String tenantId, String dictItemId) {
        SysDictItem item = itemRepository.findByDictItemId(dictItemId)
                .orElseThrow(() -> BizException.notFound("字典项", dictItemId));
        if (!tenantId.equals(item.getTenantId())) {
            throw BizException.notFound("字典项", dictItemId);
        }
        return item;
    }

    /** 编码类字段：必填、去空格，并限制长度以免超出列宽。 */
    private String requireCode(String v, String field) {
        String s = blankToNull(v);
        if (s == null) {
            throw BizException.badRequest(field + "不能为空");
        }
        if (s.length() > 64) {
            throw BizException.badRequest(field + "过长（最多 64 字符）");
        }
        // 编码会被当作 key 使用，禁掉空白字符避免前端传参时被 URL 编码差异坑到
        if (s.chars().anyMatch(Character::isWhitespace)) {
            throw BizException.badRequest(field + "不能包含空白字符");
        }
        return s;
    }

    private String normalizeStatus(String status) {
        return "disabled".equalsIgnoreCase(String.valueOf(status).trim()) ? "disabled" : ACTIVE;
    }

    private String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    private DictTypeView view(SysDictType t, long itemCount) {
        return new DictTypeView(t.getDictTypeId(), t.getTypeCode(), t.getTypeName(),
                t.getRemark(), t.getStatus(), Boolean.TRUE.equals(t.getBuiltin()), itemCount);
    }

    private DictItemView view(SysDictItem i) {
        return new DictItemView(i.getDictItemId(), i.getTypeCode(), i.getItemValue(),
                i.getItemLabel(), i.getSortOrder(), i.getStatus(), i.getRemark());
    }
}
