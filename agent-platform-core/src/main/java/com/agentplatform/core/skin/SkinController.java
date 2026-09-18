package com.agentplatform.core.skin;

import com.agentplatform.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 皮肤市场接口。
 *
 * <p>能力链条：{@code /market/read}（读取任意皮肤市场地址）→ {@code /market/install}（按条目的
 * install.target 下载源码）→ {@code /theme}（从皮肤包抽配色，映射成平台主题）。</p>
 *
 * <p>与自动化市场的区别：皮肤是**桌面端专属**能力——项目已决定逐渐弃用 Web UI，
 * 所以这套接口不做多租户隔离，安装目录也在本机（{@code agent-platform.skins.dir}）。</p>
 */
@RestController
@RequestMapping("/api/v1/skins")
@RequiredArgsConstructor
public class SkinController {

    private final SkinMarketService skinMarketService;

    /**
     * 读取一个皮肤市场地址，返回归一化后的皮肤清单。
     * <p>body：{@code url} 必填（站点首页 / GitHub 仓库 / catalog.json 直链均可）；
     * {@code refresh=true} 绕过仓库文件树缓存。</p>
     */
    @PostMapping("/market/read")
    public ApiResponse<Map<String, Object>> readMarket(@RequestBody Map<String, Object> body) {
        String url = str(body, "url");
        boolean refresh = truthy(body == null ? null : body.get("refresh"));
        return ApiResponse.ok(skinMarketService.readMarket(url, refresh), "read");
    }

    /**
     * 安装某条市场条目。直接把 {@code /market/read} 返回的那个条目对象回传即可
     * （需要 {@code id} 与 {@code install.target}）。
     */
    @PostMapping("/market/install")
    public ApiResponse<Map<String, Object>> install(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(skinMarketService.install(body), "installed");
    }

    /** 已安装的皮肤。 */
    @GetMapping("/installed")
    public ApiResponse<List<Map<String, Object>>> installed() {
        return ApiResponse.ok(skinMarketService.installed());
    }

    /** 卸载皮肤。body：{@code id}。 */
    @PostMapping("/uninstall")
    public ApiResponse<Map<String, Object>> uninstall(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(skinMarketService.uninstall(str(body, "id")), "uninstalled");
    }

    /**
     * 图片代理：把市场里的封面/截图（多为 raw.githubusercontent.com 外链）由后端取回，
     * 解决国内直连显示不出来的问题。仅放行白名单域名。
     */
    @GetMapping("/proxy")
    public ResponseEntity<byte[]> proxy(@RequestParam String url) {
        return imageResponse(url, skinMarketService.proxyImage(url));
    }

    /**
     * 皮肤客户端 bundle（方案甲的接缝）：返回自注册脚本文本，
     * 形如 {@code window.__ModuleLoader__.load({ id, factory: (require) => ({name, inject, apply}) })}。
     * <p>前端在 {@code __ModuleLoader__} 就绪后执行它，即可让皮肤跑它自己的 JS。</p>
     */
    @GetMapping("/bundle")
    public ApiResponse<Map<String, Object>> bundle(@RequestParam String id) {
        return ApiResponse.ok(skinMarketService.clientBundle(id));
    }

    /** 按文件扩展名给出图片 Content-Type，并带一段客户端缓存。 */
    private static ResponseEntity<byte[]> imageResponse(String name, byte[] data) {
        String lower = name == null ? "" : name.toLowerCase();
        MediaType type = lower.endsWith(".png") ? MediaType.IMAGE_PNG
                : lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? MediaType.IMAGE_JPEG
                : lower.endsWith(".webp") ? MediaType.parseMediaType("image/webp")
                : lower.endsWith(".gif") ? MediaType.IMAGE_GIF
                : lower.endsWith(".svg") ? MediaType.parseMediaType("image/svg+xml")
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
                .body(data);
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static boolean truthy(Object v) {
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }
}
