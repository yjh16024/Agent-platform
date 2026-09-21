package com.agentplatform.core.dict;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.dict.DictDtos.DictItemView;
import com.agentplatform.core.security.rbac.RbacContext;
import com.agentplatform.core.security.rbac.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据字典查询接口（前端下拉用）。
 *
 * <h3>为什么是 {@code dict:read} 而不是"登录即可访问"</h3>
 * 读字典是**普适能力**（每个页面的下拉都要用），所以这个权限码给了全部三个内置角色
 * （admin / operator / viewer 都拿得到）。用权限码而不是"不加注解"是为了让权限清单完整 ——
 * 界面上能一眼看到"谁能读字典"。
 *
 * <h3>两个接口的分工</h3>
 * <ul>
 *   <li>{@code GET /{typeCode}}：单个字典，用于按需懒加载；</li>
 *   <li>{@code GET ?codes=a,b,c}：批量，**前端启动时用这个一次拉全量**，
 *       否则一个页面 N 个下拉就是 N 次 HTTP。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/dicts")
@RequiredArgsConstructor
public class DictController {

    /** 与 {@code RbacPermission.DICT_READ} 一致；注解值要用编译期常量，所以这里写字面量。 */
    private static final String PERM_READ = "dict:read";

    private final DictService dictService;

    /** 取一个字典的启用项。类型不存在时返回空列表（不抛异常），避免整页因某个下拉崩掉。 */
    @GetMapping("/{typeCode}")
    @RequiresPermission(PERM_READ)
    public ApiResponse<List<DictItemView.Option>> items(@PathVariable String typeCode) {
        return ApiResponse.ok(dictService.options(RbacContext.tenantId(), typeCode));
    }

    /**
     * 批量取多个字典。
     *
     * <p>用 GET + query 而不是 POST：语义是**读**，能被浏览器/网关缓存，也便于在地址栏调试。</p>
     *
     * @param codes 逗号分隔的字典类型编码；也支持重复参数（{@code ?codes=a&codes=b}）
     */
    @GetMapping
    @RequiresPermission(PERM_READ)
    public ApiResponse<Map<String, List<DictItemView.Option>>> batch(@RequestParam(required = false) List<String> codes) {
        List<String> wanted = new ArrayList<>();
        if (codes != null) {
            for (String raw : codes) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                // 兼容 ?codes=a,b,c 这种一次性传法（前端拼参数更省事）
                for (String one : raw.split(",")) {
                    String c = one.trim();
                    if (!c.isEmpty()) {
                        wanted.add(c);
                    }
                }
            }
        }
        Map<String, List<DictItemView.Option>> data = dictService.optionsBatch(RbacContext.tenantId(), wanted);
        // 用一个有序 Map 包装，保证响应里的 key 顺序与请求顺序一致（便于人眼比对）
        return ApiResponse.ok(new LinkedHashMap<>(data));
    }

    /** 暴露给前端的一份"内置字典类型清单"，避免前端硬编码这些码。 */
    @GetMapping("/types")
    @RequiresPermission(PERM_READ)
    public ApiResponse<List<String>> builtinTypes() {
        return ApiResponse.ok(Arrays.asList(
                DictSeeder.TYPE_LOG_LEVEL,
                DictSeeder.TYPE_LOG_CATEGORY,
                DictSeeder.TYPE_CHUNK_STRATEGY,
                DictSeeder.TYPE_BUILTIN_TOOL,
                DictSeeder.TYPE_MODEL_PROVIDER_CHAT,
                DictSeeder.TYPE_MODEL_PROVIDER_EMBEDDING));
    }
}
