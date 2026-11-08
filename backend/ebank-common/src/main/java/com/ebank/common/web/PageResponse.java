package com.ebank.common.web;

import java.util.List;
import org.springframework.data.domain.Page;

/** A stable JSON shape for paged results. Spring's own Page is not meant to be serialized. */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
