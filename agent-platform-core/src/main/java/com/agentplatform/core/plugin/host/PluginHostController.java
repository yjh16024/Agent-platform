package com.agentplatform.core.plugin.host;

import com.agentplatform.framework.api.ModelAdapter;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.core.model.secret.ModelBindingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件 Host 能力 —— **后端那一半**（2026-10-19，P4 阶段 3）。
 *
 * <h3>为什么只有"模型"在这里，而 fs/exec/http 在主进程</h3>
 * Electron 主进程本来就是一个完整 Node 运行时，本机能力（文件 / 命令 / 出站网络）放那里
 * 最直接、且没有版本漂移。但**模型调用不行**：它需要平台的密钥解密、厂商适配、
 * 路由策略（直连 / 平台默认 / 本地 Mock）—— 这些只在 Java 后端里有。
 * 所以这一层按"能力在哪就放哪"切，而不是硬凑成一个来源。
 *
 * <h3>插件怎么用</h3>
 * <pre>
 * const r = await ctx.host.model.chat({ system: '你是助手', message: '你好' });
 * console.log(r.content, r.promptTokens);
 * </pre>
 *
 * <p>⚠️ 它走的是**平台默认对话模型**（与"未单独配置模型的智能体"同一套解析）——
 * 插件不应该、也没能力自己指定厂商密钥。返回里带 {@code routing}，
 * 便于排查"到底用的直连还是 Mock"。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugin-host")
@RequiredArgsConstructor
public class PluginHostController {

    private final ModelRouter modelRouter;
    private final ModelBindingService modelBinding;

    /**
     * 一次单轮模型调用（无对话历史）。
     *
     * <p>刻意**不做流式**：插件侧用流式的价值在于长文本实时显示，而那属于"插件自己
     * 用 {@code ctx.http} 直连厂商"的场景；这里提供的是一个稳定的同步入口。</p>
     */
    @PostMapping("/model/chat")
    public ApiResponse<Map<String, Object>> modelChat(@RequestBody Map<String, Object> body) {
        String message = str(body.get("message"));
        if (message == null || message.isBlank()) {
            throw BizException.badRequest("message 不能为空");
        }
        String system = str(body.get("system"));
        Double temperature = number(body.get("temperature"));
        Integer maxTokens = integer(body.get("maxTokens"));

        // 无智能体上下文 → 走「平台默认对话模型」；未配置时会落 LOCAL(Mock)，不会抛错
        ModelBindingService.ResolvedModel resolved = modelBinding.resolve(null);
        ModelAdapter.ChatRequest req = new ModelAdapter.ChatRequest(
                resolved.model(),
                system,
                message,
                temperature,
                maxTokens,
                Map.of(),
                List.of(),
                resolved.baseUrl(),
                resolved.apiKey(),
                List.of(),
                null);

        ModelAdapter.ChatResponse resp;
        try {
            resp = modelRouter.chat(resolved.provider(), req);
        } catch (RuntimeException e) {
            /*
             * 把底层异常翻成"插件作者能看懂"的一句话。
             * 不翻的表现是：前端只看到 500「内部服务器错误」，而真实原因是
             * 「网关 401 凭据无效」——插件作者会以为是插件端点坏了（2026-10-19 冒烟时正是如此）。
             */
            throw BizException.internal(
                    "模型调用失败（routing=" + resolved.routing() + "，provider=" + resolved.provider() + "）："
                            + e.getMessage(),
                    e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", resp.content());
        out.put("promptTokens", resp.promptTokens());
        out.put("completionTokens", resp.completionTokens());
        out.put("latencyMs", resp.latencyMs());
        // 排查用：DIRECT / PLATFORM / GATEWAY / LOCAL
        out.put("routing", resolved.routing() == null ? null : resolved.routing().name());
        return ApiResponse.ok(out);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Double number(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer integer(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
