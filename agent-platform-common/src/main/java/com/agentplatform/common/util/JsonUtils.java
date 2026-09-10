package com.agentplatform.common.util;

import com.agentplatform.common.exception.BizException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 序列化工具（基于 Jackson 3）。
 * <p>
 * 提供类型安全的序列化/反序列化，避免散落各处的 ObjectMapper 实例。
 * </p>
 * <p>
 * <b>Jackson 3 迁移说明</b>：坐标/包名由 {@code com.fasterxml.jackson}（databind/core）迁至
 * {@code tools.jackson}；配置改为构造期 builder（Jackson 3 移除了 {@code ObjectMapper#configure}）；
 * 异常由 checked 的 {@code JsonProcessingException} 变为 unchecked 的 {@link JacksonException}。
 * 注解包（{@code com.fasterxml.jackson.annotation.*}）Jackson 3 仍保留，无需迁移。
 * </p>
 */
public final class JsonUtils {

    private JsonUtils() {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public static JsonMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JacksonException e) {
            throw BizException.internal("Failed to serialize object to JSON", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JacksonException e) {
            throw BizException.internal("Failed to deserialize JSON to " + clazz.getSimpleName(), e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JacksonException e) {
            throw BizException.internal("Failed to deserialize JSON", e);
        }
    }

    public static JsonNode toJsonNode(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw BizException.internal("Failed to parse JSON", e);
        }
    }

    /**
     * 深拷贝对象（通过 JSON 序列化往返）。
     */
    public static <T> T deepCopy(Object obj, Class<T> clazz) {
        return fromJson(toJson(obj), clazz);
    }
}
