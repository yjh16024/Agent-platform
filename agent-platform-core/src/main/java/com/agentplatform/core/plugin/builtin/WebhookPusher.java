package com.agentplatform.core.plugin.builtin;

import com.agentplatform.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群机器人 webhook 推送 —— <b>渠道适配 + 发送</b>的共用实现。
 *
 * <h3>为什么抽出来</h3>
 * 告警推送（{@code builtin_notifier}）与用量预警（{@code builtin_usage_guard}）都需要
 * "往飞书/钉钉/企微/Slack 发一条文本"。这段逻辑里有一处<b>极易写错且极难排查</b>的细节 ——
 * 各家的 body 字段名不同：
 * <pre>
 *   飞书   {"msg_type":"text","content":{"text":"..."}}      ← msg_type（下划线）
 *   钉钉   {"msgtype":"text","text":{"content":"..."}}        ← msgtype（无下划线）
 *   企微   {"msgtype":"text","text":{"content":"..."}}
 *   Slack  {"text":"..."}
 * </pre>
 * 发错的症状是「配了但收不到」—— 对方多半静默丢弃，而人会先去怀疑 webhook 地址填错了。
 * 让两个插件共用同一份适配，至少保证<b>不会出现"一个插件能收到、另一个收不到"</b>。
 *
 * <h3>失败语义：静默，但留痕</h3>
 * 推送失败一律不抛异常，只记 WARN（含上游响应）。通知是"锦上添花"的通道，
 * 不能因为它挂了而让对话或事件派发失败；但若不记日志，"为什么没收到"就无从排查。
 */
@Slf4j
final class WebhookPusher {

    static final String CHANNEL_FEISHU = "feishu";
    static final String CHANNEL_DINGTALK = "dingtalk";
    static final String CHANNEL_WECOM = "wecom";
    static final String CHANNEL_SLACK = "slack";
    static final String CHANNEL_GENERIC = "generic";

    static final int DEFAULT_TIMEOUT_MS = 8000;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 按超时复用连接池（不同插件可能配不同超时）。 */
    private static final Map<Integer, OkHttpClient> CLIENTS = new ConcurrentHashMap<>();

    private WebhookPusher() {
    }

    /**
     * 一次推送的目标配置（不可变）。
     *
     * @param channel    渠道，决定 body 形状与是否加签；认不出的按 generic 处理
     * @param webhookUrl 群机器人地址
     * @param secret     钉钉加签密钥（可选）
     * @param timeoutMs  单次推送超时
     */
    record Config(String channel, String webhookUrl, String secret, int timeoutMs) {

        boolean hasWebhook() {
            return webhookUrl != null && !webhookUrl.isBlank();
        }

        boolean hasSecret() {
            return secret != null && !secret.isBlank();
        }

        String effectiveChannel() {
            return channel == null || channel.isBlank() ? CHANNEL_GENERIC : channel.toLowerCase().trim();
        }
    }

    /**
     * 发一条文本。
     *
     * @param reason 仅用于日志（如 {@code "event:agent.run.completed"}），便于回答"这条是谁发的"
     * @return 是否成功（上游 2xx）
     */
    static boolean push(Config cfg, String text, String reason) {
        if (cfg == null || !cfg.hasWebhook()) {
            return false;
        }
        String url = cfg.webhookUrl();
        if (CHANNEL_DINGTALK.equals(cfg.effectiveChannel()) && cfg.hasSecret()) {
            url = signDingTalk(url, cfg.secret());
        }
        int timeout = cfg.timeoutMs() > 0 ? cfg.timeoutMs() : DEFAULT_TIMEOUT_MS;
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(buildBody(cfg.effectiveChannel(), text), JSON))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "agent-platform/1.0")
                    .build();
            try (Response response = clientFor(timeout).newCall(request).execute()) {
                String resp = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    log.warn("[webhook] 推送失败 channel={} reason={} HTTP {} {}",
                            cfg.effectiveChannel(), reason, response.code(), snippet(resp));
                    return false;
                }
                log.info("[webhook] 已推送 channel={} reason={}", cfg.effectiveChannel(), reason);
                return true;
            }
        } catch (Exception e) {
            log.warn("[webhook] 推送异常 channel={} reason={} error={}",
                    cfg.effectiveChannel(), reason, e.getMessage());
            return false;
        }
    }

    /**
     * 按渠道组装 body。
     *
     * <p>包级可见以便测试直接断言形状 —— 这是本类存在的理由，必须能被钉住。</p>
     */
    static String buildBody(String channel, String text) {
        return switch (channel) {
            case CHANNEL_FEISHU -> JsonUtils.toJson(Map.of(
                    "msg_type", "text",
                    "content", Map.of("text", text)));
            case CHANNEL_DINGTALK, CHANNEL_WECOM -> JsonUtils.toJson(Map.of(
                    "msgtype", "text",
                    "text", Map.of("content", text)));
            case CHANNEL_SLACK, CHANNEL_GENERIC -> JsonUtils.toJson(Map.of("text", text));
            default -> JsonUtils.toJson(Map.of("text", text));
        };
    }

    /**
     * 钉钉的"加签"模式：把 {@code timestamp + "\n" + secret} 做 HmacSHA256 再 base64 + urlencode。
     *
     * <p>只对钉钉做 —— 其余渠道要么不需要签名，要么用的是不同的校验方式。</p>
     */
    static String signDingTalk(String webhookUrl, String secret) {
        long timestamp = System.currentTimeMillis();
        String toSign = timestamp + "\n" + secret;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = URLEncoder.encode(
                    Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8))),
                    StandardCharsets.UTF_8);
            String sep = webhookUrl.contains("?") ? "&" : "?";
            return webhookUrl + sep + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (Exception e) {
            // 签名失败就用原 URL（未开启加签的机器人本来就不需要）
            log.warn("[webhook] 钉钉签名失败，将用未加签的地址重试：{}", e.getMessage());
            return webhookUrl;
        }
    }

    private static OkHttpClient clientFor(int timeoutMs) {
        return CLIENTS.computeIfAbsent(timeoutMs, ms -> new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(Math.min(ms, 5000)))
                .readTimeout(Duration.ofMillis(ms))
                .build());
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
