package com.agentplatform.gateway;

import com.agentplatform.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * JWT 鉴权全局过滤器。
 * <p>
 * 校验 Authorization: Bearer {token}，解析 tenant_id / user_id 注入请求头，
 * 供下游核心服务消费。白名单路径（健康检查、登录）跳过鉴权。
 * </p>
 */
@Slf4j
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final List<String> WHITELIST = List.of(
            "/actuator/health", "/api/v1/auth/login", "/api/v1/auth/token", "/api/v1/auth/register");

    private final String jwtSecret;

    public JwtAuthFilter(@Value("${agent-platform.jwt.secret:change-me-to-a-256-bit-secret-key-for-production-use-please}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 白名单跳过
        if (WHITELIST.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing Bearer token");
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = JwtUtil.parseToken(jwtSecret, token);
            String tenantId = JwtUtil.tenantId(claims);
            String userId = claims.get("user_id", String.class);

            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header("X-Tenant-Id", tenantId)
                    .header("X-User-Id", userId == null ? "" : userId)
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return unauthorized(exchange, "Invalid token");
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set("Content-Type", "application/json");
        byte[] body = ("{\"success\":false,\"code\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}