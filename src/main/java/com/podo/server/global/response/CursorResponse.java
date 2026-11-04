package com.podo.server.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CursorResponse<T>(
        List<T> items,
        String nextCursor
) {

    public CursorResponse {
        items = List.copyOf(items);
    }
}