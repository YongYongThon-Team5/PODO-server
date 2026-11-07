package com.podo.server.recommendation.service;

import com.podo.server.place.domain.Place;
import com.podo.server.place.repository.PlaceRepository;
import com.podo.server.place.service.PlaceService;
import com.podo.server.place.web.PlaceResponses.PlaceSummary;
import com.podo.server.recommendation.web.PopularResponses.Aggregation;
import com.podo.server.recommendation.web.PopularResponses.Item;
import com.podo.server.recommendation.web.PopularResponses.Period;
import com.podo.server.recommendation.web.PopularResponses.Result;
import com.podo.server.weather.Weather;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 전체 누적 방문 신고를 장소별 서로 다른 계정 수로 집계한 상위 15곳. 개인 취향·날씨와 무관하다. */
@Service
public class PopularService {
    private final PlaceRepository places;
    private final PlaceService placeService;

    public PopularService(PlaceRepository places, PlaceService placeService) {
        this.places = places;
        this.placeService = placeService;
    }

    @Transactional(readOnly = true)
    public Result popular() {
        OffsetDateTime asOf = OffsetDateTime.now(Weather.SEOUL);
        List<Object[]> rows = places.findPopularRows();
        List<UUID> ids = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        for (Object[] row : rows) {
            ids.add(UUID.fromString((String) row[0]));
            counts.add(((Number) row[1]).longValue());
        }
        Map<UUID, Place> byId = places.findAllById(ids).stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));
        List<Place> ordered = ids.stream().map(byId::get).toList();
        List<PlaceSummary> summaries = placeService.summaries(ordered);

        List<Item> items = new ArrayList<>();
        for (int i = 0; i < summaries.size(); i++) {
            items.add(new Item(i + 1, summaries.get(i), counts.get(i)));
        }
        Aggregation aggregation = new Aggregation("REPORTED_DISTINCT_ACCOUNTS",
                new Period("ALL_TIME", null, null), "ACCOUNT_PER_PLACE", "NAME_ASC_ID_ASC", asOf,
                RecommendationService.POLICY_VERSION);
        return new Result(items, aggregation, items.isEmpty() ? "COLLECTING" : null);
    }
}
