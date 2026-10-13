package com.nexora.hotel.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Envoltura paginada estable usada como {@code data} en todo listado paginado. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return of(page.map(mapper));
    }
}
