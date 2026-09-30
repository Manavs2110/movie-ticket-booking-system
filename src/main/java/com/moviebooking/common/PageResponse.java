package com.moviebooking.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One page of results. Offset lists fill {@code page}/{@code size}/{@code hasNext};
 * cursor lists fill {@code nextCursor} (null on the last page).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageResponse<T>(List<T> items, Integer page, int size, boolean hasNext, String nextCursor) {

    /** Build an offset page from a query that fetched {@code size + 1} rows. */
    public static <T> PageResponse<T> offset(List<T> fetched, int page, int size) {
        boolean hasNext = fetched.size() > size;
        return new PageResponse<>(hasNext ? List.copyOf(fetched.subList(0, size)) : List.copyOf(fetched),
                page, size, hasNext, null);
    }

    public static <T> PageResponse<T> cursor(List<T> items, int size, String nextCursor) {
        return new PageResponse<>(List.copyOf(items), null, size, nextCursor != null, nextCursor);
    }
}
