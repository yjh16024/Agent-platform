package com.agentplatform.core.skill.market;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 一个「取件通道」：负责列仓库文件与读文件内容。
 *
 * <p>之所以抽象成通道而不是写死一个地址，是因为**国内访问 GitHub 的可用路径各不相同**：
 * jsDelivr 数据接口、各类 GitHub 加速代理、直连，各自在不同网络下时通时不通。
 * 服务层按顺序试，谁通用谁，并把结果记下来（见 {@link SkillMarketService}）。</p>
 */
public abstract class SkillChannel {

    protected static final String UA = "agent-platform/skill-market";

    protected final OkHttpClient http;

    protected SkillChannel(OkHttpClient http) {
        this.http = http;
    }

    /** 分组名：{@code jsdelivr} / {@code ghproxy} / {@code github}，用于配置里按组启停。 */
    public abstract String group();

    /** 唯一标识（诊断信息里展示）。 */
    public abstract String id();

    /** 人类可读的名字。 */
    public abstract String label();

    /** 轻量探测该通道当前是否可用。 */
    public abstract boolean available();

    /** 仓库默认分支；取不到返回 {@code null}（由服务层换别的通道或实测 main/master）。 */
    public abstract String defaultBranch(String repo) throws IOException;

    /** 列出仓库的全部文件，返回**仓库相对路径**（无前导 `/`）。 */
    public abstract List<String> listFiles(String repo, String branch) throws IOException;

    /** 读取文件；文件确定不存在时抛 {@link Miss}（不必再试别的通道）。 */
    public abstract byte[] read(String repo, String branch, String path) throws IOException;

    /** 资源确定不存在（HTTP 404/410）——换通道也没用。 */
    public static class Miss extends IOException {
        public Miss(String message) {
            super(message);
        }
    }

    /** 探测用超时（秒）——诊断时不能因为某条通道卡住而拖几十秒。 */
    protected static final int PROBE_TIMEOUT_SECONDS = 8;

    protected byte[] fetch(String url, long maxBytes) throws IOException {
        return fetch(url, maxBytes, 0);
    }

    /**
     * GET 拿字节；404/410 抛 {@link Miss}，其余非 2xx 抛普通 {@link IOException}（可换通道再试）。
     *
     * @param timeoutSeconds &gt;0 时覆盖本次调用的总超时（含连接与读取），用于探测
     */
    protected byte[] fetch(String url, long maxBytes, int timeoutSeconds) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .build();
        Call call = http.newCall(req);
        if (timeoutSeconds > 0) {
            call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS);
        }
        try (Response resp = call.execute()) {
            int code = resp.code();
            if (code == 404 || code == 410) {
                throw new Miss(url + " -> HTTP " + code);
            }
            if (!resp.isSuccessful()) {
                throw new IOException(url + " -> HTTP " + code);
            }
            ResponseBody body = resp.body();
            if (body == null) {
                throw new IOException(url + " -> 空响应体");
            }
            byte[] data = body.bytes();
            if (maxBytes > 0 && data.length > maxBytes) {
                throw new IOException("响应过大（" + data.length + " > " + maxBytes + "）：" + url);
            }
            return data;
        }
    }

    /** GET 拿文本（UTF-8）。 */
    protected String fetchText(String url, long maxBytes) throws IOException {
        return new String(fetch(url, maxBytes), StandardCharsets.UTF_8);
    }

    /** 逐段 URL 编码（保留 `/`）——技能文件名里的空格与中文都很常见。 */
    protected static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/", -1)) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }
}
