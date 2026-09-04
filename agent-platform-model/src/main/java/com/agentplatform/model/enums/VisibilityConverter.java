package com.agentplatform.model.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@link Visibility} 与数据库存储字符串的互转。
 * <p>
 * 因 {@code private} / {@code public} 是 Java 关键字，枚举使用 {@code private_} / {@code public_}，
 * 落库时还原为合法字符串。
 * </p>
 */
@Converter(autoApply = true)
public class VisibilityConverter implements AttributeConverter<Visibility, String> {

    @Override
    public String convertToDatabaseColumn(Visibility attribute) {
        return attribute == null ? null : attribute.toDb();
    }

    @Override
    public Visibility convertToEntityAttribute(String dbData) {
        return Visibility.fromDb(dbData);
    }
}