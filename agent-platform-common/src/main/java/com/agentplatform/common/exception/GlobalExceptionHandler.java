package com.agentplatform.common.exception;

import com.agentplatform.common.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 * 统一将各类异常转换为 {@link ApiResponse} 格式返回。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException ex) {
        log.warn("Business exception: [{}] {}", ex.getErrorCode(), ex.getMessage());
        HttpStatus status = switch (ex.getErrorCode()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "BAD_REQUEST", "VALIDATION_ERROR" -> HttpStatus.BAD_REQUEST;
            case "UNAUTHORIZED" -> HttpStatus.UNAUTHORIZED;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "CONFLICT" -> HttpStatus.CONFLICT;
            case "MODEL_UPSTREAM_ERROR" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status)
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    /**
     * 文件超过上传上限。
     * <p>Spring 在 multipart 解析阶段抛出，默认会被兜底成 500 且信息晦涩，
     * 这里转成 400 + 明确提示（含上限值）。</p>
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload size exceeded: {}", ex.getMessage());
        long max = ex.getMaxUploadSize() <= 0 ? 50L * 1024 * 1024 : ex.getMaxUploadSize();
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("FILE_TOO_LARGE",
                        "上传文件超过上限（" + (max / 1024 / 1024) + "MB），请压缩后重试"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_ERROR", detail));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        // 唯一键/约束冲突（DataIntegrityViolationException 等）→ 409，
        // 避免把 "could not execute statement ... Duplicate entry" 这类原始 SQL 直接抛给用户
        Throwable violation = findDuplicateViolation(ex);
        if (violation != null) {
            log.warn("Duplicate/constraint violation: {}", violation.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("CONFLICT", "数据已存在或违反唯一约束，请勿重复提交：" + brief(violation)));
        }
        log.error("Unhandled exception", ex);
        // 原样透出异常信息，避免上游错误被吞成笼统的 "An unexpected error occurred"
        String message = ex.getMessage() == null || ex.getMessage().isBlank()
                ? "An unexpected error occurred" : ex.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", message));
    }

    /**
     * 在异常链中查找「重复/唯一约束冲突」。
     * <p>用类名 + 消息判断而非直接依赖 spring-dao，保持 common 模块零新增依赖。</p>
     */
    private static Throwable findDuplicateViolation(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String name = c.getClass().getName();
            String msg = c.getMessage() == null ? "" : c.getMessage();
            boolean duplicateType = name.contains("DataIntegrityViolation")
                    || name.contains("DuplicateKey")
                    || name.contains("SQLIntegrityConstraintViolation");
            if (duplicateType || msg.contains("Duplicate entry")) {
                return c;
            }
        }
        return null;
    }

    private static String brief(Throwable t) {
        String m = t.getMessage() == null ? "" : t.getMessage();
        int i = m.indexOf("Duplicate entry");
        String core = i >= 0 ? m.substring(i) : m;
        return core.length() > 160 ? core.substring(0, 160) : core;
    }
}