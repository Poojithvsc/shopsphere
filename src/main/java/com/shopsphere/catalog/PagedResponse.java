package com.shopsphere.catalog;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A stable pagination envelope: {@code content} plus a small {@code page} block. Defined explicitly
 * rather than serialising Spring Data's {@code Page} so the JSON contract is ours and survives
 * Spring upgrades (Boot 3.3 deprecates serialising {@code PageImpl} directly).
 */
record PagedResponse<T>(List<T> content, PageInfo page) {

    static <E, T> PagedResponse<T> of(Page<E> page, Function<E, T> mapper) {
        List<T> content = page.getContent().stream().map(mapper).toList();
        return new PagedResponse<>(content, new PageInfo(
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }

    record PageInfo(int number, int size, long totalElements, int totalPages) {
    }
}
