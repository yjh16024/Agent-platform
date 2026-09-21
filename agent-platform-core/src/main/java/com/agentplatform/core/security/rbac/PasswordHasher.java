package com.agentplatform.core.security.rbac;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码哈希。
 *
 * <h3>为什么是 BCrypt</h3>
 * 它是自带盐、可调工作因子的慢哈希，抵抗彩虹表与离线暴破；
 * 相比自己用 JDK 的 PBKDF2 手搓，少一层"参数没选对"的风险（迭代次数、盐长度、编码方式）。
 * 本项目对密码只做「存哈希 + 校验」两件事，故只依赖 {@code spring-security-crypto} 这个工具库，
 * 不引入 Spring Security 的过滤器链（见 core/pom.xml 的说明）。
 *
 * <h3>工作因子为什么取 10</h3>
 * 默认值即 10（约 2^10 次迭代）。本项目是单机/内网部署，登录不是高频操作，
 * 10 在"抗暴破"与"登录响应时间"之间是常见折中；调高会让每次登录明显变慢（因子每 +1 耗时翻倍）。
 */
@Slf4j
@Component
public class PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 生成哈希（每次调用结果都不同 —— BCrypt 自带随机盐）。 */
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 校验密码。
     * <p>
     * 哈希为空或格式非法时返回 false 而不是抛异常：调用方是登录接口，
     * 这里抛异常会把"数据脏了"变成 500，让用户以为是服务故障。
     * </p>
     */
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return encoder.matches(rawPassword, storedHash);
        } catch (IllegalArgumentException e) {
            // 库里存了非 BCrypt 格式的值（例如手工插入的明文）
            log.warn("[rbac] 密码哈希格式非法，判定为校验失败：{}", e.getMessage());
            return false;
        }
    }
}
