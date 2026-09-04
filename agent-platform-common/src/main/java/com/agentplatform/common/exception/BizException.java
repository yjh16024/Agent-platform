package com.agentplatform.common.exception;

import lombok.Getter;

/**
 * 业务异常基类。
 * <p>
 * 所有业务层异常均继承此类，通过 {@code errorCode} 和 {@code message} 携带
 * 可被前端/诊断引擎消费的结构化错误信息。
 * </p>
 */
@Getter
public class BizException extends RuntimeException {

    private final String errorCode;

    public BizException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 常用错误码工厂方法。
     */
    public static BizException notFound(String resource, String id) {
        return new BizException("NOT_FOUND", resource + " not found: " + id);
    }

    public static BizException badRequest(String message) {
        return new BizException("BAD_REQUEST", message);
    }

    public static BizException unauthorized(String message) {
        return new BizException("UNAUTHORIZED", message);
    }

    public static BizException forbidden(String message) {
        return new BizException("FORBIDDEN", message);
    }

    public static BizException conflict(String message) {
        return new BizException("CONFLICT", message);
    }

    public static BizException internal(String message) {
        return new BizException("INTERNAL_ERROR", message);
    }

    public static BizException internal(String message, Throwable cause) {
        return new BizException("INTERNAL_ERROR", message, cause);
    }

    public static BizException validation(String message) {
        return new BizException("VALIDATION_ERROR", message);
    }
}