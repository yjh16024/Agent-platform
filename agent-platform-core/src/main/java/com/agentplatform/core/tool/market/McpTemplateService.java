package com.agentplatform.core.tool.market;

import com.agentplatform.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 常用 MCP server 的**命令模板**清单。
 *
 * <h3>为什么需要它</h3>
 * 平台已支持 stdio 传输（{@code POST /tools/mcp/stdio}），但注册时要手写一整条启动命令 ——
 * 官方 reference server 的包名（{@code @modelcontextprotocol/server-filesystem} 这类）很长、
 * 还常带必填参数（filesystem 必须给一个目录），用户很难一次写对。
 *
 * <p>更要紧的是：**这些命令不该由程序从 MCP Registry 推导**。实测官方 registry 的包型条目
 * 只给包名与 registryType、**不给怎么运行**，推导出来的命令没有人验证过；
 * 而本清单里的条目是**人写好并核对过包名**的（npm / PyPI 均可查到），
 * 用户只需选模板、填少量参数。这与 HTTP 工具市场（{@code tool-market.json}）是同一套思路：
 * **清单在资源文件里，新增条目不需要改代码**。</p>
 *
 * <p>⚠️ 清单只保证**包名真实存在**；命令行的**参数形式**以各 server 官方文档为准 ——
 * 因此前端允许用户在注册前直接编辑完整命令行，不把模板当成不可改的最终答案。</p>
 */
@Slf4j
@Service
public class McpTemplateService {

    private static final String RESOURCE = "mcp-server-templates.json";

    /** 清单只读且不变，首次解析后缓存（与 {@link ToolMarketService} 同一做法）。 */
    private volatile List<Map<String, Object>> cached;

    /** 全部模板。读取失败时返回空列表，不抛异常 —— 顶部市场不该拖垮工具页面。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            List<Map<String, Object>> parsed = parse();
            cached = parsed;
            return parsed;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parse() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = JsonUtils.toJsonNode(json);
            List<Map<String, Object>> out = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode n : root) {
                    out.add(JsonUtils.mapper().convertValue(n, Map.class));
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            log.warn("[mcp-template] 读取清单失败 {}: {}", RESOURCE, e.getMessage());
            return List.of();
        }
    }
}
