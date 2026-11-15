package com.podo.server.books;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record PlaceBookResponse(List<Item> items, List<Warning> warnings,
                               @Schema(description = "우리 서버가 확인한 시각. 제공자 데이터 갱신 시각이 아님") Instant checkedAt) {
    @Schema(name = "PlaceBookItem")
    public record Item(String title, List<String> libraries) {}

    @Schema(description = "순서 고정: 미선정, 설정 없음, 공급자 실패, 첫 100행 초과, 선정 도서 미발견")
    public enum Warning {
        NOT_CURATED, PROVIDER_NOT_CONFIGURED, PROVIDER_UNAVAILABLE, RESULTS_TRUNCATED, CURATED_BOOK_NOT_FOUND
    }
}
