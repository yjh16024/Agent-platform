package com.agentplatform.core.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 安全启动守卫（P0 加固）。
 * <p>
 * 项目大量使用「change-me-*」默认密钥，本地/演示无妨，但一旦开启生产鉴权
 * （{@code agent-platform.security.enabled=true}）却仍用默认密钥，等于裸奔。
 * 因此：
 * </p>
 * <ul>
 *   <li>鉴权已开启 + JWT 密钥仍为默认值 → <b>拒绝启动</b>（fail-fast，倒逼覆盖配置）；</li>
 *   <li>模型 Key 加密主密钥仍为默认值 → 警告（本地可运行，但密文等价可解）。</li>
 * </ul>
 */
@Slf4j
@Component
public class SecurityStartupGuard {

    private static final String DEFAULT_JWT_SECRET = "change-me-to-a-256-bit-secret-key-for-production-use-please";
    private static final String DEFAULT_MODEL_KEY = "change-me-model-key-encryption-32b";

    private final boolean securityEnabled;
    private final String jwtSecret;
    private final String modelKeyEncKey;

    public SecurityStartupGuard(
            @Value("${agent-platform.security.enabled:false}") boolean securityEnabled,
            @Value("${agent-platform.jwt.secret:change-me-to-a-256-bit-secret-key-for-production-use-please}") String jwtSecret,
            @Value("${agent-platform.secrets.model-key:change-me-model-key-encryption-32b}") String modelKeyEncKey) {
        this.securityEnabled = securityEnabled;
        this.jwtSecret = jwtSecret;
        this.modelKeyEncKey = modelKeyEncKey;
    }

    @PostConstruct
    public void verify() {
        boolean defaultJwt = DEFAULT_JWT_SECRET.equals(jwtSecret);
        boolean defaultModelKey = DEFAULT_MODEL_KEY.equals(modelKeyEncKey);

        if (securityEnabled && defaultJwt) {
            throw new IllegalStateException("""
                    检测到 agent-platform.security.enabled=true 但 JWT 密钥仍为默认值（change-me-*）。
                    这等于生产环境裸奔——任何人都能用默认密钥签发 token 访问全部租户数据。
                    请在环境变量设置 JWT_SECRET（建议 ≥32 字节随机串）后再开启鉴权。
                    """);
        }
        if (securityEnabled) {
            log.info("[security] core 侧鉴权已开启，密钥来自外部配置");
        }
        if (defaultJwt) {
            log.warn("[security] JWT 密钥仍为默认值，仅限本地/演示使用。生产环境请通过 JWT_SECRET 覆盖。");
        }
        if (defaultModelKey) {
            log.warn("[security] 模型 API Key 加密主密钥仍为默认值，仅限本地/演示使用。生产请通过 MODEL_KEY_ENC_KEY 覆盖，否则密文可被解密。");
        }
    }
}
