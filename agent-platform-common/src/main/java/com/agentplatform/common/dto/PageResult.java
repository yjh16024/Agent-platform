package com.agentplatform.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 分页结果封装。
 *
 * @param <T> 元素类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> items;
    private long total;
    private int page;
    private int size;
    private int totalPages;

    /**
     * 从 Spring Data {@link Page} 转换。
     */
    public static <E, T> PageResult<T> from(Page<E> page, Function<E, T> mapper) {
        return PageResult.<T>builder()
                .items(page.getContent().stream().map(mapper).toList())
                .total(page.getTotalElements())
                .page(page.getNumber())
                .size(page.getSize())
                .totalPages(page.getTotalPages())
                .build();
    }
}